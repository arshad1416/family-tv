package app.familytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

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
 * OK clicks it. Lighter than tv-material's Surface, which matters in the guide grid.
 */
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Tv.Panel,
    selected: Boolean = false,
    outline: Boolean = false,
    shape: Shape = Tv.Shape,
    scale: Float = 1f,
    onFocus: () -> Unit = {},
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused && !outline -> Tv.Accent
        selected -> Tv.Raised
        else -> color
    }
    Box(
        modifier
            .then(if (scale != 1f) Modifier.graphicsLayer { if (focused) { scaleX = scale; scaleY = scale } } else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .clip(shape)
            .background(bg)
            .then(if (focused && outline) Modifier.border(3.dp, Tv.Accent, shape) else Modifier)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = contentAlignment,
    ) { content(focused) }
}

@Composable
fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    FocusBox(onClick = { if (enabled) onClick() }, modifier = modifier, color = Tv.Raised, shape = RoundedCornerShape(24.dp), contentAlignment = Alignment.Center) {
        Text(text, Modifier.padding(horizontal = 22.dp, vertical = 10.dp), fontSize = 16.sp, color = if (enabled) Tv.Text else Tv.Muted)
    }
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
