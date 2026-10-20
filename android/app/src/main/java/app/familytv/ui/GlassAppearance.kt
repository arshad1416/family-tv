package app.familytv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.familytv.user.Appearance
import app.familytv.user.GlassPrefs
import app.familytv.weather.Forecast
import app.familytv.weather.Sun
import kotlinx.coroutines.delay
import java.time.ZoneId

/** How long the whole screen takes to cross between dark and light. */
private const val CROSSFADE_MS = 2_000

/**
 * How light the TV home is, 0 (dark) to 1 (light), easing over [CROSSFADE_MS] when it changes.
 * Auto is dark from sunset to sunrise in Toronto, by the weather's own times once they're loaded,
 * and looks again every minute.
 */
@Composable
internal fun rememberLightness(appearance: Appearance, forecast: Forecast?): Float {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    val dark = when (appearance) {
        Appearance.LIGHT -> false
        Appearance.DARK -> true
        Appearance.AUTO -> {
            val t = GlassPrefs.TORONTO
            Sun.isDark(now, ZoneId.systemDefault(), t.lat, t.lon, forecast?.sunrises.orEmpty(), forecast?.sunsets.orEmpty())
        }
    }
    val lightness by animateFloatAsState(if (dark) 0f else 1f, tween(CROSSFADE_MS), label = "appearance")
    return lightness
}

/**
 * The sun/moon switch before Settings: Select steps Light, Auto, Dark and round again. The arrows
 * only move focus on, as from any button (stepping with Left trapped focus here). The knob slides in 0.3 s.
 */
@Composable
internal fun AppearanceSwitch(value: Appearance, onChange: (Appearance) -> Unit, modifier: Modifier = Modifier) {
    val pos by animateFloatAsState(value.ordinal / 2f, tween(300), label = "knob")
    val steps = Appearance.entries
    FocusBox(
        onClick = { onChange(steps[(value.ordinal + 1) % steps.size]) },
        modifier = modifier
            .size(96.gx, 44.gx)
            .semantics { contentDescription = "Appearance: ${value.label}" },
        color = Color.Transparent,
        outline = true,
        shape = RoundedCornerShape(50),
    ) {
        Canvas(Modifier.fillMaxSize()) { drawSwitch(pos) }
    }
}

// The mockup's art, in its 96 x 44 units: (x, y, radius, alpha).
private val CLOUDS = listOf(
    floatArrayOf(58f, 40f, 11f, .55f), floatArrayOf(70f, 34f, 10f, .55f), floatArrayOf(84f, 30f, 11f, .55f),
    floatArrayOf(62f, 44f, 10f, .95f), floatArrayOf(74f, 40f, 10f, .95f), floatArrayOf(88f, 38f, 11f, .95f),
)
private val STARS = listOf(
    floatArrayOf(12f, 12f, 1.3f, .9f), floatArrayOf(24f, 28f, 1f, .7f), floatArrayOf(34f, 11f, 1.6f, 1f), floatArrayOf(18f, 21f, .8f, .6f),
    floatArrayOf(42f, 30f, 1.1f, .8f), floatArrayOf(48f, 16f, .9f, .7f), floatArrayOf(30f, 36f, .8f, .5f),
)
private val CRATERS = listOf(floatArrayOf(-6f, -6f, 4f), floatArrayOf(5f, 5f, 5f), floatArrayOf(6f, -8f, 2.3f))
private val SKY = listOf(Color(0xFF3D8FE0), Color(0xFF8FD2FF))
private val NIGHT = listOf(Color(0xFF141A33), Color(0xFF2D3868))

/**
 * The switch at [p]: 0 Light (all sky, the sun at the left), 0.5 Auto (half sky, half night, a
 * half-sun half-moon knob in the middle), 1 Dark (all night, the moon at the right).
 */
private fun DrawScope.drawSwitch(p: Float) {
    val u = size.width / 96f
    val split = 96f * (1 - p) * u
    val knob = Offset((22f + 52f * p) * u, 22f * u)
    val r = 17f * u
    clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(22f * u))) }) {
        clipRect(right = split) {
            drawRect(Brush.verticalGradient(SKY))
            CLOUDS.forEach { (x, y, cr, a) -> drawCircle(Color.White.copy(alpha = a), cr * u, Offset((x - 112f * p) * u, y * u)) }
        }
        clipRect(left = split) {
            drawRect(Brush.verticalGradient(NIGHT))
            STARS.forEach { (x, y, sr, a) -> drawCircle(Color.White.copy(alpha = a), sr * u, Offset((x + 104f * (1 - p)) * u, y * u)) }
        }
        drawCircle(Color.White.copy(alpha = .07f), 34f * u, knob)
        drawCircle(Color.White.copy(alpha = .09f), 27f * u, knob)
        clipRect(right = split) {
            drawCircle(Color(0xFFFFC83D), r, knob)
            drawCircle(Color(0xCCFFE08A), r, knob, style = Stroke(1.5f * u))
        }
        clipRect(left = split) {
            drawCircle(Color(0xFFDFE3EE), r, knob)
            CRATERS.forEach { (dx, dy, cr) -> drawCircle(Color(0xFFBCC3D3), cr * u, knob + Offset(dx * u, dy * u)) }
        }
    }
    drawRoundRect(Color(0x59FFFFFF), Offset(.5f * u, .5f * u), Size(95f * u, 43f * u), CornerRadius(21.5f * u), style = Stroke(u))
}
