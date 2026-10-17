package app.familytv.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.familytv.app
import app.familytv.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Periodic background check-in: heartbeat, run queued commands, share watched marks, refresh lists twice a day, look for new episodes. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        return try {
            currentCoroutineContext().ensureActive()
            val config = app.portal.config()
            if (!config.paired) return Result.success()
            currentCoroutineContext().ensureActive()
            val hb = app.portal.heartbeat(PlaybackStatus.snapshot())
            currentCoroutineContext().ensureActive()
            app.commands.runAll(config.commands, config.profile)
            runCatching { app.sharedProgress.sync() }.onFailure { if (it is CancellationException) throw it }
            runCatching { Updater.check(app, hb.latestApp ?: config.latestApp, visible = app.visible) }
                .onSuccess { it?.let { s -> PlaybackStatus.update = s } }
                .onFailure { if (it is CancellationException) throw it }
            currentCoroutineContext().ensureActive()
            config.profile?.let { app.commands.refreshCatalog(it, onlyIfStale = true) }
            config.profile?.let { runCatching { app.newEpisodes.checkIfDue(it) }.onFailure { e -> if (e is CancellationException) throw e } }
            runCatching { CategoryReport.sendIfChanged(app) }.onFailure { if (it is CancellationException) throw it }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
