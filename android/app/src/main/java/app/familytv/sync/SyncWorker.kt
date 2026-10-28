package app.familytv.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.familytv.app
import app.familytv.update.Updater
import app.familytv.usage.Usage

/** Periodic background check-in: heartbeat, run queued commands, share watched marks, refresh lists twice a day, look for new episodes. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        return try {
            val config = app.portal.config()
            if (!config.paired) return Result.success()
            val hb = app.portal.heartbeat(PlaybackStatus.snapshot())
            app.commands.runAll(config.commands, config.profile)
            runCatching { app.sharedProgress.sync() }
            runCatching { Updater.check(app, hb.latestApp ?: config.latestApp, visible = app.visible) }
                .onSuccess { PlaybackStatus.update = it }
            val stale = System.currentTimeMillis() - (app.db.catalog().sources().minOfOrNull { it.lastRefresh } ?: 0) > 12 * 3600_000
            if (stale) config.profile?.let { app.commands.refreshCatalog(it) }
            config.profile?.let { runCatching { app.newEpisodes.checkIfDue(it) } }
            runCatching { CategoryReport.sendIfChanged(app) }
            Usage.send(app.portal)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
