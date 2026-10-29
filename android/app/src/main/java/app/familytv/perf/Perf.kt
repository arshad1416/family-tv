package app.familytv.perf

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
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
 * Speed and memory numbers sent as the heartbeat's `status.perf` (parity checklist, "Spec: Speed and
 * memory report"). Whole numbers, MB truncated; unknown fields are left out. Measured in place: no
 * extra network calls, and nothing about what's watched.
 */
object Perf {
    private const val MB = 1024L * 1024
    /** A launch counts as cold only when its screen opened this soon after the process started (not a background check-in's process). */
    private const val COLD_GAP_MS = 10_000L
    /**
     * Before Android 12, a browsing frame that took more than this many refreshes is a slow frame. Android draws a
     * frame while the one before is still on its way to the screen, so a frame on time often takes over one refresh
     * (every frame did on the Chromecasts and the Kitchen's Fire Stick, Oct 28); over two, one was missed. From
     * Android 12 the system gives each frame its own deadline, which allows for that queue ([missedDeadline]).
     */
    private const val SLOW_FRAME_REFRESHES = 2

    private var prefs: SharedPreferences? = null
    private var appCreatedAt = 0L
    private var deviceRamMb: Long? = null
    @Volatile private var activityAt = 0L
    @Volatile private var notCold = false
    @Volatile private var measured = false
    /** Process start to the first frame of the home screen with its rows filled, on the last cold start. */
    @Volatile var coldStartMs: Long? = null
        private set
    /** The last list refresh where one or more sources loaded: channels, movies and series saved (and searchable), not the extra guides. */
    @Volatile var listLoadMs: Long? = null
    @Volatile private var pssPeakKb = 0L
    /** The app's own pages are showing (not the startup splash, loading, pairing or full-screen video). */
    @Volatile var browsing = false
    /** Our screensaver shows: its slow fades over hours aren't browsing, and would fill the slow-frame window. */
    @Volatile var screensaver = false
    internal val frames = FrameWindow()
    private val frameHandler by lazy { Handler(HandlerThread("perf").apply { start() }.looper) }

    fun init(context: Context) {
        appCreatedAt = SystemClock.uptimeMillis()
        deviceRamMb = context.getSystemService(ActivityManager::class.java)?.let { am ->
            ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / MB
        }
        prefs = context.getSharedPreferences("perf", Context.MODE_PRIVATE).also { p ->
            coldStartMs = p.getLong("coldStartMs", -1).takeIf { it >= 0 }
        }
    }

    /** The main screen opened; with [firstScreen] this gives the cold start time. */
    fun activityCreated() {
        if (activityAt == 0L) activityAt = SystemClock.uptimeMillis()
    }

    /** Pairing, the first list download or a failure came first: this start isn't a fair cold start. */
    fun skipColdStart() { notCold = true }

    /** The home screen's first frame with rows on it. Kept, so later heartbeats (and processes) carry it. */
    fun firstScreen() {
        if (measured || notCold || activityAt == 0L) return
        measured = true
        val start = if (Build.VERSION.SDK_INT >= 24) Process.getStartUptimeMillis() else appCreatedAt
        if (activityAt - start >= COLD_GAP_MS) return
        val ms = SystemClock.uptimeMillis() - start
        coldStartMs = ms
        prefs?.edit()?.putLong("coldStartMs", ms)?.apply()
    }

    /** The lists loaded in [ms]; memory is sampled then too. */
    fun listLoaded(ms: Long) {
        listLoadMs = ms
        samplePssKb()
    }

    /**
     * Counts the window's slow frames while [browsing], not under the [screensaver] (Android 7+; older TVs leave
     * jankPct out). From Android 12 a slow frame is one that missed the deadline the system gave it, as gfxinfo's
     * "deadline missed" counts them; before that, one that took over [SLOW_FRAME_REFRESHES] refreshes.
     */
    fun watchFrames(activity: Activity) {
        if (Build.VERSION.SDK_INT < 24) return
        @Suppress("DEPRECATION")
        val hz = activity.windowManager.defaultDisplay?.refreshRate?.takeIf { it >= 20f } ?: 60f
        val slowNs = slowFrameNs(hz)
        runCatching {
            activity.window.addOnFrameMetricsAvailableListener(Window.OnFrameMetricsAvailableListener { _, m, _ ->
                if (!browsing || screensaver) return@OnFrameMetricsAvailableListener
                if (Build.VERSION.SDK_INT >= 26 && m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
                val total = m.getMetric(FrameMetrics.TOTAL_DURATION)
                val slow = if (Build.VERSION.SDK_INT >= 31) {
                    missedDeadline(total, m.getMetric(FrameMetrics.DEADLINE), m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP), slowNs)
                } else {
                    total > slowNs
                }
                frames.add(System.currentTimeMillis(), slow)
            }, frameHandler)
        }
    }

    /** How long a frame may take at [hz] before it counts as slow, before Android 12. */
    internal fun slowFrameNs(hz: Float): Long = (SLOW_FRAME_REFRESHES * 1e9 / hz).toLong()

    /**
     * Whether a frame that took [total] ns from its vsync missed its [deadline]: a time from the frame's vsync, or the
     * moment itself when it reads as one (after [intendedVsync]). A deadline that doesn't read sensibly falls back to
     * [fallbackNs].
     */
    internal fun missedDeadline(total: Long, deadline: Long, intendedVsync: Long, fallbackNs: Long): Boolean {
        val budget = if (intendedVsync in 1..<deadline) deadline - intendedVsync else deadline
        return total > if (budget in 1..1_000_000_000L) budget else fallbackNs
    }

    private fun samplePssKb(): Long = Debug.getPss().also { pssPeakKb = max(pssPeakKb, it) }

    fun snapshot(): Map<String, Any> {
        val pssKb = samplePssKb()
        val rt = Runtime.getRuntime()
        return listOf(
            "coldStartMs" to coldStartMs,
            "pssMb" to pssKb / 1024,
            "heapMb" to (rt.totalMemory() - rt.freeMemory()) / MB,
            "pssPeakMb" to pssPeakKb / 1024,
            "listLoadMs" to listLoadMs,
            "jankPct" to frames.pct(),
            "deviceRamMb" to deviceRamMb,
            "model" to Build.MODEL,
            "sdk" to Build.VERSION.SDK_INT,
        ).mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
    }
}

/** Slow-frame share over the last [minutes] minutes that had browsing frames, one bucket per minute. */
internal class FrameWindow(private val minutes: Int = 10) {
    private class Minute(val at: Long, var total: Int = 0, var slow: Int = 0)
    private val buckets = ArrayDeque<Minute>()

    @Synchronized fun add(nowMs: Long, isSlow: Boolean) {
        val m = nowMs / 60_000
        if (buckets.lastOrNull()?.at != m) {
            buckets.addLast(Minute(m))
            if (buckets.size > minutes) buckets.removeFirst()
        }
        buckets.last().apply { total++; if (isSlow) slow++ }
    }

    companion object { const val MIN_FRAMES = 60 }

    /** Percent, one decimal; null until there are [MIN_FRAMES] frames to judge. */
    @Synchronized fun pct(): Double? {
        val t = buckets.sumOf { it.total }
        return if (t < MIN_FRAMES) null else (buckets.sumOf { it.slow } * 1000.0 / t).roundToLong() / 10.0
    }
}
