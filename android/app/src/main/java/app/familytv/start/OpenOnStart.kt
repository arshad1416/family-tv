package app.familytv.start

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.familytv.R
import app.familytv.app
import app.familytv.sync.PlaybackStatus

/**
 * "Open when the TV turns on" (portal profile setting `openOnStart`, meant for the Airbnb guest TV):
 * a small foreground service brings the app to the front when the TV starts or wakes. Google TV
 * stays the home screen. Android only lets an app open itself from the background once it holds
 * Display over other apps, which each TV grants once (its settings page, or adb when it has none).
 */
object OpenOnStart {
    enum class State(val report: String) { OFF("off"), NEEDS_PERMISSION("needs-permission"), READY("ready") }

    /** The adb command that grants the permission on TVs without a settings page for it. */
    const val ADB_GRANT = "adb shell appops set app.familytv SYSTEM_ALERT_WINDOW allow"

    fun state(enabled: Boolean, canOverlay: Boolean): State = when {
        !enabled -> State.OFF
        !canOverlay -> State.NEEDS_PERMISSION
        else -> State.READY
    }

    fun enabled(ctx: Context): Boolean = ctx.app.portal.cachedConfig?.profile?.openOnStart == true

    fun current(ctx: Context): State = state(enabled(ctx), Settings.canDrawOverlays(ctx))

    /** Starts or stops the service to match the profile; runs after every config fetch and when a screen opens. */
    fun sync(ctx: Context, enabled: Boolean = enabled(ctx)) {
        val s = state(enabled, Settings.canDrawOverlays(ctx))
        PlaybackStatus.openOnStart = s.report.takeIf { s != State.OFF }
        val service = Intent(ctx, OpenOnStartService::class.java)
        // Android may refuse a background start; the next screen or boot tries again.
        if (s == State.READY) runCatching { ContextCompat.startForegroundService(ctx, service) } else ctx.stopService(service)
    }

    /** Opens the TV's Display over other apps page for this app; false when the TV has none. */
    fun askPermission(ctx: Context): Boolean = runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    /** The launcher's own intent, so a running app comes back where it was instead of opening twice. */
    internal fun bringToFront(ctx: Context) {
        val pm = ctx.packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(ctx.packageName) ?: pm.getLaunchIntentForPackage(ctx.packageName) ?: return
        runCatching { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

class OpenOnStartService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val open = Runnable {
        if (OpenOnStart.current(this) == OpenOnStart.State.READY) OpenOnStart.bringToFront(this) else stopSelf()
    }
    private val screenOn = object : BroadcastReceiver() {
        // Google TV puts its home screen up as it wakes; opening a moment later lands on top of it.
        override fun onReceive(c: Context, i: Intent) = openSoon(WAKE_DELAY_MS)
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Open when the TV turns on", NotificationManager.IMPORTANCE_MIN))
        }
        val note = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Family TV opens when this TV turns on")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTE_ID, note, type)
        // Screen-on can't be declared in the manifest; it only reaches a receiver registered by a running app.
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_OPEN_NOW, false) == true) openSoon(BOOT_DELAY_MS)
        return START_STICKY
    }

    private fun openSoon(delayMs: Long) {
        handler.removeCallbacks(open)
        handler.postDelayed(open, delayMs)
    }

    override fun onDestroy() {
        handler.removeCallbacks(open)
        unregisterReceiver(screenOn)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    internal companion object {
        const val EXTRA_OPEN_NOW = "openNow"
        const val CHANNEL = "open_on_start"
        const val NOTE_ID = 7301
        const val WAKE_DELAY_MS = 1_500L
        const val BOOT_DELAY_MS = 4_000L
    }
}

/** Starts the service when the TV boots (and opens the app), and again after a self-update replaced the app. */
class OpenOnStartReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (OpenOnStart.current(ctx) != OpenOnStart.State.READY) return
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED
        runCatching {
            ContextCompat.startForegroundService(ctx, Intent(ctx, OpenOnStartService::class.java).putExtra(OpenOnStartService.EXTRA_OPEN_NOW, boot))
        }
    }
}
