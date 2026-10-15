package app.familytv.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.familytv.FamilyTvApp
import app.familytv.R
import app.familytv.data.Kind
import app.familytv.data.SeriesEntity
import app.familytv.iptv.EpisodeGuide
import app.familytv.iptv.SeriesTitles
import app.familytv.iptv.XtreamParser
import app.familytv.user.Pending
import app.familytv.user.ProgressKey
import app.familytv.ui.groupSeries
import app.familytv.ui.key
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Spots new episodes of favourite shows (Xtream lines only: M3U has no episode lists). Every few
 * hours it asks the provider for each show's episodes and compares the newest with the newest
 * seen last time; a show seen for the first time only sets that baseline. What it finds stays in
 * [fresh] (the Home "New episodes" row) until the show's page is opened, and also goes out as a
 * system notification, which phones, tablets and Fire TV show but Google TV may not.
 */
class NewEpisodes(private val app: FamilyTvApp) {
    private val prefs = app.getSharedPreferences("new_episodes", Context.MODE_PRIVATE)

    private val _fresh = MutableStateFlow(load())
    /** "sourceId/seriesId" of a favourite copy -> what is new ("S3 E12 · Title"). */
    val fresh: StateFlow<Map<String, String>> = _fresh

    suspend fun check() {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(CHECKED_AT, 0) < EVERY_MS) return
        val sources = app.portal.cachedConfig?.profile?.sources.orEmpty().filter { it.type == "xtream" }.associateBy { it.id }
        if (sources.isEmpty()) return
        prefs.edit().putLong(CHECKED_AT, now).apply()
        val dao = app.db.catalog()
        val rows = app.userDb.user().allFavouriteRows()
            .filter { it.kind == Kind.SERIES && it.pending != Pending.REMOVE }
            .mapNotNull { dao.seriesItem(it.sourceId, it.itemId) }
        val found = ArrayList<Pair<SeriesEntity, String>>()
        // One check per show however many language copies are favourites.
        for (g in groupSeries(rows).take(MAX_SHOWS)) {
            val v = g.copies.firstOrNull { it.sourceId in sources } ?: continue
            val episodes = runCatching { app.catalog.seriesInfo(v.sourceId, v.seriesId, sources.getValue(v.sourceId)) }.getOrNull()?.episodes.orEmpty()
            if (episodes.isEmpty()) continue
            val seenKey = "$SEEN${v.key}"
            val seen = if (prefs.contains(seenKey)) prefs.getInt(seenKey, 0) else null
            val watched = app.userDb.user()
                .progressFor(episodes.map { ProgressKey.episode(v.sourceId, v.seriesId, it.season, it.episode) })
                .filter { it.watched }.mapTo(HashSet()) { it.season to it.episode }
            // Already watched elsewhere (Trakt) isn't news.
            val new = NewEpisodeRules.since(seen, episodes).filter { (it.season to it.episode) !in watched }
            val edit = prefs.edit().putInt(seenKey, maxOf(seen ?: 0, episodes.maxOf(NewEpisodeRules::code)))
            if (new.isNotEmpty()) {
                val label = NewEpisodeRules.label(new)
                edit.putString("$NEW${v.key}", label)
                found += v to label
            }
            edit.apply()
        }
        if (found.isEmpty()) return
        _fresh.value = load()
        notify(found)
    }

    /** The show's page was opened: it isn't new any more. */
    fun seen(copies: List<SeriesEntity>) {
        val keys = copies.map { it.key }.filter { "$NEW$it" in prefs.all }
        if (keys.isEmpty()) return
        prefs.edit().apply { keys.forEach { remove("$NEW$it") } }.apply()
        keys.forEach { NotificationManagerCompat.from(app).cancel(it.hashCode()) }
        _fresh.value = load()
    }

    private fun load(): Map<String, String> =
        prefs.all.mapNotNull { (k, v) -> if (k.startsWith(NEW) && v is String) k.removePrefix(NEW) to v else null }.toMap()

    @SuppressLint("MissingPermission") // checked just below
    private fun notify(found: List<Pair<SeriesEntity, String>>) {
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = NotificationManagerCompat.from(app)
        nm.createNotificationChannel(NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName("New episodes").build())
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        val open = PendingIntent.getActivity(app, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        for ((v, label) in found) {
            val n = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("New on ${SeriesTitles.parse(v.name).title}")
                .setContentText(label)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(v.key.hashCode(), n) }
        }
    }

    companion object {
        private const val CHECKED_AT = "checkedAt"
        private const val SEEN = "seen/"
        private const val NEW = "new/"
        private const val CHANNEL = "new_episodes"
        private const val EVERY_MS = 3 * 3_600_000L
        // ponytail: caps provider calls per check; page through favourites if families keep more.
        private const val MAX_SHOWS = 40
    }
}

/** Which episodes are new, and how to say so. Pure, unit tested. */
object NewEpisodeRules {
    /** Orders episodes by season, then number. */
    fun code(e: XtreamParser.Episode) = e.season * 10_000 + e.episode

    /** Episodes past the newest seen before, oldest first; none the first time (that only sets the baseline). */
    fun since(seen: Int?, episodes: List<XtreamParser.Episode>): List<XtreamParser.Episode> =
        if (seen == null) emptyList() else episodes.filter { code(it) > seen }.sortedBy(::code)

    fun label(new: List<XtreamParser.Episode>): String {
        val first = new.first()
        if (new.size == 1) return "S${first.season} E${first.episode}  ·  ${EpisodeGuide.title(first)}"
        return "${new.size} new episodes from S${first.season} E${first.episode}"
    }
}
