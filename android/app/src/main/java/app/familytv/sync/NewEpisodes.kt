package app.familytv.sync

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.familytv.BuildConfig
import app.familytv.FamilyTvApp
import app.familytv.R
import app.familytv.data.Kind
import app.familytv.iptv.EpisodeGuide
import app.familytv.iptv.SeriesTitles
import app.familytv.portal.Profile
import app.familytv.ui.MainActivity
import app.familytv.ui.copyIds
import app.familytv.user.EpisodeWatchEntity
import app.familytv.user.NewEpisodeList
import app.familytv.user.Pending
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

/**
 * Watches favourite shows for episodes waiting to be watched. Every few hours (and when the app
 * starts, if due) it asks the provider for each favourite show's episode list. The episodes after
 * the furthest one the family watched, plus any that arrived since the last check, become the
 * show's counter and put it in a row on Home; arrivals also post a notification. Watching them
 * counts the show back down. The first look at a show never alerts.
 */
class NewEpisodes(private val app: FamilyTvApp) {
    private val dao get() = app.userDb.user()
    private val prefs get() = app.getSharedPreferences("new_episodes", Context.MODE_PRIVATE)

    private val running = Mutex()

    /** Runs [check] when the last one was long enough ago (the app start and the worker both ask). */
    suspend fun checkIfDue(profile: Profile) {
        if (!running.tryLock()) return
        try {
            val now = System.currentTimeMillis()
            // An updated app recounts at once, since its counting may have changed.
            val sameApp = prefs.getInt(LAST_VERSION, 0) == BuildConfig.VERSION_CODE
            if (sameApp && now - prefs.getLong(LAST_RUN, 0) < INTERVAL_MS) return
            prefs.edit().putLong(LAST_RUN, now).putInt(LAST_VERSION, BuildConfig.VERSION_CODE).apply()
            check(profile)
        } finally {
            running.unlock()
        }
    }

    suspend fun check(profile: Profile) {
        // This TV's favourite shows and each viewer's own.
        val favs = (dao.allFavouriteRows().filter { it.pending != Pending.REMOVE && it.pending != Pending.HIDDEN } + app.viewers.everyonesFavourites())
            .filter { it.kind == Kind.SERIES }
            .distinctBy { it.sourceId to it.itemId }
            // Shows favourited by hand before ones followed from Trakt history.
            .sortedWith(compareBy({ it.fromHistory }, { -it.addedAt }))
        val known = dao.episodeWatches().associateBy { it.sourceId to it.seriesId }
        // No longer a favourite: forget it, so favouriting it again doesn't alert on old episodes.
        val keep = favs.mapTo(HashSet()) { it.sourceId to it.itemId }
        known.keys.filter { it !in keep }.forEach { (s, id) -> dao.deleteEpisodeWatch(s, id) }
        val fresh = ArrayList<Pair<String, Int>>()
        val report = ArrayList<String>()
        for ((i, f) in favs.take(MAX_SHOWS).withIndex()) {
            val src = profile.sources.firstOrNull { it.id == f.sourceId && it.type == "xtream" } ?: continue
            if (i > 0) delay(PAUSE_MS) // one show at a time, gently, so the provider never sees a burst
            val episodes = runCatching { app.catalog.seriesInfo(f.sourceId, f.itemId, src).episodes }.getOrNull() ?: continue
            val latest = EpisodeGuide.latest(episodes) ?: continue
            val now = System.currentTimeMillis()
            val old = known[f.sourceId to f.itemId]
            val since = old?.let { it.latestSeason to it.latestEpisode }
            val arrived = since?.let { EpisodeGuide.after(episodes, it) }.orEmpty()
            // Watched on any copy of the show (another language or quality) counts.
            val watched = app.db.copyIds(f.sourceId, f.itemId)
                .flatMap { (s, id) -> dao.watchedEpisodes(s, id) }.mapTo(HashSet()) { it.season to it.episode }
            val waiting = EpisodeGuide.waiting(episodes, old?.let { NewEpisodeList.decode(it.newEpisodes) }.orEmpty(), since, watched)
            val furthest = watched.filter { it.first > 0 }.maxWithOrNull(compareBy({ it.first }, { it.second }))
            report += "${f.name.take(36)}: has S${latest.first}E${latest.second}, watched " +
                (furthest?.let { "S${it.first}E${it.second}" } ?: "none") + ", ${waiting.size} waiting"
            dao.upsertEpisodeWatch(
                EpisodeWatchEntity(
                    f.sourceId, f.itemId, f.name, latest.first, latest.second, now,
                    newCount = waiting.size,
                    newSince = when {
                        waiting.isEmpty() -> 0
                        arrived.isNotEmpty() -> now
                        else -> old?.newSince?.takeIf { it > 0 } ?: now
                    },
                    newEpisodes = NewEpisodeList.encode(waiting),
                ),
            )
            if (arrived.isNotEmpty()) fresh += SeriesTitles.parse(f.name).title to arrived.size
        }
        // What the counter found, for the admin page (the TVs have no other way to show it).
        PlaybackStatus.followed = mapOf("at" to System.currentTimeMillis(), "shows" to report.take(REPORT_SHOWS))
        if (fresh.isNotEmpty()) notify(fresh.distinctBy { it.first })
    }

    /** Opening a show's page clears a count from before episodes were tracked one by one. */
    suspend fun seen(sourceId: String, seriesId: String) = dao.clearOldNewCount(sourceId, seriesId)

    private fun notify(shows: List<Pair<String, Int>>) {
        if (!allowed(app)) return
        val nm = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "New episodes", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a favourite show has a new episode"
            })
        }
        val open = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val lines = shows.map { (title, n) -> if (n == 1) "$title: a new episode" else "$title: $n new episodes" }
        val title = if (shows.size == 1) "New on ${shows[0].first}" else "${shows.size} favourite shows have new episodes"
        val note = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(lines.joinToString(", "))
            .setStyle(NotificationCompat.InboxStyle().also { s -> lines.forEach(s::addLine) })
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, note) }
    }

    companion object {
        private const val CHANNEL = "new_episodes"
        private const val NOTIFICATION_ID = 2001
        private const val LAST_RUN = "lastRunWaiting"
        private const val LAST_VERSION = "lastRunVersion"
        private const val ASKED = "asked"
        private const val INTERVAL_MS = 6 * 3_600_000L
        private const val PAUSE_MS = 1_500L
        private const val MAX_SHOWS = 60
        // Kept short: the portal stores 8,000 characters of the whole status.
        private const val REPORT_SHOWS = 15

        private fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        /** Android 13+ asks once for permission to notify, the first time there is a favourite show. */
        fun askOnce(activity: Activity) {
            if (allowed(activity)) return
            val prefs = activity.getSharedPreferences("new_episodes", Context.MODE_PRIVATE)
            if (prefs.getBoolean(ASKED, false)) return
            prefs.edit().putBoolean(ASKED, true).apply()
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }
}
