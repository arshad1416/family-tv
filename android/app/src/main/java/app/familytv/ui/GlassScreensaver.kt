package app.familytv.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.familytv.FamilyTvApp
import app.familytv.app
import app.familytv.data.CategoryEntity
import app.familytv.data.Kind
import app.familytv.portal.Profile
import app.familytv.user.SaverSource
import app.familytv.weather.WeatherParse
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlin.random.Random

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

/** One screensaver picture: filling the screen ([crop]) or whole on black, with an optional caption. */
internal class SaverPicture(val url: String, val crop: Boolean, val caption: String? = null)

/**
 * The screensaver, from Settings > Screensaver's pictures: the line's newest film posters, TMDB
 * art of the top films and shows the viewer has, NASA's space and Earth photos (one at a time with
 * a slow drift, none when the system asks for less motion), a drawn scene of the season, or just
 * a dim clock that moves each minute. Pictures come only from categories this viewer can see, never
 * adult ones; when a source has nothing (no TMDB key yet), the library's posters stand in. Any key
 * wakes it (GlassHome eats that key).
 */
@Composable
internal fun Screensaver(profile: Profile, weather: GlassWeather) {
    val ctx = LocalContext.current
    val source by ctx.app.glassPrefs.saverSource.collectAsState()
    val movies = categories(profile, Kind.MOVIE)
    val shows = categories(profile, Kind.SERIES)
    var pictures by remember(source, movies, shows) { mutableStateOf<List<SaverPicture>?>(null) }
    LaunchedEffect(source, movies, shows) {
        if (source != SaverSource.SEASONS && source != SaverSource.CLOCK) pictures = saverPictures(ctx.app, source, movies, shows)
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (source) {
            SaverSource.CLOCK -> ClockOnly(weather)
            SaverSource.SEASONS -> {
                SeasonScene(Splash.season(Calendar.getInstance().get(Calendar.MONTH) + 1))
                SaverClock(weather)
            }
            else -> {
                pictures?.let { SaverSlides(it) }
                SaverClock(weather)
            }
        }
    }
}

private suspend fun saverPictures(app: FamilyTvApp, source: SaverSource, movies: List<CategoryEntity>, shows: List<CategoryEntity>): List<SaverPicture> {
    val chosen = when (source) {
        SaverSource.TITLES -> runCatching { Featured.art(app, movies, shows) }.getOrDefault(emptyList()).shuffled().map { (url, title) -> SaverPicture(url, true, title) }
        SaverSource.SPACE -> SpaceArt.pictures.shuffled()
        else -> emptyList()
    }
    return chosen.ifEmpty {
        val ok = categoryKeys(movies)
        app.db.catalog().recentMovies(200).filter { (it.sourceId to it.categoryId) in ok }.mapNotNull { it.poster }.distinct().take(40).map { SaverPicture(it, true) }
    }
}

/** A picture decoded at the screen's size, so the preload and the drawn image share one cached bitmap. */
private fun saverRequest(ctx: Context, url: String): ImageRequest {
    val m = ctx.resources.displayMetrics
    return ImageRequest.Builder(ctx).data(url).size(m.widthPixels, m.heightPixels).build()
}

@Composable
private fun BoxScope.SaverSlides(pictures: List<SaverPicture>) {
    val ctx = LocalContext.current
    var i by remember(pictures) { mutableIntStateOf(0) }
    LaunchedEffect(pictures) {
        while (pictures.size > 1) {
            // The next picture decodes while this one shows.
            ctx.imageLoader.enqueue(saverRequest(ctx, pictures[(i + 1) % pictures.size].url))
            delay(SLIDE_MS.toLong())
            i = (i + 1) % pictures.size
        }
    }
    val still = rememberReducedMotion()
    val picture = pictures.getOrNull(i)
    Crossfade(picture, animationSpec = tween(1_200), label = "screensaver") { p ->
        if (p != null) {
            // Transform only: a slow drift and slight zoom, nothing blurred.
            val drift = remember(p) { Animatable(0f) }
            LaunchedEffect(p) { if (!still) drift.animateTo(1f, tween(SLIDE_MS + 1_200, easing = LinearEasing)) }
            AsyncImage(
                saverRequest(ctx, p.url), contentDescription = null, contentScale = if (p.crop) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val s = 1.08f + 0.04f * drift.value
                    scaleX = s; scaleY = s
                    translationX = -40.dp.toPx() * drift.value
                },
            )
        }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD9000000))))
    picture?.caption?.let {
        Text(
            it, fontSize = 16.sp, color = Color(0x99FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 64.dp, bottom = 52.dp).widthIn(max = 560.dp),
        )
    }
}

/** The clock and today's weather over the pictures, a few pixels elsewhere each minute so nothing stays put on the panel. */
@Composable
private fun BoxScope.SaverClock(weather: GlassWeather) {
    val now = rememberMinute()
    val shift = remember(now / 60_000) { Random(now / 60_000).let { (it.nextInt(-2, 3) * 6).dp to (it.nextInt(-2, 3) * 4).dp } }
    Column(Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 48.dp).offset(shift.first, shift.second)) {
        Text(time(now), fontSize = 64.sp, fontWeight = FontWeight.Light, color = Color.White)
        weather.forecast?.let { f ->
            Text("${WeatherParse.icon(f.code, f.isDay)}  ${f.temp}°  ${WeatherParse.condition(f.code)}", fontSize = 22.sp, color = Color(0xCCFFFFFF))
        }
        Text("Press any button to wake", fontSize = 14.sp, color = Color(0x99FFFFFF), modifier = Modifier.padding(top = 10.dp))
    }
}

/** "Clock only": a dim clock, the date and the weather on black, somewhere new each minute. */
@Composable
private fun BoxScope.ClockOnly(weather: GlassWeather) {
    val now = rememberMinute()
    val spot = remember(now / 60_000) { Random(now / 60_000).let { BiasAlignment(it.nextFloat() * 1.4f - 0.7f, it.nextFloat() * 1.2f - 0.6f) } }
    Column(Modifier.align(spot), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(time(now), fontSize = 96.sp, fontWeight = FontWeight.Light, color = Color(0x8CFFFFFF))
        Text(DateFormat.getDateInstance(DateFormat.FULL).format(Date(now)), fontSize = 22.sp, color = Color(0x66FFFFFF))
        weather.forecast?.let { f ->
            Text("${WeatherParse.icon(f.code, f.isDay)}  ${f.temp}°  ${WeatherParse.condition(f.code)}", fontSize = 20.sp, color = Color(0x66FFFFFF), modifier = Modifier.padding(top = 6.dp))
        }
    }
}
