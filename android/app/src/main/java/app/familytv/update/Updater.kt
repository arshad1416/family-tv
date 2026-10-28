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
import java.io.File
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
    @Volatile private var cleared = false

    fun available(latest: LatestApp?) = latest != null && latest.versionCode > BuildConfig.VERSION_CODE

    /** [visible] is true when one of our screens is showing. Returns what happened, for the portal: null when there is nothing newer. */
    suspend fun check(context: Context, latest: LatestApp?, visible: Boolean, force: Boolean = false): String? = lock.withLock {
        // Once per run: an offer this app left unconfirmed belongs to an older run, perhaps for a
        // build that's now installed or older, and Android could show it again later.
        if (!cleared) { cleared = true; abandonLeftovers(context) }
        if (!available(latest)) return null
        latest!!
        if (PlaybackStatus.title != null && !force) return "waiting: playing"
        val silent = canInstallSilently(context)
        when {
            !visible && !silent -> return "waiting: needs someone at the TV"
            visible && !force && promptedFor == latest.versionCode -> return "offered ${latest.versionName ?: latest.versionCode}: waiting for Install"
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

    /** Abandons this app's install sessions, so only the offer being made now can be confirmed. */
    internal fun abandonLeftovers(context: Context) {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
    }

    /** The build in the APK at [apk], or null when it isn't one. */
    @Suppress("DEPRECATION")
    internal fun versionCode(context: Context, apk: File): Long? =
        context.packageManager.getPackageArchiveInfo(apk.path, 0)?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }

    private suspend fun install(context: Context, latest: LatestApp, silent: Boolean) = withContext(Dispatchers.IO) {
        abandonLeftovers(context)
        // Downloaded first, so a build that turns out no newer than this one is never offered.
        val apk = File(context.cacheDir, "update.apk")
        try {
            Http.client.newCall(Request.Builder().url(latest.url).build()).execute().use { res ->
                if (!res.isSuccessful) error("download failed: HTTP ${res.code}")
                apk.outputStream().use { res.body!!.byteStream().copyTo(it) }
            }
            val code = versionCode(context, apk) ?: error("download is not an app")
            if (code <= BuildConfig.VERSION_CODE) error("download is build $code, not newer than ${BuildConfig.VERSION_CODE}")
            offer(context, apk, silent)
        } finally {
            apk.delete()
        }
    }

    private suspend fun offer(context: Context, apk: File, silent: Boolean) {
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
                session.openWrite("family-tv.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
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
