package app.familytv.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.net.Uri
import android.provider.Settings
import app.familytv.BuildConfig
import app.familytv.app
import app.familytv.core.Http
import app.familytv.portal.LatestApp
import app.familytv.sync.PlaybackStatus
import app.familytv.usage.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Installs new builds published by CI.
 *
 * Android only skips the "Install" prompt (API 31+) when this app installed itself last time, so:
 * - app closed + silent install possible: update in the background;
 * - app open on the home screen: open the installer so the viewer presses Install once;
 * - something playing: wait.
 * Fire TV (Android 11) always needs the one press.
 */
object Updater {
    private val lock = Mutex()
    @Volatile private var promptedFor = 0

    fun available(latest: LatestApp?) = latest != null && latest.versionCode > BuildConfig.VERSION_CODE

    /** [visible] is true when one of our screens is showing. Returns what happened, for the portal. */
    suspend fun check(context: Context, latest: LatestApp?, visible: Boolean, force: Boolean = false): String? = lock.withLock {
        if (!available(latest)) return null
        latest!!
        if (PlaybackStatus.title != null && !force) return "waiting: playing"
        val silent = canInstallSilently(context)
        when {
            !visible && !silent -> return "waiting: needs someone at the TV"
            visible && !force && promptedFor == latest.versionCode -> return null
        }
        if (!context.packageManager.canRequestPackageInstallsCompat()) {
            if (!visible) return "waiting: install permission"
            promptedFor = latest.versionCode
            // Some TV builds lack the per-app screen; fall back to the general security settings.
            val perApp = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(perApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .recoverCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return "asked for install permission"
        }
        promptedFor = latest.versionCode
        install(context, latest, silent && !visible)
        return "installing ${latest.versionName ?: latest.versionCode}"
    }

    private fun canInstallSilently(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val installer = runCatching { context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName }.getOrNull()
        return installer == context.packageName
    }

    private fun android.content.pm.PackageManager.canRequestPackageInstallsCompat() =
        Build.VERSION.SDK_INT < 26 || canRequestPackageInstalls()

    private suspend fun install(context: Context, latest: LatestApp, silent: Boolean) = withContext(Dispatchers.IO) {
        // The install replaces this app, so send what's queued first.
        Usage.log(Usage.Screen.UPDATE, Usage.Action.UPDATE_INSTALL, if (silent) 1 else 0)
        runCatching { Usage.send(context.app.portal) }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(
                    if (silent) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    else PackageInstaller.SessionParams.USER_ACTION_REQUIRED,
                )
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            try {
                Http.client.newCall(Request.Builder().url(latest.url).build()).execute().use { res ->
                    if (!res.isSuccessful) error("download failed: HTTP ${res.code}")
                    val body = res.body!!
                    session.openWrite("family-tv.apk", 0, body.contentLength()).use { out ->
                        body.byteStream().copyTo(out)
                        session.fsync(out)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val callback = PendingIntent.getBroadcast(context, id, Intent(context, InstallReceiver::class.java), flags)
                session.commit(callback.intentSender)
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
        }
    }
}

/** Receives the installer's verdict; opens the confirmation screen when Android asks for one. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> PlaybackStatus.error("update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
