package app.familytv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * The raised glass bubble on a focused glass row or pill ([Lift.Glow]): a face lighter at the top,
 * a 1 dp rim lit from above, a soft shadow and the page's accent glow, in place of the old hard
 * white ring. A chosen row that isn't focused keeps a quiet glass fill and a faint glow. Plain fills,
 * strokes, transforms and opacity only: nothing is blurred, so the onn box keeps up. Rust and Apple
 * match these values (parity checklist, "Live TV: 'All Countries', 3D glass focus bubble").
 */
@Immutable
internal class BubbleLook(
    val faceTop: Color,
    val faceBottom: Color,
    val rimTop: Color,
    val rimBottom: Color,
    /** The shadow under a focused bubble (its colour's alpha is the shadow's strength). */
    val shadow: Color,
    /** A chosen row's fill when focus is elsewhere. */
    val chosen: Color,
) {
    companion object {
        val Dark = BubbleLook(
            faceTop = Color(0x40FFFFFF), faceBottom = Color(0x2BFFFFFF), rimTop = Color(0x8CFFFFFF), rimBottom = Color(0x1AFFFFFF),
            shadow = Color(0x59000000), chosen = Color(0x24FFFFFF),
        )
        val Light = BubbleLook(
            faceTop = Color(0xC7FFFFFF), faceBottom = Color(0xB3FFFFFF), rimTop = Color(0xFFFFFFFF), rimBottom = Color(0x330B0F18),
            shadow = Color(0x2E000000), chosen = Color(0x240B0F18),
        )

        /** The look for the glass colours in use: dark text means the light look. */
        val current: BubbleLook @Composable @ReadOnlyComposable get() = if (LocalGlassPalette.current.text.luminance() < 0.5f) Light else Dark
    }
}

/** True when the system asks for less motion; the glass home provides it once for all its boxes. */
internal val LocalReducedMotion = staticCompositionLocalOf { false }

/** The accent glow's strength around a focused bubble, and around a chosen row focus has left. */
internal const val BUBBLE_GLOW = 0.25f
internal const val CHOSEN_GLOW = 0.12f
/** How far a focused bubble grows. */
internal const val BUBBLE_SCALE = 1.04f
/** Fades: focus in and out, and the chosen row moving. */
internal const val FOCUS_FADE_MS = 150
internal const val CHOSEN_FADE_MS = 180

// The glow: eight thin rings 1 dp apart fading outward to 8 dp outside the edge, smooth enough to read as a soft light.
private const val GLOW_RING_DP = 1.1f
private val GLOW_RINGS = (1..8).map { i -> i - 0.5f to (1f - (i - 1) / 8f).let { it * it } }

/** Room around a list's rows, so a focused bubble's growth, shadow and glow aren't cut off at the list's edge. */
internal val BUBBLE_ROOM: PaddingValues @Composable get() = PaddingValues(horizontal = 6.gx, vertical = 8.gx)

/** A content change (a new category, country or channel) fades in, and here a list also rises a little. */
internal const val ARRIVE_MS = 180

/**
 * Fades this in each time [key] changes after the first (from [from] opacity, rising 8 px unless
 * [rise] is false or the system asks for less motion, when the fade is shorter too). Opacity and a
 * transform only, so input is never held up and nothing recomposes while it runs.
 */
@Composable
internal fun Modifier.arriveFade(key: Any?, from: Float = 0f, rise: Boolean = true): Modifier {
    val still = LocalReducedMotion.current
    val shown = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(key) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        shown.snapTo(0f)
        shown.animateTo(1f, tween(if (still) ARRIVE_MS / 2 else ARRIVE_MS, easing = FastOutSlowInEasing))
    }
    val dy = with(LocalDensity.current) { 8.gx.toPx() }
    return graphicsLayer {
        val t = shown.value
        alpha = from + (1f - from) * t
        if (rise && !still) translationY = (1f - t) * dy
    }
}

/** Behind a bubble and outside its edge: the accent glow at [strength] (0 draws nothing). */
internal fun Modifier.bubbleGlow(shape: Shape, strength: () -> Float, accent: () -> Color): Modifier = drawWithCache {
    val ring = Stroke(GLOW_RING_DP.dp.toPx())
    val rings = GLOW_RINGS.map { (out, fade) ->
        val px = out.dp.toPx()
        Triple(px, fade, shape.createOutline(Size(size.width + 2 * px, size.height + 2 * px), layoutDirection, this))
    }
    onDrawBehind {
        val a = strength()
        if (a <= 0f) return@onDrawBehind
        val c = accent()
        rings.forEach { (px, fade, o) -> translate(-px, -px) { drawOutline(o, c, alpha = a * fade, style = ring) } }
    }
}

/**
 * Inside the bubble's shape, over its fill: the chosen fill at [chosen], then the lit face (unless
 * [face] is false, over artwork or a white pill) and the rim at [lit].
 */
internal fun Modifier.bubbleFace(shape: Shape, look: BubbleLook, face: Boolean, lit: () -> Float, chosen: () -> Float): Modifier = drawWithCache {
    val whole = shape.createOutline(size, layoutDirection, this)
    val s = 1.dp.toPx()
    val edge = shape.createOutline(Size(size.width - s, size.height - s), layoutDirection, this)
    val fill = Brush.verticalGradient(listOf(look.faceTop, look.faceBottom))
    val rim = Brush.verticalGradient(listOf(look.rimTop, look.rimBottom))
    val stroke = Stroke(s)
    onDrawBehind {
        val c = chosen()
        if (c > 0f) drawOutline(whole, look.chosen, alpha = c)
        val l = lit()
        if (l <= 0f) return@onDrawBehind
        if (face) drawOutline(whole, fill, alpha = l)
        translate(s / 2, s / 2) { drawOutline(edge, rim, alpha = l, style = stroke) }
    }
}
