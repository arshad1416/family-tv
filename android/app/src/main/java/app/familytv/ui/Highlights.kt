package app.familytv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.data.CategoryEntity
import app.familytv.data.MovieEntity
import app.familytv.data.SeriesEntity
import app.familytv.trailer.MovieMeta
import app.familytv.trakt.ProgressRules
import app.familytv.user.ProgressEntity
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Locale

// Movies and Series open on Highlights (shelves of what to watch) under a spotlight that shows the
// focused poster's artwork and details, as Android TV's immersive list does. The provider's own
// categories stay one step right in the chips, each as the poster grid it always was.

private const val HIGHLIGHTS_CATEGORY = "\u0000highlights"

fun highlightsCategory(kind: String) = CategoryEntity(sourceId = "", kind = kind, categoryId = HIGHLIGHTS_CATEGORY, name = "Highlights", sort = -2, gen = 0)

val CategoryEntity.isHighlights get() = categoryId == HIGHLIGHTS_CATEGORY

/** Highlights and Favourites: entries the app adds before the provider's categories. */
val CategoryEntity.isVirtual get() = isFavourites || isHighlights

/** Ratings at or above this make the "Top rated" shelf (providers rate out of 10). */
const val TOP_RATING = 7.5

/** What the spotlight shows for the focused poster. */
data class Spot(val title: String, val meta: String?, val blurb: String?, val art: String?)

/**
 * TV only: the focused poster's title, details and artwork across the top of the page. The art
 * fades into the background on the left so the text stays readable.
 */
@Composable
fun Spotlight(spot: Spot?, fallback: String) {
    Box(Modifier.fillMaxWidth().height(170.dp).padding(start = FOCUS_ROOM)) {
        Crossfade(spot?.art, Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.5f), animationSpec = tween(250), label = "spotlight") { art ->
            if (art != null) Box(Modifier.fillMaxSize()) {
                // Posters are tall: show their upper middle, where titles and faces usually are.
                AsyncImage(art, contentDescription = null, contentScale = ContentScale.Crop, alignment = BiasAlignment(0f, -0.4f), modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Tv.Bg, 0.5f to Tv.Bg.copy(alpha = 0.6f), 1f to Tv.Bg.copy(alpha = 0.2f))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Tv.Bg)))
            }
        }
        Column(Modifier.fillMaxWidth(0.62f).align(Alignment.CenterStart)) {
            Text(spot?.title ?: fallback, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            spot?.meta?.let { Text(it, fontSize = 15.sp, color = Tv.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
            spot?.blurb?.let { Text(it, fontSize = 15.sp, lineHeight = 21.sp, color = Tv.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

/**
 * The spotlight for Movies. The provider's list has no plot, so once focus rests on a poster the
 * overview, genres and running time come from TMDB (when the portal set a key), never from the
 * provider, which throttles bursts of calls.
 */
@Composable
fun MovieSpotlight(movie: MovieEntity?, progress: ProgressEntity?) {
    val ctx = LocalContext.current
    var meta by remember(movie) { mutableStateOf<MovieMeta?>(null) }
    LaunchedEffect(movie) {
        if (movie == null) return@LaunchedEffect
        delay(450)
        meta = ctx.app.extras.movieMeta(movie)
    }
    Spotlight(movie?.let { movieSpot(it, progress, meta) }, "Movies")
}

@Composable
fun SeriesSpotlight(show: SeriesGroup?, newCount: Int) {
    Spotlight(show?.let { seriesSpot(it, newCount) }, "Series")
}

fun movieSpot(m: MovieEntity, p: ProgressEntity?, meta: MovieMeta?): Spot = Spot(
    title = m.name,
    meta = details(
        m.year?.toString(),
        ratingText(m.rating),
        meta?.genres?.take(2)?.joinToString(", ")?.takeIf { it.isNotEmpty() },
        meta?.runtimeMin?.let(::runtimeText),
        when {
            p?.watched == true -> "Watched"
            ProgressRules.bar(p) > 0f -> "${(ProgressRules.bar(p) * 100).toInt()}% watched"
            else -> null
        },
    ),
    blurb = meta?.overview,
    art = m.poster,
)

fun seriesSpot(g: SeriesGroup, newCount: Int): Spot = Spot(
    title = g.title,
    meta = details(
        g.copies.firstNotNullOfOrNull(SeriesEntity::year)?.toString(),
        ratingText(g.copies.firstNotNullOfOrNull { it.rating?.takeIf { r -> ratingText(r) != null } }),
        when {
            newCount > 1 -> "$newCount new episodes"
            newCount == 1 -> "New episode"
            else -> null
        },
    ),
    blurb = g.plot,
    art = g.poster,
)

private fun details(vararg parts: String?): String? = parts.filterNotNull().joinToString("  ·  ").takeIf { it.isNotEmpty() }

/** "★ 7.8" for a rating out of 10; null for none, zero or junk. */
fun ratingText(r: String?): String? = r?.trim()?.toDoubleOrNull()?.takeIf { it > 0 && it <= 10 }?.let { "★ " + String.format(Locale.US, "%.1f", it) }

fun runtimeText(min: Int): String = if (min < 60) "$min min" else "${min / 60} h" + (min % 60).let { if (it > 0) " $it min" else "" }
