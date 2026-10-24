package app.familytv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The top bar's chosen pill at [chosen] and focus bubble at [focused] (x and width in the tab row,
 * px), drawn behind the tabs as one of each that glides from tab to tab (parity checklist, "Top
 * bar: highlight glides between icons"). Each edge rides its own spring, the leading one stiffer,
 * so the pill stretches a little toward the next tab (at most [GLIDE_STRETCH]) and settles; a new
 * tab mid-glide takes over from where the pill is. The bubble fades in where focus enters the row
 * and out where it leaves. With reduced motion both jump and fade in over [STILL_FADE_MS] instead.
 * [chosenFocused]: the bubble is on the white chosen pill, so it draws no face over it.
 */
@Composable
internal fun rememberTabGlide(chosen: Pair<Float, Float>?, focused: Pair<Float, Float>?, chosenFocused: Boolean): DrawScope.() -> Unit {
    val still = LocalReducedMotion.current
    val look = BubbleLook.current
    val selected = Glass.Selected
    val accent = LocalGlassShell.current?.glow
    val pill = remember { GlideSpan() }
    val bubble = remember { GlideSpan() }
    val pillShown = remember { Animatable(1f) }
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
    return {
        val h = size.height
        if (chosen != null && pill.placed) {
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
}

/** A span (a pill's left and right edges) that glides to each new place on two springs. */
private class GlideSpan {
    private val left = Animatable(0f)
    private val right = Animatable(0f)
    private var rightward = true
    private var width = 0f
    private var cap = 0f
    var placed = false
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
/** With reduced motion: the jump's fade. */
private const val STILL_FADE_MS = 120
