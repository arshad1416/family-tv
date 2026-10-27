package app.familytv.ui

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import app.familytv.BuildConfig
import app.familytv.iptv.CatalogWork

/**
 * One kind of slide change's verdict from its recent timed samples: [SLOW][Verdict.SLOW] or
 * [OK][Verdict.OK] once [agree] of the last [window] say so. A sample that missed more than
 * [busyShare] of its frames next to clean ones is the box busy with something else (a fade with
 * 40 of 42 frames late beside one with none), so it is dropped, unless [busyRun] in a row do it.
 */
internal class FadeGate(
    private val window: Int = 6,
    private val agree: Int = 4,
    private val lateShare: Double = 0.1,
    private val busyShare: Double = 0.9,
    private val busyRun: Int = 3,
) {
    enum class Verdict { UNKNOWN, OK, SLOW }

    var verdict = Verdict.UNKNOWN
        private set
    private val recent = ArrayDeque<Boolean>()
    private var busy = 0

    /** Adds a fade's [frames] and how many were [late]; says what it made of it, for the log. */
    fun add(frames: Int, late: Int): String {
        if (frames < 5) return "too few frames, dropped"
        val share = late.toDouble() / frames
        if (share > busyShare) {
            busy++
            if (busy < busyRun) return "box busy, dropped"
        } else {
            busy = 0
        }
        recent.addLast(share > lateShare)
        if (recent.size > window) recent.removeFirst()
        val slow = recent.count { it }
        val ok = recent.size - slow
        verdict = when {
            slow >= agree -> Verdict.SLOW
            ok >= agree -> Verdict.OK
            else -> verdict
        }
        return "$slow slow, $ok smooth of the last ${recent.size}"
    }
}

/**
 * How this box changes the carousel's slides, decided on the box, not from a list of models:
 *  - a low-RAM device cuts;
 *  - otherwise fades are timed on a settled box only: [SETTLE_MS] after the app starts, comes back,
 *    leaves the screensaver or returns to Home, and [QUIET_MS] after any list, guide or carousel
 *    load ([CatalogWork]); a sample a load overlapped is dropped. A [FadeGate] decides: plain
 *    fades that are SLOW (the onn, 5-40% of frames late) make it cut; OK ones bring the flourish
 *    ([effects], [CarouselFlourish]), whose own gate turns it off when SLOW;
 *  - verdicts last for this run of the app; each is kept only as a hint for the next run's first
 *    choice, per app version (test builds included). A hint to cut or to drop the flourish is
 *    tried again after [RETRY_MS].
 * Debug and test builds reset it on `adb shell am broadcast -a app.familytv.RESET_SLIDE_FADE`.
 */
internal object SlideFade {
    private const val SETTLE_MS = 30_000L
    private const val QUIET_MS = 5_000L
    private const val RETRY_MS = 24 * 3_600_000L
    private const val PLAIN = "slideHint"
    private const val FX = "slideFxHint"
    private const val RESET = "app.familytv.RESET_SLIDE_FADE"

    private var loaded = false
    private var lowRam = false
    private var plainHint: String? = null
    private var fxHint: String? = null
    private var plainRetry = false
    private var fxRetry = false
    private var plain = FadeGate()
    private var fx = FadeGate()
    @Volatile private var settledFrom = 0L

    /** True while fades on this box get the flourish. */
    val effects = mutableStateOf(false)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("featured", Context.MODE_PRIVATE)

    /** Hints are kept per version name, so every test build (same version code) starts afresh. */
    private fun key(name: String) = "$name/${BuildConfig.VERSION_NAME}"

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val app = ctx.applicationContext
        lowRam = (app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
        val start = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Process.getStartElapsedRealtime() else SystemClock.elapsedRealtime()
        settledFrom = maxOf(settledFrom, start)
        read(app)
        if (BuildConfig.DEBUG || BuildConfig.VERSION_NAME.contains("-test")) {
            val reset = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    prefs(c).edit().remove(key(PLAIN)).remove(key(FX)).remove(key(PLAIN) + "/at").remove(key(FX) + "/at").apply()
                    plain = FadeGate(); fx = FadeGate()
                    read(c)
                    Log.i("SlideFade", "reset")
                }
            }
            ContextCompat.registerReceiver(app, reset, IntentFilter(RESET), ContextCompat.RECEIVER_EXPORTED)
        }
    }

    private fun read(ctx: Context) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        plainHint = p.getString(key(PLAIN), null)
        fxHint = p.getString(key(FX), null)
        plainRetry = plainHint == CUT && now - p.getLong(key(PLAIN) + "/at", 0) > RETRY_MS
        fxRetry = fxHint == OFF && now - p.getLong(key(FX) + "/at", 0) > RETRY_MS
        update()
    }

    private fun keep(ctx: Context, name: String, value: String) =
        prefs(ctx).edit().putString(key(name), value).putLong(key(name) + "/at", System.currentTimeMillis()).apply()

    private fun settled() = SystemClock.elapsedRealtime() - settledFrom >= SETTLE_MS && CatalogWork.quietFor() >= QUIET_MS

    private fun cutting(): Boolean = when {
        lowRam -> true
        plain.verdict == FadeGate.Verdict.SLOW -> true
        plain.verdict == FadeGate.Verdict.OK -> false
        // Told to cut last time: cut, unless it is time to try fading again and the box has settled.
        plainHint == CUT -> !(plainRetry && settled())
        else -> false
    }

    private fun update() {
        val plainGood = plain.verdict == FadeGate.Verdict.OK || (plain.verdict == FadeGate.Verdict.UNKNOWN && plainHint == FADE)
        val fxAllowed = fx.verdict != FadeGate.Verdict.SLOW && (fxHint != OFF || fxRetry)
        effects.value = !cutting() && plainGood && fxAllowed
    }

    /** The app came back, left the screensaver or returned to Home: the box needs a moment again. */
    fun unsettle() {
        settledFrom = SystemClock.elapsedRealtime()
    }

    fun cuts(ctx: Context): Boolean {
        load(ctx)
        return cutting()
    }

    /**
     * Runs [fade] ([withFx]: with the flourish over it), timing its frames ([timeFrames]) on a
     * settled box while that kind of fade has no verdict yet.
     */
    suspend fun watch(ctx: Context, withFx: Boolean, fade: suspend () -> Unit) {
        load(ctx)
        val gate = if (withFx) fx else plain
        if (gate.verdict != FadeGate.Verdict.UNKNOWN || !settled()) return fade()
        val loads = CatalogWork.started
        val (frames, late) = timeFrames(ctx, fade)
        val what = if (withFx) "fade with flourish" else "fade"
        if (CatalogWork.started != loads || CatalogWork.quietFor() < 0 || !settled()) {
            Log.i("SlideFade", "$what not counted: a load or wake came during it")
            return
        }
        val note = gate.add(frames, late)
        val verdict = when (gate.verdict) {
            FadeGate.Verdict.UNKNOWN -> "timing"
            FadeGate.Verdict.SLOW -> if (withFx) "flourish off, fading".also { keep(ctx, FX, OFF) } else "cutting this run".also { keep(ctx, PLAIN, CUT) }
            FadeGate.Verdict.OK -> if (withFx) "flourish kept".also { keep(ctx, FX, ON) } else "fading".also { keep(ctx, PLAIN, FADE) }
        }
        update()
        Log.i("SlideFade", "$what: $late of $frames frames late ($note), $verdict")
    }

    private const val CUT = "cut"
    private const val FADE = "fade"
    private const val ON = "on"
    private const val OFF = "off"
}
