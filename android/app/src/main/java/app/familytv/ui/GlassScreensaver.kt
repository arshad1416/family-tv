package app.familytv.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.data.Kind
import app.familytv.portal.Profile
import app.familytv.weather.WeatherParse
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** How long the TV home waits with no key before the screensaver (backlog item 16). */
internal const val SCREENSAVER_IDLE_MS = 5 * 60_000L
private const val SLIDE_MS = 12_000

/**
 * True when the system asks for less motion (animations off in Accessibility or Developer
 * options): pans and slides are skipped then, fades kept short.
 */
internal fun reducedMotion(ctx: Context): Boolean =
    runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

/** [reducedMotion], read once for this composable. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { reducedMotion(ctx) }
}

/**
 * The artwork screensaver: the line's newest film posters, one at a time with a slow drift (none
 * when the system asks for less motion), under the clock and today's weather. Only posters from
 * categories this profile can see; never adult ones. Any key wakes it (GlassHome eats that key).
 */
@Composable
internal fun Screensaver(profile: Profile, weather: GlassWeather) {
    val ctx = LocalContext.current
    val visible = categories(profile, Kind.MOVIE)
    var art by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(visible) {
        val ok = categoryKeys(visible)
        art = ctx.app.db.catalog().recentMovies(200).filter { (it.sourceId to it.categoryId) in ok }.mapNotNull { it.poster }.distinct().take(40)
    }
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(art) { while (art.size > 1) { delay(SLIDE_MS.toLong()); i = (i + 1) % art.size } }
    val still = rememberReducedMotion()
    val now = rememberMinute()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(art.getOrNull(i), animationSpec = tween(1_200), label = "screensaver") { url ->
            if (url != null) {
                // Transform only: a slow drift and slight zoom, nothing blurred.
                val drift = remember(url) { Animatable(0f) }
                LaunchedEffect(url) { if (!still) drift.animateTo(1f, tween(SLIDE_MS + 1_200, easing = LinearEasing)) }
                AsyncImage(
                    url, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        val s = 1.08f + 0.04f * drift.value
                        scaleX = s; scaleY = s
                        translationX = -40.dp.toPx() * drift.value
                    },
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD9000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 48.dp)) {
            Text(time(now), fontSize = 64.sp, fontWeight = FontWeight.Light, color = Color.White)
            weather.forecast?.let { f ->
                Text("${WeatherParse.icon(f.code, f.isDay)}  ${f.temp}°  ${WeatherParse.condition(f.code)}", fontSize = 22.sp, color = Color(0xCCFFFFFF))
            }
            Text("Press any button to wake", fontSize = 14.sp, color = Color(0x99FFFFFF), modifier = Modifier.padding(top = 10.dp))
        }
    }
}
