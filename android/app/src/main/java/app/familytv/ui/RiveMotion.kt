package app.familytv.ui

import android.content.Context
import android.util.Log
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.familytv.BuildConfig
import app.familytv.R
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.controllers.RiveFileController
import app.rive.runtime.kotlin.core.Fit
import app.rive.runtime.kotlin.core.RiveEvent
import kotlin.math.roundToInt

/** A Rive view that never takes focus, touch or a screen reader's attention, or null where Rive can't run or [raw] won't load. */
private fun riveView(ctx: Context, raw: Int, stateMachine: String, fit: Fit): RiveAnimationView? {
    if (!RiveArt.ready(ctx)) return null
    return runCatching {
        RiveAnimationView(ctx).apply {
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setRiveResource(raw, stateMachineName = stateMachine, fit = fit)
            riveLog("$stateMachine made")
        }
    }.getOrNull()
}

/** Test builds: every Rive view made and released (tag RiveViews), to check none comes and goes with each page. */
internal fun riveLog(what: String) {
    if (BuildConfig.DEBUG || BuildConfig.VERSION_NAME.contains("-test")) Log.i("RiveViews", what)
}

/**
 * The page transition (family_tv_transition.riv, state machine "Transition": number "style",
 * trigger "go", events "covered" and "done"). Made once with the TV home and kept. Hidden when idle, it never blocks a key:
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

    /** Starts [style] ([PAGE] or [PAGE_LIGHT]); [onCovered] runs once nothing underneath shows. False, with nothing run, unless idle. */
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
        view.postDelayed(coverLate, PAGE_COVERED_BY_MS)
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
        /** Style 2: the light-glass wipe, for a new page on the light look; timed as [PAGE]. */
        const val PAGE_LIGHT = 2

        /** Null where Rive can't run; the caller swaps straight away then. */
        fun create(ctx: Context): RiveTransition? = riveView(ctx, R.raw.family_tv_transition, TRANSITION, Fit.FILL)?.let(::RiveTransition)
    }
}

/**
 * A tab's Rive icon (family_tv_nav_icons.riv, artboard [artboard], state machine "Icon"): it plays
 * its own move once on focus, a squash on each new [presses], and follows [selected] and [light]
 * for its ink. Never paused or played again from here: each input change starts it, and it stops drawing once
 * its state machine settles. Paused and played again, the onn's surface came back blank for a frame (round 15).
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
                riveLog("tab icon $artboard made")
            }
        },
        modifier = modifier,
        onRelease = { riveLog("tab icon $artboard released") },
    )
    LaunchedEffect(view, focused, selected, light, presses) {
        val v = view ?: return@LaunchedEffect
        runCatching {
            v.setBooleanState(ICON, "light", light)
            v.setBooleanState(ICON, "selected", selected)
            v.setBooleanState(ICON, "focused", focused)
            if (presses != fired[0]) { fired[0] = presses; v.fireState(ICON, "press") }
        }
    }
}

/**
 * Where OK lands on the TV home's glass cards, for one shared press ripple ([PressRipple])
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
 * The press ripple over [host]'s latest pressed card, clipped to its shape, after the Rive design
 * (rive/press: a glass ripple growing from the centre, a soft core flash and a bright rim, 250 ms).
 * Drawn here as layers that only scale and fade: the Rive view it was had its surface remade for
 * every card's size, and the Kitchen's Fire Stick crashed tearing those surfaces down (Oct 27).
 * Nothing is composed between presses; the press never waits for it.
 */
@Composable
internal fun PressRipple(host: PressHost, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val spot = host.spot
    val at = remember { Animatable(1f) }
    Box(modifier.onPlaced { host.coords = it }) {
        if (spot != null && at.value < 1f) {
            val shape = spot.shape
            with(density) {
                Box(Modifier.offset { spot.offset }.size(spot.size.width.toDp(), spot.size.height.toDp()).clip(shape)) {
                    // The ripple: a ring of light in the card's proportions, growing from a point past the card's edges.
                    Spacer(
                        Modifier.fillMaxSize().graphicsLayer {
                            val p = at.value
                            val grow = 0.12f + 0.88f * (1f - (1f - p) * (1f - p))
                            scaleX = grow
                            scaleY = grow * size.height / size.width
                            alpha = minOf(1f, p / 0.2f, (1f - p) / 0.5f).coerceIn(0f, 1f)
                        }.drawWithCache {
                            val r = size.width * 0.66f
                            val ring = Brush.radialGradient(
                                0f to Color.Transparent, 0.45f to Color(0x0DFFFFFF), 0.74f to Color(0x33FFFFFF), 0.86f to Color(0x4CFFFFFF),
                                0.94f to Color(0x1FFFFFFF), 1f to Color.Transparent, radius = r,
                            )
                            onDrawBehind { drawCircle(ring, r) }
                        },
                    )
                    // A soft flash at the centre, gone by the ripple's middle.
                    Spacer(
                        Modifier.fillMaxSize().graphicsLayer {
                            val p = at.value
                            scaleY = size.height / size.width
                            alpha = minOf(1f, p / 0.15f, (0.6f - p) / 0.45f).coerceIn(0f, 1f)
                        }.drawWithCache {
                            val r = size.width * 0.25f
                            val core = Brush.radialGradient(0f to Color(0x38FFFFFF), 0.6f to Color(0x1AFFFFFF), 1f to Color.Transparent, radius = r)
                            onDrawBehind { drawCircle(core, r) }
                        },
                    )
                    // The rim flashes around the card's edge, brighter at the top.
                    Spacer(
                        Modifier.fillMaxSize().graphicsLayer {
                            val p = at.value
                            alpha = minOf(1f, p / 0.3f, (1f - p) / 0.7f).coerceIn(0f, 1f)
                        }.drawWithCache {
                            val w = 3.dp.toPx()
                            val edge = shape.createOutline(Size(size.width - w, size.height - w), layoutDirection, this)
                            val ink = Brush.verticalGradient(0f to Color(0xF2FFFFFF), 1f to Color(0x66FFFFFF))
                            onDrawBehind { translate(w / 2, w / 2) { drawOutline(edge, ink, style = Stroke(w)) } }
                        },
                    )
                }
            }
        }
    }
    LaunchedEffect(spot) {
        if (spot == null) return@LaunchedEffect
        at.snapTo(0f)
        at.animateTo(1f, tween(PRESS_MS, easing = LinearEasing))
    }
}

private const val ICON = "Icon"
/** The ripple's 250 ms. */
private const val PRESS_MS = 250
private const val TRANSITION = "Transition"
/** "covered" comes at about 265 ms; past this the swap happens anyway. */
private const val PAGE_COVERED_BY_MS = 400L
/** "done" comes at about 505 ms at most; past this the overlay hides anyway. */
private const val DONE_BY_MS = 800L
