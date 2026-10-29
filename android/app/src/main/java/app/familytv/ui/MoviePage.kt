package app.familytv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.data.Kind
import app.familytv.iptv.EpisodeGuide
import app.familytv.iptv.SeriesTitles
import app.familytv.iptv.XtreamParser
import app.familytv.iptv.language
import app.familytv.iptv.releaseYear
import app.familytv.portal.Profile
import app.familytv.trakt.PlaybackTracker
import app.familytv.trakt.ProgressRules
import app.familytv.usage.TrackScreen
import app.familytv.usage.Usage
import app.familytv.user.MovieLanguages
import app.familytv.user.ProgressEntity
import app.familytv.user.ProgressKey
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One film before it plays, like the series page: poster, title, year, rating, running time and
 * synopsis from the provider's movie info, a 4K/HDR note, Play (or "Resume · 42 min left" and
 * Start over, or "Play again" once watched), Favourite and Trailer. A failed info call still shows the poster and title with Play.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MoviePage(profile: Profile, group: MovieGroup, visible: Set<Pair<String, String>>, onBack: () -> Unit) {
    TrackScreen(Usage.Screen.MOVIE_PAGE)
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    val form = LocalFormFactor.current
    val phone = form == FormFactor.Phone
    BackHandler(onBack = onBack)

    var copies by remember(group.key) { mutableStateOf(group.copies) }
    LaunchedEffect(group.key, visible) { copies = app.db.allCopies(group, visible) }
    val progress = rememberProgress()
    var chosenKey by rememberSaveable(group.key) { mutableStateOf<String?>(null) }
    val m = copies.firstOrNull { it.key == chosenKey } ?: remember(copies, progress) { pickCopy(ctx, copies, progress) }
    val labels = remember(copies) { SeriesTitles.variantLabels(copies.map { it.language to SeriesTitles.parse(it.name).tags }) }

    var info by remember(m.key) { mutableStateOf<XtreamParser.VodInfo?>(null) }
    LaunchedEffect(m.key) {
        val src = profile.sources.firstOrNull { it.id == m.sourceId && it.type == "xtream" } ?: return@LaunchedEffect
        info = runCatching { app.catalog.vodInfo(m.sourceId, m.streamId, src) }.getOrNull()
    }

    val all = remember(group.key, copies) { MovieGroup(group.key, copies) }
    val favKeys = rememberFavouriteKeys(rememberFavourites())
    val favourite = all.favourite(favKeys)
    val p = progress[ProgressKey.movie(m.sourceId, m.streamId)]
    val watched = p?.watched == true
    val resumeAt = movieResumeAt(p)
    val cover = m.poster ?: group.poster ?: info?.cover
    val meta = listOfNotNull(
        (m.releaseYear ?: info?.year)?.toString(),
        ratingText(m.rating?.takeIf { it.isNotBlank() && it.toDoubleOrNull() != 0.0 } ?: info?.rating),
        EpisodeGuide.runtime(info?.durationSecs),
        SeriesTitles.languageName(m.language).takeIf { copies.size > 1 },
        SeriesTitles.qualityBadge(listOf(m.name)),
    )
    val playFirst = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (!form.touch) { delay(60); runCatching { playFirst.requestFocus() } } }

    val list: @Composable (Modifier) -> Unit = { mod ->
        LazyColumn(mod, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (form.touch) item("back") { TvButton("‹  Back", onBack) }
            item("title") {
                Row(verticalAlignment = Alignment.Top) {
                    if (phone) {
                        Poster(cover, Modifier.width(96.dp))
                        Spacer(Modifier.width(14.dp))
                    }
                    Column {
                        Text(group.title, fontSize = if (phone) 22.sp else 28.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), fontSize = 14.sp, color = Tv.Muted, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            item("actions") {
                LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item("play") {
                        TvButton(
                            moviePlayLabel(p),
                            { scope.launch { playMovieNow(ctx, profile, m, fromStart = watched) } },
                            Modifier.focusRequester(playFirst),
                        )
                    }
                    if (resumeAt != null) item("over") { TvButton("↺  Start over", { scope.launch { playMovieNow(ctx, profile, m, fromStart = true) } }) }
                    item("fav") {
                        TvButton(if (favourite) "♥  In Favourites" else "♡  Add to Favourites", { toggleMovieFavourite(ctx, all, emptySet(), favKeys, m) })
                    }
                    item("trailer") { TrailerButton(movieTrailerAction(profile) { m }) }
                }
            }
            info?.plot?.let { plot -> item("about") { ExpandableText(plot, collapsedLines = if (phone) 5 else 4) } }
            if (copies.size > 1) item("lang") {
                Column {
                    Text("Language", fontSize = 13.sp, color = Tv.Muted, modifier = Modifier.padding(bottom = 6.dp))
                    ChoiceChips(labels, copies.indexOf(m)) { i ->
                        chosenKey = copies[i].key
                        MovieLanguages(ctx).remember(copies, copies[i].language)
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (phone) list(Modifier.fillMaxSize())
        else Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Poster(cover, Modifier.width(if (form == FormFactor.Tablet) 220.dp else 260.dp))
            list(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Poster(url: String?, modifier: Modifier) {
    Box(modifier.aspectRatio(2f / 3f).clip(Tv.Shape).background(Tv.Raised)) {
        if (url != null) AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** Where Play picks a film up, or null for the start: watched (on any copy) starts over, as the tile's check says, even with a later spot saved. */
internal fun movieResumeAt(p: ProgressEntity?): Long? =
    if (p?.watched != true && ProgressRules.started(p)) ProgressRules.resumeAt(p, p?.durationMs ?: 0) else null

/** Play's label on a film's page: "Play again" once watched, "Resume · 42 min left" from a saved spot, else Play. */
internal fun moviePlayLabel(p: ProgressEntity?): String {
    if (p?.watched == true) return "▶  Play again"
    val at = movieResumeAt(p) ?: return "▶  Play"
    return ProgressRules.timeLeft(p)?.let { "▶  Resume · $it" } ?: "▶  Resume from ${PlaybackTracker.clock(at)}"
}
