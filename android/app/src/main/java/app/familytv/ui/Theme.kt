package app.familytv.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import app.familytv.usage.Usage

/** Dark navy palette with one strong blue for focus. */
object Tv {
    val Bg = Color(0xFF060A14)
    val Panel = Color(0xFF0D1626)
    val Raised = Color(0xFF16223A)
    val Line = Color(0xFF233250)
    val Accent = Color(0xFF1F6BFF)
    val AccentSoft = Color(0x401F6BFF)
    val Text = Color.White
    val Muted = Color(0xFF93A0B8)
    val Good = Color(0xFF4ADE80)
    val Warn = Color(0xFFFBBF24)
    val Bad = Color(0xFFF87171)
    val Shape = RoundedCornerShape(10.dp)
}

/** Secondary text: [Tv.Muted], or the glass look's own dim text on the TV's glass pages (dark in the light look). */
val MutedText: Color @Composable @ReadOnlyComposable get() = if (LocalGlass.current) Glass.Dim else Tv.Muted

@Composable
fun FamilyTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tv.Accent, onPrimary = Tv.Text,
            background = Tv.Bg, onBackground = Tv.Text,
            surface = Tv.Bg, onSurface = Tv.Text,
            surfaceVariant = Tv.Panel, onSurfaceVariant = Tv.Muted,
            inverseSurface = Tv.Accent, inverseOnSurface = Tv.Text,
        ),
    ) { CompositionLocalProvider(LocalContentColor provides Tv.Text, content = content) }
}

/**
 * The one focusable building block: D-pad focus paints it blue (or outlines it, for artwork),
 * or on the TV's glass pages ([LocalGlass]) rings it in white, or raises rows and pills as a glass bubble ([Lift.Glow]); OK clicks it. Lighter than tv-material's Surface, which matters in the guide grid.
 * [onLongClick] runs on holding OK or pressing Menu on a remote, and on a long press on touch.
 * [ring] false leaves the focus to [content] to show, as the Rive appearance switch's own glow does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    color: Color = Tv.Panel,
    selected: Boolean = false,
    outline: Boolean = false,
    shape: Shape = Tv.Shape,
    scale: Float = 1f,
    lift: Lift = Lift.None,
    onFocus: () -> Unit = {},
    contentAlignment: Alignment = Alignment.TopStart,
    ring: Boolean = true,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var hasFocus by remember { mutableStateOf(false) }
    // A finger held on a card lights it like D-pad focus does, so touch gets feedback too.
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused = hasFocus || pressed
    val okPress = remember { OkPress() }
    val memory = LocalFocusMemory.current
    val me = remember { FocusRequester() }
    val glass = LocalGlass.current
    // Glass rows and pills take the raised glass bubble ([bubbleFace]) on focus, not a white ring.
    val bubble = glass && lift == Lift.Glow
    val still = LocalReducedMotion.current
    val bg = when {
        bubble -> Glass.fill(color, focused = false, selected = false)
        glass -> Glass.fill(color, focused && !outline, selected)
        focused && !outline -> Tv.Accent
        selected -> Tv.Raised
        else -> color
    }
    val ring = when {
        !focused || bubble || !ring -> null
        glass -> Glass.Ring
        outline -> Tv.Accent
        else -> null
    }
    // Glass only: a shadow (and the bubble) or a shadow and a slight tilt (posters) while focused. Transforms and
    // opacity only, nothing blurred; the scale springs in, and none of it moves when the system asks for less motion.
    val lifted = if (glass) lift else Lift.None
    val look = BubbleLook.current
    val shade = if (lifted == Lift.Glow) look.shadow else Color.Black
    val animated = scale != 1f || lifted != Lift.None
    val lit = if (animated) animateFloatAsState(if (focused) 1f else 0f, if (still) snap() else tween(FOCUS_FADE_MS, easing = FastOutSlowInEasing), label = "lit") else null
    val grow = if (animated) animateFloatAsState(if (focused && !still) scale else 1f, if (still) snap() else spring(dampingRatio = 0.8f, stiffness = 700f), label = "grow") else null
    val chosen = if (bubble) animateFloatAsState(if (selected) 1f else 0f, if (still) snap() else tween(CHOSEN_FADE_MS), label = "chosen") else null
    val accent = LocalGlassShell.current?.glow
    Box(
        modifier
            .then(
                if (lit != null && grow != null) Modifier.graphicsLayer {
                    scaleX = grow.value; scaleY = grow.value
                    val l = lit.value
                    if (lifted == Lift.None || l == 0f) return@graphicsLayer
                    this.shape = shape
                    shadowElevation = (if (lifted == Lift.Glow) 12.dp else 18.dp).toPx() * l
                    ambientShadowColor = shade; spotShadowColor = shade
                    if (lifted == Lift.Tilt) { rotationX = 3f * l; cameraDistance = 16 * density }
                } else Modifier,
            )
            .onFocusChanged { hasFocus = it.isFocused; if (it.isFocused) { onFocus(); memory?.focused(me) } }
            .then(if (memory != null) Modifier.focusRequester(me) else Modifier)
            .then(
                if (lit != null && chosen != null) Modifier.bubbleGlow(shape, { maxOf(BUBBLE_GLOW * lit.value, CHOSEN_GLOW * chosen.value) }, { accent?.value ?: Tv.Accent }) else Modifier,
            )
            .clip(shape)
            .background(bg)
            .then(
                // No lit face over a slider (outline) or a white pill, which it would wash out.
                if (lit != null && chosen != null) Modifier.bubbleFace(shape, look, face = !outline && bg != Glass.Selected, { lit.value }, { chosen.value }) else Modifier,
            )
            .then(if (ring != null) Modifier.border(if (outline) 3.dp else 2.dp, ring, shape) else Modifier)
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(source, indication = null, onClick = onClick)
                } else {
                    val held = { Usage.log(Usage.Action.HOLD_OK); onLongClick() }
                    Modifier.remoteLongPress(okPress, onClick, held).combinedClickable(source, indication = null, onLongClick = held, onClick = onClick)
                },
            ),
        contentAlignment = contentAlignment,
    ) {
        // A white glass pill takes dark text.
        if (glass && bg == Glass.Selected) CompositionLocalProvider(LocalContentColor provides Glass.OnSelected) { content(focused) } else content(focused)
    }
}

/**
 * The [FocusBox]es last focused on one screen, newest first, so the screen can put focus back after
 * a menu or a prompt closes rather than let the next press start again at its top ([SettingsScreen]).
 */
class FocusMemory {
    private val recent = ArrayDeque<FocusRequester>()

    fun focused(box: FocusRequester) {
        recent.remove(box)
        recent.addFirst(box)
        while (recent.size > 8) recent.removeLast()
    }

    /** Focuses the newest box still on screen (one that has gone throws); false if none is left. */
    fun restore(): Boolean = recent.any { runCatching { it.requestFocus() }.isSuccess }
}

/** The screen's [FocusMemory]; boxes outside one (and in dialogs) remember nothing. */
val LocalFocusMemory = staticCompositionLocalOf<FocusMemory?> { null }

/** How a glass [FocusBox] stands out while focused, beyond its ring and scale. */
enum class Lift { None, Glow, Tilt }

/** Where an OK press on a [FocusBox] with a long-press action is: [down] seen here, [held] long enough. */
private class OkPress {
    var down = false
    var held = false
}

/**
 * Remote keys for a box with a long-press action: OK clicks on release, holding OK runs
 * [onLongClick] once (and the release then doesn't click), and Menu runs it too. A release
 * whose press began elsewhere (the OK that opened this screen) does nothing.
 */
private fun Modifier.remoteLongPress(press: OkPress, onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    onPreviewKeyEvent { e ->
        val k = e.nativeKeyEvent
        when (k.keyCode) {
            android.view.KeyEvent.KEYCODE_MENU -> { if (e.type == KeyEventType.KeyUp) onLongClick(); true }
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> when (e.type) {
                KeyEventType.KeyDown -> {
                    if (k.repeatCount == 0) { press.down = true; press.held = false }
                    else if (press.down && !press.held) { press.held = true; onLongClick() }
                    true
                }
                KeyEventType.KeyUp -> {
                    if (press.down && !press.held) onClick()
                    press.down = false; press.held = false
                    true
                }
                else -> false
            }
            else -> false
        }
    }

@Composable
fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    // Touch screens get the 48dp minimum target; TV keeps its compact pill.
    val minH = if (LocalFormFactor.current.touch) 48.dp else 0.dp
    FocusBox(onClick = { if (enabled) onClick() }, modifier = modifier.heightIn(min = minH), color = Tv.Raised, shape = RoundedCornerShape(24.dp), contentAlignment = Alignment.Center) {
        Text(text, Modifier.padding(horizontal = 22.dp, vertical = 10.dp), fontSize = 16.sp, color = if (enabled) Tv.Text else Tv.Muted)
    }
}

/** A page's own title. On phones and tablets the touch header already shows it, so only TV draws it. */
@Composable
fun PageTitle(text: String, bottom: Dp = 14.dp) {
    // The TV's glass top bar already shows it too.
    if (!LocalFormFactor.current.touch && !LocalGlass.current) Text(text, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = bottom))
}

/** Thin programme progress bar. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp, track: Color = Color(0x33FFFFFF), fill: Color = Tv.Accent) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height)).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(fill))
    }
}

fun progress(start: Long, stop: Long, now: Long = System.currentTimeMillis()): Float =
    if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
