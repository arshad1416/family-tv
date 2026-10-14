package app.familytv.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import app.familytv.BuildConfig
import app.familytv.app
import app.familytv.core.Http
import app.familytv.portal.Command
import app.familytv.portal.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** Executes actions queued from the admin portal and reports the result back. */
class CommandRunner(private val context: Context) {
    sealed interface UiEvent {
        data class Message(val text: String) : UiEvent
        data object Reload : UiEvent
    }

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    private val seen = LinkedHashSet<String>()

    suspend fun runAll(commands: List<Command>?, profile: Profile?) {
        for (c in commands.orEmpty()) {
            if (!seen.add(c.id)) continue
            if (seen.size > 100) seen.remove(seen.first())
            val result: Any? = try {
                run(c, profile)
            } catch (e: Exception) {
                mapOf("error" to (e.message ?: e.javaClass.simpleName))
            }
            runCatching { context.app.portal.ack(c.id, result) }
        }
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
                if (latest.versionCode <= BuildConfig.VERSION_CODE) return mapOf("upToDate" to true)
                installUpdate(latest.url)
                mapOf("installerOpened" to latest.versionCode)
            }
            else -> mapOf("error" to "unknown command ${c.type}")
        }
    }

    suspend fun refreshCatalog(profile: Profile) {
        try {
            context.app.catalog.refresh(profile)
            PlaybackStatus.lastRefresh = System.currentTimeMillis()
            PlaybackStatus.lastRefreshError = null
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

    /** Downloads the APK and opens the system installer; the person at the TV confirms with one click. */
    private fun installUpdate(url: String) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "family-tv.apk")
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) error("download failed: HTTP ${res.code}")
            apk.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
        }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT < 24) setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive")
        }
        context.startActivity(intent)
    }
}
