package app.familytv.perf

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Speed and memory numbers sent as the heartbeat's `status.perf`, to compare this app with the Rust
 * one on the same TVs (parity checklist, "Spec: Speed and memory report"). Numbers only, measured
 * in place: no extra network calls, and nothing about what's watched.
 */
object Perf {
    private const val MB = 1024L * 1024
    /** A launch counts as cold only when its screen opened this soon after the process started (not a background check-in's process). */
    private const val COLD_GAP_MS = 5_000L

    private var appCreatedAt = 0L
    private var deviceRamMb: Long? = null
    @Volatile private var activityAt = 0L
    @Volatile private var notCold = false
    /** Process start to the first usable screen (home ready, under the startup video), on the last cold start. */
    @Volatile var coldStartMs: Long? = null
        private set
    /** The last full refresh of the channel, movie and series lists, from the first download to saved (and searchable). */
    @Volatile var listLoadMs: Long? = null
    @Volatile private var pssPeakKb = 0
    internal val frames = FrameWindow()
    private val frameHandler by lazy { Handler(HandlerThread("perf").apply { start() }.looper) }

    fun init(context: Context) {
        appCreatedAt = SystemClock.uptimeMillis()
        deviceRamMb = context.getSystemService(ActivityManager::class.java)?.let { am ->
            ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / MB
        }
    }

    /** The main screen opened; with [firstScreen] this gives the cold start time. */
    fun activityCreated() {
        if (activityAt == 0L) activityAt = SystemClock.uptimeMillis()
    }

    /** Pairing, the first list download or a failure came first: this start isn't a fair cold start. */
    fun skipColdStart() { notCold = true }

    /** The home screen is ready to use (the startup video may still be over it). */
    fun firstScreen() {
        if (coldStartMs != null || notCold || activityAt == 0L) return
        val start = if (Build.VERSION.SDK_INT >= 24) Process.getStartUptimeMillis() else appCreatedAt
        if (activityAt - start < COLD_GAP_MS) coldStartMs = SystemClock.uptimeMillis() - start
    }

    /** Counts this window's slow frames (Android 7+; older TVs report jankPct null). */
    fun watchFrames(activity: Activity) {
        if (Build.VERSION.SDK_INT < 24) return
        val deadlineNs = (1_000_000_000 / refreshRate(activity)).toLong()
        runCatching {
            activity.window.addOnFrameMetricsAvailableListener(Window.OnFrameMetricsAvailableListener { _, m, _ ->
                if (Build.VERSION.SDK_INT >= 26 && m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
                val deadline = if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.DEADLINE) else deadlineNs
                frames.add(System.currentTimeMillis(), m.getMetric(FrameMetrics.TOTAL_DURATION) > deadline)
            }, frameHandler)
        }
    }

    @Suppress("DEPRECATION")
    private fun refreshRate(activity: Activity): Float =
        (if (Build.VERSION.SDK_INT >= 30) activity.display?.refreshRate else activity.windowManager.defaultDisplay.refreshRate)
            ?.takeIf { it >= 1f } ?: 60f

    fun snapshot(): Map<String, Any?> {
        val pssKb = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
        pssPeakKb = max(pssPeakKb, pssKb)
        val rt = Runtime.getRuntime()
        return mapOf(
            "coldStartMs" to coldStartMs,
            "pssMb" to pssKb / 1024,
            "heapMb" to (rt.totalMemory() - rt.freeMemory()) / MB,
            "pssPeakMb" to pssPeakKb / 1024,
            "listLoadMs" to listLoadMs,
            "jankPct" to frames.pct(System.currentTimeMillis()),
            "deviceRamMb" to deviceRamMb,
            "model" to Build.MODEL,
            "sdk" to Build.VERSION.SDK_INT,
        )
    }
}

/** Slow-frame share over the last [minutes] minutes, kept in one-minute buckets. */
internal class FrameWindow(private val minutes: Int = 10) {
    private val total = IntArray(minutes)
    private val slow = IntArray(minutes)
    private val stamp = LongArray(minutes) { -1 }

    @Synchronized fun add(nowMs: Long, isSlow: Boolean) {
        val m = nowMs / 60_000
        val i = (m % minutes).toInt()
        if (stamp[i] != m) { stamp[i] = m; total[i] = 0; slow[i] = 0 }
        total[i]++
        if (isSlow) slow[i]++
    }

    /** Percent, one decimal; null until there are [MIN_FRAMES] frames to judge. */
    @Synchronized fun pct(nowMs: Long): Double? {
        val m = nowMs / 60_000
        var t = 0
        var s = 0
        for (i in 0 until minutes) if (stamp[i] > m - minutes && stamp[i] <= m) { t += total[i]; s += slow[i] }
        return if (t < MIN_FRAMES) null else (s * 1000.0 / t).roundToLong() / 10.0
    }

    companion object { const val MIN_FRAMES = 60 }
}
