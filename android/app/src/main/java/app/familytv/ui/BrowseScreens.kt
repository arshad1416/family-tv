package app.familytv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.data.CategoryEntity
import app.familytv.data.Kind
import app.familytv.data.SeriesEntity
import app.familytv.iptv.Streams
import app.familytv.iptv.XtreamParser
import app.familytv.portal.Profile
import app.familytv.sync.PlaybackStatus
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

val SeriesEntity.key get() = "$sourceId/$seriesId"

/** Which series has its episode list open, and which poster to refocus when it closes. */
class SeriesNav {
    var open by mutableStateOf<SeriesEntity?>(null)
    var back by mutableStateOf<String?>(null)
    var refocus = false
}

@Composable
fun rememberSeriesNav() = remember { SeriesNav() }

/** Shows the open series' episodes in place of [content]; Back returns to the poster that opened it. */
@Composable
fun SeriesNav.Host(profile: Profile, content: @Composable () -> Unit) {
    val entry = pageEntry()
    val o = open
    if (o != null) {
        EpisodeList(profile, o) { back = o.key; refocus = true; open = null }
    } else {
        content()
        LaunchedEffect(Unit) {
            if (!refocus) return@LaunchedEffect
            refocus = false
            delay(80)
            // The poster may not be composed any more (its row scrolled away); fall back to the page's entry.
            if (entry.attached == 0) { back = null; delay(80) }
            entry.focus()
            back = null
        }
    }
}

@Composable
fun EpisodeList(profile: Profile, s: SeriesEntity, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var episodes by remember(s) { mutableStateOf<List<XtreamParser.Episode>?>(null) }
    val first = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    LaunchedEffect(s) {
        val src = profile.sources.firstOrNull { it.id == s.sourceId }
        episodes = if (src == null) emptyList() else runCatching { ctx.app.catalog.episodes(s.sourceId, s.seriesId, src) }.getOrDefault(emptyList())
    }
    LaunchedEffect(episodes) { if (episodes != null) { delay(50); runCatching { first.requestFocus() } } }
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.width(220.dp)) {
            Box(Modifier.fillMaxWidth().height(330.dp).padding(bottom = 12.dp)) {
                if (s.poster != null) AsyncImage(s.poster, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            s.plot?.let { Text(it, fontSize = 13.sp, color = Tv.Muted, maxLines = 8, overflow = TextOverflow.Ellipsis) }
        }
        Column(Modifier.weight(1f)) {
            Text(s.name, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            val eps = episodes
            when {
                eps == null -> Text("Loading episodes…", color = Tv.Muted)
                eps.isEmpty() -> {
                    Text("No episodes found.", color = Tv.Muted)
                    Spacer(Modifier.height(12.dp))
                    TvButton("Back", onBack, Modifier.focusRequester(first))
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                    itemsIndexed(eps, key = { _, e -> e.id }) { i, e ->
                        FocusBox(
                            onClick = { scope.launch { Streams(ctx.app.db, profile).episode(s.sourceId, e)?.let { play(ctx, it, "S${e.season} E${e.episode} ${e.title}") } } },
                            modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                        ) { focused ->
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                                Text("S${e.season} E${e.episode}  ${e.title}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                e.plot?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = if (focused) Tv.Text else Tv.Muted) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Remembers the selected category, falling back to the first when it disappears. */
@Composable
private fun selectedCategory(cats: List<CategoryEntity>): Pair<CategoryEntity?, (CategoryEntity) -> Unit> {
    var cat by remember { mutableStateOf<CategoryEntity?>(null) }
    LaunchedEffect(cats) { if (cat == null || cat !in cats) cat = cats.firstOrNull() }
    return cat to { c: CategoryEntity -> cat = c }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CategoryColumn(cats: List<CategoryEntity>, selected: CategoryEntity?, onSelect: (CategoryEntity) -> Unit) {
    LazyColumn(Modifier.width(270.dp).fillMaxHeight().focusRestorer(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        items(cats, key = { "${it.sourceId}/${it.categoryId}" }) { c ->
            FocusBox(onClick = { onSelect(c) }, selected = c == selected, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) { focused ->
                Text(
                    c.name, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp,
                    color = if (c == selected && !focused) Tv.Accent else Tv.Text,
                    fontWeight = if (c == selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Category shortcuts across the top of Movies, Series and the Guide. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CategoryChips(cats: List<CategoryEntity>, selected: CategoryEntity?, onSelect: (CategoryEntity) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().focusRestorer(), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 40.dp)) {
        items(cats, key = { "${it.sourceId}/${it.categoryId}" }) { c ->
            FocusBox(onClick = { onSelect(c) }, selected = c == selected, color = Tv.Panel, shape = RoundedCornerShape(20.dp)) { focused ->
                Text(
                    c.name, Modifier.padding(horizontal = 18.dp, vertical = 9.dp), fontSize = 15.sp, maxLines = 1,
                    color = if (c == selected && !focused) Tv.Accent else Tv.Text,
                )
            }
        }
    }
}

@Composable
fun LiveScreen(vm: MainViewModel, profile: Profile) {
    val ctx = LocalContext.current
    val cats = categories(vm, profile, Kind.LIVE)
    val (cat, select) = selectedCategory(cats)
    val channels by remember(cat) {
        cat?.let { ctx.app.db.catalog().channels(it.sourceId, it.categoryId) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val progs = rememberProgrammes(channels)
    if (cats.isEmpty()) return EmptyState(PlaybackStatus.lastRefreshError?.let { "Couldn't load channels: $it. Open Settings and run a check." } ?: "No channels yet.")
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        CategoryColumn(cats, cat, select)
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            itemsIndexed(channels, key = { _, c -> c.streamId }) { i, c ->
                val p = c.epgId?.let { progs[it] }.orEmpty()
                val now = p.firstOrNull()
                val next = p.getOrNull(1)
                FocusBox(onClick = { playLive(ctx, c.sourceId, c.categoryId, i) }, modifier = Modifier.fillMaxWidth().height(80.dp).then(entryPoint(i == 0))) { focused ->
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.num}", Modifier.width(52.dp), fontSize = 17.sp, color = if (focused) Tv.Text else Tv.Muted)
                        Logo(c.logo, 54)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(now?.title ?: "No guide information", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (focused) Tv.Text else Tv.Muted)
                            if (now != null) ProgressLine(progress(now.start, now.stop), Modifier.padding(top = 6.dp).width(220.dp), fill = if (focused) Tv.Text else Tv.Accent)
                        }
                        if (next != null) Column(Modifier.width(240.dp).padding(start = 16.dp)) {
                            Text("Next · ${time(next.start)}", fontSize = 12.sp, color = if (focused) Tv.Text else Tv.Muted)
                            Text(next.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MoviesScreen(vm: MainViewModel, profile: Profile) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cats = categories(vm, profile, Kind.MOVIE)
    val (cat, select) = selectedCategory(cats)
    val movies by remember(cat) {
        cat?.let { ctx.app.db.catalog().movies(it.sourceId, it.categoryId) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    if (cats.isEmpty()) return EmptyState("No movies on this line.")
    Column {
        Text("Movies", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 14.dp))
        CategoryChips(cats, cat, select)
        Spacer(Modifier.height(18.dp))
        PosterGrid(movies.map { Triple(it.streamId, it.name, it.poster) }, back = null) { idx ->
            scope.launch { playMovieNow(ctx, profile, movies[idx]) }
        }
    }
}

@Composable
fun SeriesScreen(vm: MainViewModel, profile: Profile) {
    val ctx = LocalContext.current
    val cats = categories(vm, profile, Kind.SERIES)
    val (cat, select) = selectedCategory(cats)
    val nav = rememberSeriesNav()
    val grid = rememberLazyGridState()
    val series by remember(cat) {
        cat?.let { ctx.app.db.catalog().series(it.sourceId, it.categoryId) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    if (cats.isEmpty()) return EmptyState("No series on this line.")
    nav.Host(profile) {
        Column {
            Text("Series", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 14.dp))
            CategoryChips(cats, cat, select)
            Spacer(Modifier.height(18.dp))
            PosterGrid(series.map { Triple(it.key, it.name, it.poster) }, back = nav.back, state = grid) { idx -> nav.open = series[idx] }
        }
    }
}

@Composable
private fun PosterGrid(
    items: List<Triple<String, String, String?>>,
    back: String?,
    state: LazyGridState = rememberLazyGridState(),
    onClick: (Int) -> Unit,
) {
    val hasBack = back != null && items.any { it.first == back }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = state,
        contentPadding = PaddingValues(top = 6.dp, bottom = 32.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        itemsIndexed(items, key = { _, it -> it.first }) { i, (key, name, poster) ->
            PosterCard(name, poster, entryPoint(if (hasBack) key == back else i == 0), width = null) { onClick(i) }
        }
    }
}
