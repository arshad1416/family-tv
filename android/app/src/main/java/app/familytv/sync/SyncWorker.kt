package app.familytv.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.familytv.app
import app.familytv.update.Updater

/** Periodic background check-in: heartbeat, run queued commands, refresh lists twice a day, look for new episodes. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        return try {
            val config = app.portal.config()
            if (!config.paired) return Result.success()
            val hb = app.portal.heartbeat(PlaybackStatus.snapshot())
            app.commands.runAll(config.commands, config.profile)
            runCatching { Updater.check(app, hb.latestApp ?: config.latestApp, visible = app.visible) }
                .onSuccess { it?.let { s -> PlaybackStatus.update = s } }
            val stale = System.currentTimeMillis() - (app.db.catalog().sources().minOfOrNull { it.lastRefresh } ?: 0) > 12 * 3600_000
            if (stale) config.profile?.let { app.commands.refreshCatalog(it) }
            config.profile?.let { runCatching { app.newEpisodes.checkIfDue(it) } }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
