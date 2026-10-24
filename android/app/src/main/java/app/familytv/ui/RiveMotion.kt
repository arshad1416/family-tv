package app.familytv.ui

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.familytv.R
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.controllers.RiveFileController
import app.rive.runtime.kotlin.core.Fit
import app.rive.runtime.kotlin.core.RiveEvent

/** A Rive view that never takes focus, touch or a screen reader's attention, or null where Rive can't run or [raw] won't load. */
private fun riveView(ctx: Context, raw: Int, stateMachine: String, fit: Fit): RiveAnimationView? {
    if (!RiveArt.ready(ctx)) return null
    return runCatching {
        RiveAnimationView(ctx).apply {
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setRiveResource(raw, stateMachineName = stateMachine, fit = fit)
        }
    }.getOrNull()
}

/**
 * The "TV" pill loader (family_tv_loading.riv, state machine "Loading", bool "active"). It stays
 * mounted: [loading] shows it after [LOADER_WAIT_MS], so a quick load never flashes it, and fades it
 * out again; it holds still (paused) while hidden. It stays with reduced motion too, as it shows
 * that something is happening.
 */
internal class RiveLoader private constructor(val view: RiveAnimationView) {
    private var want = false
    private var shown = false
    private val show = Runnable { shown = true; runCatching { view.play(LOADING, isStateMachine = true); view.setBooleanState(LOADING, "active", true) } }
    private val rest = Runnable { runCatching { view.pause() } }

    init { view.postDelayed(rest, LOADER_FADE_MS) }

    fun loading(on: Boolean) {
        if (on == want) return
        want = on
        view.removeCallbacks(show)
        view.removeCallbacks(rest)
        if (on) view.postDelayed(show, LOADER_WAIT_MS)
        else if (shown) {
            shown = false
            runCatching { view.setBooleanState(LOADING, "active", false) }
            view.postDelayed(rest, LOADER_FADE_MS)
        }
    }

    companion object {
        /** Null where Rive can't run; the caller keeps its own spinner then. */
        fun create(ctx: Context): RiveLoader? = riveView(ctx, R.raw.family_tv_loading, LOADING, Fit.CONTAIN)?.let(::RiveLoader)
    }
}

/**
 * The page and channel transition (family_tv_transition.riv, state machine "Transition": number
 * "style", trigger "go", events "covered" and "done"). Hidden when idle, it never blocks a key:
 * a [go] while one runs is refused, never queued. If an event doesn't arrive in time, the swap and
 * the end happen anyway.
 */
internal class RiveTransition private constructor(val view: RiveAnimationView) {
    enum class Phase { IDLE, COVERING, REVEALING }

    var phase by mutableStateOf(Phase.IDLE)
        private set
    private var onCovered: (() -> Unit)? = null
    private val coverLate = Runnable { covered() }
    private val doneLate = Runnable { done() }

    init {
        view.visibility = View.INVISIBLE
        view.addEventListener(object : RiveFileController.RiveEventListener {
            override fun notifyEvent(event: RiveEvent) {
                when (event.name) {
                    "covered" -> view.post(::covered)
                    "done" -> view.post(::done)
                }
            }
        })
    }

    /** Starts [style] ([PAGE] or [CHANNEL]); [onCovered] runs once nothing underneath shows. False, with nothing run, unless idle. */
    fun go(style: Int, onCovered: () -> Unit = {}): Boolean {
        if (phase != Phase.IDLE) return false
        val started = runCatching {
            view.visibility = View.VISIBLE
            view.play(TRANSITION, isStateMachine = true)
            view.setNumberState(TRANSITION, "style", style.toFloat())
            view.fireState(TRANSITION, "go")
        }.isSuccess
        if (!started) {
            view.visibility = View.INVISIBLE
            return false
        }
        this.onCovered = onCovered
        phase = Phase.COVERING
        view.postDelayed(coverLate, if (style == PAGE) PAGE_COVERED_BY_MS else CHANNEL_COVERED_BY_MS)
        view.postDelayed(doneLate, DONE_BY_MS)
        return true
    }

    private fun covered() {
        if (phase != Phase.COVERING) return
        view.removeCallbacks(coverLate)
        phase = Phase.REVEALING
        onCovered?.invoke()
        onCovered = null
    }

    private fun done() {
        if (phase == Phase.IDLE) return
        covered()
        view.removeCallbacks(doneLate)
        phase = Phase.IDLE
        view.visibility = View.INVISIBLE
        runCatching { view.pause() }
    }

    companion object {
        /** Style 0: the soft glass wipe, for a new page. */
        const val PAGE = 0
        /** Style 1: the dim, scan-line flash and bloom, for a new channel. */
        const val CHANNEL = 1

        /** Null where Rive can't run; the caller swaps straight away then. */
        fun create(ctx: Context): RiveTransition? = riveView(ctx, R.raw.family_tv_transition, TRANSITION, Fit.FILL)?.let(::RiveTransition)
    }
}

private const val LOADING = "Loading"
private const val TRANSITION = "Transition"
/** How long a load runs before the loader shows. */
private const val LOADER_WAIT_MS = 300L
/** The loader's 200 ms fade-out, with room to spare. */
private const val LOADER_FADE_MS = 300L
/** "covered" comes at about 265 ms (page) and 185 ms (channel); past these the swap happens anyway. */
private const val PAGE_COVERED_BY_MS = 400L
private const val CHANNEL_COVERED_BY_MS = 300L
/** "done" comes at about 505 ms at most; past this the overlay hides anyway. */
private const val DONE_BY_MS = 800L
