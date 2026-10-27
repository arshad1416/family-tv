package app.familytv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The top bar's chosen pill at [chosen] and focus bubble at [focused] (x and width in the tab row,
 * px), drawn behind the tabs as one of each that glides from tab to tab (parity checklist, "Top
 * bar: highlight glides between icons"). Each edge rides its own spring, the leading one stiffer,
 * so the pill stretches a little toward the next tab (at most [GLIDE_STRETCH]) and settles; a new
 * tab mid-glide takes over from where the pill is. The bubble fades in where focus enters the row
 * and out where it leaves. With reduced motion both jump and fade in over [STILL_FADE_MS] instead.
 * [chosenFocused]: the bubble is on the white chosen pill, so it draws no face over it. Each tab's label
 * ([GlideInk]) takes its dark ink from where the pill is drawn in that frame, so a label is never white
 * on the white pill or dark on the bar while it glides, or while it fades in with reduced motion.
 */
@Composable
internal fun rememberTabGlide(chosen: Pair<Float, Float>?, focused: Pair<Float, Float>?, chosenFocused: Boolean): TabGlide {
    val still = LocalReducedMotion.current
    val look = BubbleLook.current
    val selected = Glass.Selected
    val accent = LocalGlassShell.current?.glow
    val glide = remember { TabGlide() }
    val pill = glide.pill
    val bubble = remember { GlideSpan() }
    val pillShown = glide.pillShown
    val lit = remember { Animatable(0f) }
    val chosenNow by rememberUpdatedState(chosen)
    val focusedNow by rememberUpdatedState(focused)
    LaunchedEffect(still) {
        snapshotFlow { chosenNow }.collect { spot ->
            if (spot == null) return@collect
            launch {
                val fade = still && pill.placed
                pill.glide(spot, jump = still)
                if (fade) {
                    pillShown.snapTo(0f)
                    pillShown.animateTo(1f, tween(STILL_FADE_MS))
                }
            }
        }
    }
    LaunchedEffect(still) {
        snapshotFlow { focusedNow }.collect { spot ->
            launch {
                if (spot == null) {
                    lit.animateTo(0f, tween(FOCUS_FADE_MS))
                    return@launch
                }
                // Into the row from elsewhere it appears on the tab; between tabs it glides.
                if (still || lit.value == 0f) {
                    bubble.glide(spot, jump = true)
                    if (still) lit.snapTo(0f)
                    lit.animateTo(1f, tween(if (still) STILL_FADE_MS else FOCUS_FADE_MS))
                } else {
                    launch { lit.animateTo(1f, tween(FOCUS_FADE_MS)) }
                    bubble.glide(spot, jump = false)
                }
            }
        }
    }
    // Set here, not after: the row's pill and the labels and icons read the same state in the same frame.
    glide.chosen = chosen != null
    glide.draw = {
        val h = size.height
        if (glide.chosen && pill.placed) {
            val (x, w) = pill.span()
            drawRoundRect(selected, Offset(x, 0f), Size(w, h), CornerRadius(h / 2), alpha = pillShown.value)
        }
        val a = lit.value
        if (a > 0f && bubble.placed) {
            val (x, w) = bubble.span()
            // It grows with the focused tab, as each pill's own bubble did.
            val dx = w * (TAB_GROW - 1f) / 2 * a
            val dy = h * (TAB_GROW - 1f) / 2 * a
            drawPillBubble(Offset(x - dx, -dy), Size(w + 2 * dx, h + 2 * dy), look, accent?.value ?: Tv.Accent, a, face = !chosenFocused)
        }
    }
    return glide
}

/** The tab row's glide: [draw] goes behind the row, placed at [row]; its labels take their ink from it ([GlideInk]). */
internal class TabGlide {
    internal val pill = GlideSpan()
    internal val pillShown = Animatable(1f)
    /** Whether a tab is chosen (none on Settings). State, like the pill's place, so labels drawn before it is known redraw. */
    internal var chosen by mutableStateOf(false)
    var draw: DrawScope.() -> Unit = {}
    /** Where the tab row is, which the pill's spans are in. */
    var row: LayoutCoordinates? = null
    private val path = Path()

    /**
     * The pill's x and width as drawn now, or none while no tab is chosen or the pill isn't placed yet. Read in a
     * draw, it redraws the label when any of that changes, the first placing included (the chosen tab at x 0 at start).
     */
    internal fun pillNow(): Pair<Float, Float>? = if (chosen && pill.placed) pill.span() else null

    private val paint = Paint()
    private val tint = Paint()

    /** The pill as drawn now, in [at]'s place (as a layout placed in the row), or none. */
    private fun pillIn(at: LayoutCoordinates?): RoundRect? {
        val row = row
        val (x, w) = pillNow() ?: return null
        if (at == null || row == null || !at.isAttached || !row.isAttached) return null
        val a = at.localPositionOf(row, Offset(x, 0f))
        val b = at.localPositionOf(row, Offset(x + w, row.size.height.toFloat()))
        return RoundRect(a.x, a.y, b.x, b.y, CornerRadius((b.y - a.y) / 2))
    }

    /**
     * Whether the pill is clear of an icon placed at [at]: all over it when [chosen], else off it (or not drawn), so
     * the icon can change its own ink under the tint without any edge crossing it.
     */
    fun clear(at: LayoutCoordinates?, chosen: Boolean): Boolean {
        val pill = pillIn(at)
        val box = Rect(Offset.Zero, at?.size?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: return false)
        return if (chosen) pill != null && pill.covers(box) else pill == null || !pill.touches(box)
    }

    private fun RoundRect.touches(box: Rect) = Rect(left, top, right, bottom).overlaps(box)
    private fun RoundRect.covers(box: Rect) = contains(box.topLeft) && contains(box.topRight) && contains(box.bottomLeft) && contains(box.bottomRight)

    private fun outline(pill: RoundRect): Path = path.apply { rewind(); addRoundRect(pill) }

    /**
     * Draws a tab's Rive icon placed at [at], whose own ink is [shown] (the selected ink or not): the part on the pill
     * in [over] and the rest in [under] wherever the icon's ink is wrong for it ([iconTint]), so the icon splits at the
     * pill's edge as the labels do. The icon is one Rive view drawn once or twice through clips, never a second view.
     */
    fun iconInk(scope: ContentDrawScope, at: LayoutCoordinates?, shown: Boolean, cover: Float, under: Color, over: Color) = with(scope) {
        val pill = pillIn(at)
        val box = Rect(Offset.Zero, size)
        when {
            pill == null || !pill.touches(box) -> tinted(scope, iconTint(false, shown, cover), under)
            pill.covers(box) -> tinted(scope, iconTint(true, shown, cover), over)
            else -> {
                val path = outline(pill)
                clipPath(path, ClipOp.Difference) { tinted(scope, iconTint(false, shown, cover), under) }
                clipPath(path) { tinted(scope, iconTint(true, shown, cover), over) }
            }
        }
    }

    /** The content as it is, cross-faded to it tinted all in [color] by [t] (0 none, 1 all). */
    private fun tinted(scope: ContentDrawScope, t: Float, color: Color) = with(scope) {
        if (t <= 0f) return@with drawContent()
        drawIntoCanvas {
            val bounds = Rect(Offset.Zero, size)
            if (t < 1f) {
                paint.alpha = 1f - t
                it.saveLayer(bounds, paint)
                drawContent()
                it.restore()
            }
            tint.alpha = t
            tint.colorFilter = ColorFilter.tint(color, BlendMode.SrcIn)
            it.saveLayer(bounds, tint)
            drawContent()
            it.restore()
        }
    }

    /**
     * Draws a label ([inside]: its dark copy) placed at [at]: the dark copy only over the pill as drawn now,
     * the light one everywhere else, each faded with the pill while it fades in.
     */
    fun ink(scope: ContentDrawScope, at: LayoutCoordinates?, inside: Boolean) = with(scope) {
        val path = pillIn(at)?.let(::outline)
        if (path == null) {
            if (!inside) drawContent()
            return@with
        }
        val shown = pillShown.value
        if (!inside) clipPath(path, ClipOp.Difference) { this@with.drawContent() }
        // Over the pill, the dark copy shows as much as the pill does and the light one the rest.
        val over = if (inside) shown else 1f - shown
        if (over <= 0f) return@with
        clipPath(path) {
            if (over >= 1f) this@with.drawContent()
            else drawIntoCanvas {
                paint.alpha = over
                it.saveLayer(Rect(Offset.Zero, size), paint)
                this@with.drawContent()
                it.restore()
            }
        }
    }
}

/**
 * How much of a Rive tab icon's part ([inside] the pill or off it) is drawn tinted, when its own ink is [shown]: all of
 * it where that ink is wrong for the part, else [cover], the tint still over the icon while its own ink changes.
 */
internal fun iconTint(inside: Boolean, shown: Boolean, cover: Float): Float = if (inside != shown) 1f else cover

/**
 * A tab's Rive icon ([icon], given the selected ink to show and the modifier for its view) split at the gliding pill's
 * edge like its label. The icon's own ink changes over [ICON_INK_MS], so it is told the tab is [chosen] only once the
 * pill is clear of its edges ([TabGlide.clear]); until then, and while its ink changes, the tint draws the right ink
 * over it ([IconInk]). Settled, it is drawn as it is.
 */
@Composable
internal fun GlideIcon(glide: TabGlide, chosen: Boolean, under: Color, over: Color, icon: @Composable (selected: Boolean, modifier: Modifier) -> Unit) {
    // State, so a first placing re-checks whether the pill is clear of it.
    var at by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val ink = remember { IconInk(chosen) }
    val chosenNow by rememberUpdatedState(chosen)
    LaunchedEffect(glide, ink) {
        snapshotFlow { chosenNow.let { it to glide.clear(at, it) } }.collect { (now, clear) -> ink.update(this, now, clear) }
    }
    icon(ink.shown, Modifier.onPlaced { at = it }.drawWithContent { glide.iconInk(this, at, ink.shown, ink.cover, under, over) })
}

/**
 * A Rive tab icon's ink: [shown], the ink the icon itself is told to show (selected or not), and [cover], the tint
 * still over all of it while that ink changes. Only a change of [shown] starts the tint, which then always fades out;
 * nothing else stops it, so a settled icon has none.
 */
internal class IconInk(chosen: Boolean) {
    var shown by mutableStateOf(chosen)
        private set
    var cover by mutableFloatStateOf(0f)
        private set
    private var fade: Job? = null

    /** The tab is [chosen] or not now; [clear]: the pill is clear of the icon's edges, so its ink may change. */
    fun update(scope: CoroutineScope, chosen: Boolean, clear: Boolean) {
        if (chosen == shown || !clear) return
        fade?.cancel()
        // In the same frame as the new ink, so no frame shows the old one changing without the tint.
        cover = 1f
        shown = chosen
        fade = scope.launch {
            delay(ICON_INK_MS)
            animate(1f, 0f, animationSpec = tween(ICON_UNTINT_MS)) { v, _ -> cover = v }
            cover = 0f
        }
    }
}

/** A tab label drawn twice from [label]: [under] off the gliding pill, [over] on it, split where the pill is now. */
@Composable
internal fun GlideInk(glide: TabGlide, under: Color, over: Color, label: @Composable (color: Color, modifier: Modifier) -> Unit) {
    val at = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Box(Modifier.onPlaced { at[0] = it }) {
        label(under, Modifier.drawWithContent { glide.ink(this, at[0], inside = false) })
        label(over, Modifier.drawWithContent { glide.ink(this, at[0], inside = true) })
    }
}

/** A span (a pill's left and right edges) that glides to each new place on two springs. */
internal class GlideSpan {
    private val left = Animatable(0f)
    private val right = Animatable(0f)
    private var rightward = true
    private var width = 0f
    private var cap = 0f
    var placed by mutableStateOf(false)
        private set

    /** Glides to [spot] (x and width), or [jump]s there; the first call always jumps. */
    suspend fun glide(spot: Pair<Float, Float>, jump: Boolean): Unit = coroutineScope {
        val (x, w) = spot
        cap = maxOf(width, w) * GLIDE_STRETCH
        width = w
        if (jump || !placed) {
            launch { left.snapTo(x) }
            right.snapTo(x + w)
            placed = true
            return@coroutineScope
        }
        if (x != left.value) rightward = x > left.value
        // The edge in front leads; the one behind follows on the softer spring.
        launch { left.animateTo(x, if (rightward) TRAIL else LEAD) }
        right.animateTo(x + w, if (rightward) LEAD else TRAIL)
        Unit
    }

    /** The x and width to draw now, the stretch held to the cap. */
    fun span(): Pair<Float, Float> {
        var l = left.value
        var r = right.value
        if (r - l > cap) if (rightward) l = r - cap else r = l + cap
        return l to (r - l)
    }
}

/** The leading edge: critically damped, about 260 ms. */
private val LEAD = spring<Float>(dampingRatio = 0.85f, stiffness = 600f)
/** The trailing edge: softer, so it lags the leader by about 40 ms mid-glide. */
private val TRAIL = spring<Float>(dampingRatio = 0.85f, stiffness = 380f)
/** The most a gliding pill stretches: 15% past the wider of the two tabs. */
private const val GLIDE_STRETCH = 1.15f
/** How much a focused tab grows (its pill's scale). */
private const val TAB_GROW = 1.06f
/** How long a Rive tab icon takes to change its own ink for "selected" (nav-icons spec: 180 ms), with a frame or two spare. */
private const val ICON_INK_MS = 220L
/** Then the tint over it fades, showing the icon's own selected glow and dot. */
private const val ICON_UNTINT_MS = 150
/** With reduced motion: the jump's fade. */
private const val STILL_FADE_MS = 120
