package app.familytv.ui

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.familytv.R
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.controllers.RiveFileController
import app.rive.runtime.kotlin.core.Fit
import app.rive.runtime.kotlin.core.RiveEvent
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
        view.postDelayed(coverLate, if (style == CHANNEL) CHANNEL_COVERED_BY_MS else PAGE_COVERED_BY_MS)
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
        /** Style 0: the soft dark-glass wipe, for a new page on the dark look. */
        const val PAGE = 0
        /** Style 1: the dim, scan-line flash and bloom, for a new channel. */
        const val CHANNEL = 1
        /** Style 2: the light-glass wipe, for a new page on the light look; timed as [PAGE]. */
        const val PAGE_LIGHT = 2

        /** Null where Rive can't run; the caller swaps straight away then. */
        fun create(ctx: Context): RiveTransition? = riveView(ctx, R.raw.family_tv_transition, TRANSITION, Fit.FILL)?.let(::RiveTransition)
    }
}

/**
 * The Home carousel's slide-change flourish (family_tv_carousel.riv, state machine "Carousel":
 * number "style", trigger "go", event "done"): light drawn over the art while the backdrop fades
 * underneath; the art itself stays in the app. Hidden and paused when idle; a [play] while one runs
 * is refused. If "done" doesn't arrive in time, it hides anyway.
 */
internal class RiveCarouselFx private constructor(val view: RiveAnimationView) {
    private var playing = false
    private val doneLate = Runnable { done() }

    init {
        view.visibility = View.INVISIBLE
        view.addEventListener(object : RiveFileController.RiveEventListener {
            override fun notifyEvent(event: RiveEvent) {
                if (event.name == "done") view.post(::done)
            }
        })
    }

    /** Plays [style] ([SWEEP], [GLOW] or [STREAKS]); false, with nothing played, unless idle. */
    fun play(style: Int): Boolean {
        if (playing) return false
        val started = runCatching {
            view.visibility = View.VISIBLE
            view.play(CAROUSEL, isStateMachine = true)
            view.setNumberState(CAROUSEL, "style", style.toFloat())
            view.fireState(CAROUSEL, "go")
        }.isSuccess
        if (!started) {
            view.visibility = View.INVISIBLE
            return false
        }
        playing = true
        view.postDelayed(doneLate, CAROUSEL_DONE_BY_MS)
        return true
    }

    private fun done() {
        if (!playing) return
        playing = false
        view.removeCallbacks(doneLate)
        view.visibility = View.INVISIBLE
        runCatching { view.pause() }
    }

    companion object {
        /** A soft diagonal band of light crossing left to right (films). */
        const val SWEEP = 0
        /** A bloom and ring opening over the art's upper right (shows). */
        const val GLOW = 1
        /** Thin streaks gliding right to left at different speeds (events). */
        const val STREAKS = 2

        /** Null where Rive can't run; the slides then just fade. */
        fun create(ctx: Context): RiveCarouselFx? = riveView(ctx, R.raw.family_tv_carousel, CAROUSEL, Fit.FILL)?.let(::RiveCarouselFx)
    }
}

/**
 * A tab's Rive icon (family_tv_nav_icons.riv, artboard [artboard], state machine "Icon"): it plays
 * its own move once on focus, a squash on each new [presses], and follows [selected] and [light]
 * for its ink. Still when idle: paused once its move is over.
 */
@Composable
internal fun RiveTabIcon(artboard: String, focused: Boolean, selected: Boolean, light: Boolean, presses: Int, modifier: Modifier = Modifier) {
    var view by remember { mutableStateOf<RiveAnimationView?>(null) }
    val fired = remember { intArrayOf(presses) }
    AndroidView(
        factory = { c ->
            RiveAnimationView(c).apply {
                isFocusable = false
                isClickable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                runCatching { setRiveResource(R.raw.family_tv_nav_icons, artboardName = artboard, stateMachineName = ICON, fit = Fit.CONTAIN) }
                view = this
            }
        },
        modifier = modifier,
    )
    LaunchedEffect(view, focused, selected, light, presses) {
        val v = view ?: return@LaunchedEffect
        runCatching {
            v.play(ICON, isStateMachine = true)
            v.setBooleanState(ICON, "light", light)
            v.setBooleanState(ICON, "selected", selected)
            v.setBooleanState(ICON, "focused", focused)
            if (presses != fired[0]) { fired[0] = presses; v.fireState(ICON, "press") }
        }
        delay(ICON_SETTLE_MS)
        runCatching { v.pause() }
    }
}

/**
 * Where OK lands on the TV home's glass cards, for one shared Rive press ripple ([PressRipple])
 * laid over the pressed card. Cards under [minWidth] by [minHeight] px (pills, tabs, icon
 * buttons) and cards in another window (dialogs) get none.
 */
internal class PressHost(private val minWidth: Float, private val minHeight: Float) {
    var spot by mutableStateOf<PressSpot?>(null)
        private set
    var coords: LayoutCoordinates? = null
    private var count = 0

    fun press(card: LayoutCoordinates, shape: Shape) {
        val host = coords?.takeIf { it.isAttached && card.isAttached } ?: return
        if (card.findRootCoordinates() !== host.findRootCoordinates()) return
        val r = card.boundsInWindow()
        if (r.width < minWidth || r.height < minHeight) return
        val at = host.windowToLocal(r.topLeft)
        spot = PressSpot(IntOffset(at.x.roundToInt(), at.y.roundToInt()), IntSize(r.width.roundToInt(), r.height.roundToInt()), shape, ++count)
    }
}

/** A pressed card's place in the [PressHost]'s box, its shape, and which press this is. */
internal data class PressSpot(val offset: IntOffset, val size: IntSize, val shape: Shape, val n: Int)

/** The TV home's [PressHost]; null elsewhere and when the system asks for less motion, so cards there get no ripple. */
internal val LocalPress = staticCompositionLocalOf<PressHost?> { null }

/**
 * The press ripple (family_tv_press.riv, state machine "Press": trigger "press", number "radius")
 * over [host]'s latest pressed card, clipped to its shape. Hidden and still between presses; the
 * press never waits for it.
 */
@Composable
internal fun PressRipple(host: PressHost, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val view = remember { riveView(ctx, R.raw.family_tv_press, PRESS, Fit.FILL)?.apply { visibility = View.INVISIBLE } } ?: return
    val hide = remember { Runnable { view.visibility = View.INVISIBLE; runCatching { view.pause() } } }
    val density = LocalDensity.current
    val spot = host.spot
    Box(modifier.onPlaced { host.coords = it }) {
        AndroidView(
            { view },
            if (spot == null) Modifier.size(0.dp)
            else with(density) { Modifier.offset { spot.offset }.size(spot.size.width.toDp(), spot.size.height.toDp()).clip(spot.shape) },
        )
    }
    LaunchedEffect(spot) {
        val s = spot ?: return@LaunchedEffect
        // Once the view has its new size.
        withFrameNanos { }
        val corner = (s.shape as? CornerBasedShape)?.topStart?.toPx(Size(s.size.width.toFloat(), s.size.height.toFloat()), density) ?: 0f
        view.removeCallbacks(hide)
        view.visibility = View.VISIBLE
        runCatching {
            view.play(PRESS, isStateMachine = true)
            view.setNumberState(PRESS, "radius", (corner * 240f / s.size.height).coerceIn(0f, 100f))
            view.fireState(PRESS, "press")
        }
        view.postDelayed(hide, PRESS_MS)
    }
}

private const val ICON = "Icon"
private const val PRESS = "Press"
/** An icon's focus move takes about 0.7 s and its press 0.18 s. */
private const val ICON_SETTLE_MS = 900L
/** The ripple's 250 ms, with room to spare. */
private const val PRESS_MS = 350L
private const val LOADING = "Loading"
private const val TRANSITION = "Transition"
private const val CAROUSEL = "Carousel"
/** The flourish runs 800 ms; past this it hides anyway. */
private const val CAROUSEL_DONE_BY_MS = 1_100L
/** How long a load runs before the loader shows. */
private const val LOADER_WAIT_MS = 300L
/** The loader's 200 ms fade-out, with room to spare. */
private const val LOADER_FADE_MS = 300L
/** "covered" comes at about 265 ms (page) and 185 ms (channel); past these the swap happens anyway. */
private const val PAGE_COVERED_BY_MS = 400L
private const val CHANNEL_COVERED_BY_MS = 300L
/** "done" comes at about 505 ms at most; past this the overlay hides anyway. */
private const val DONE_BY_MS = 800L
