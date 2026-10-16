package app.familytv.sync

import android.content.Context
import android.content.Intent
import app.familytv.app
import app.familytv.portal.Command
import app.familytv.portal.Profile
import app.familytv.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext

/** Executes actions queued from the admin portal and reports the result back. */
class CommandRunner(private val context: Context) {
    sealed interface UiEvent {
        data class Message(val text: String) : UiEvent
        data object Reload : UiEvent
    }

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    private val ledger = CommandLedger()

    suspend fun runAll(commands: List<Command>?, profile: Profile?) {
        for (c in commands.orEmpty()) ledger.run(c.id, action = {
            try { run(c, profile) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mapOf("error" to (e.message ?: e.javaClass.simpleName)) }
        }, acknowledge = { result -> context.app.portal.ack(c.id, result) })
    }

    private suspend fun run(c: Command, profile: Profile?): Any? = withContext(Dispatchers.IO) { runOnIo(c, profile) }

    private suspend fun runOnIo(c: Command, profile: Profile?): Any? {
        val app = context.app
        return when (c.type) {
            "refresh" -> {
                val fresh = app.portal.config()
                fresh.profile?.let { refreshCatalog(it) }
                _events.tryEmit(UiEvent.Reload)
                mapOf("channels" to app.db.catalog().channelCount())
            }
            "diagnose" -> {
                val p = profile ?: app.portal.config().profile ?: return mapOf("error" to "not paired")
                val r = app.diagnoser.runAndReport(p, playingHere = PlaybackStatus.title != null)
                mapOf("verdict" to r.verdict.name)
            }
            "clear_cache" -> {
                context.cacheDir.deleteRecursively()
                app.db.clearAllTables()
                app.portal.config().profile?.let { refreshCatalog(it) }
                _events.tryEmit(UiEvent.Reload)
                mapOf("ok" to true)
            }
            "message" -> {
                val text = c.payload?.get("text")?.toString()?.take(500) ?: return null
                _events.tryEmit(UiEvent.Message(text))
                mapOf("shown" to true)
            }
            "restart" -> {
                context.app.portal.ack(c.id, mapOf("restarting" to true))
                restart()
                null
            }
            "update" -> {
                val latest = app.portal.config().latestApp ?: return mapOf("error" to "no update published")
                if (!Updater.available(latest)) return mapOf("upToDate" to true)
                mapOf("result" to Updater.check(context, latest, visible = true, force = true))
            }
            else -> mapOf("error" to "unknown command ${c.type}")
        }
    }

    suspend fun refreshCatalog(profile: Profile, onlyIfStale: Boolean = false) {
        try {
            if (!context.app.catalog.refresh(profile, onlyIfStale)) return
            PlaybackStatus.lastRefresh = System.currentTimeMillis()
            PlaybackStatus.lastRefreshError = null
            runCatching { CategoryReport.sendIfChanged(context.app) }.onFailure { if (it is CancellationException) throw it }
            // New lists can carry titles that are on the Trakt watchlist or history: pull again.
            context.app.trakt.sync.soon()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PlaybackStatus.lastRefreshError = e.message
            throw e
        }
    }

    private fun restart() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}
