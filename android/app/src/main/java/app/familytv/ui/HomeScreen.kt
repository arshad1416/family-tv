package app.familytv.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.data.CategoryEntity
import app.familytv.data.ChannelEntity
import app.familytv.data.Kind
import app.familytv.data.MovieEntity
import app.familytv.data.NowNext
import app.familytv.data.SeriesEntity
import app.familytv.iptv.Streams
import app.familytv.portal.DeviceConfig
import app.familytv.portal.Profile
import app.familytv.sync.PlaybackStatus
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val ADULT = Regex("(?i)\\b(xxx|adult|18\\+|for adults)\\b")
private val RAIL = 76.dp

enum class Page(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Guide("Guide", Icons.Filled.DateRange),
    Live("Live TV", Icons.Filled.PlayArrow),
    Movies("Movies", Icons.Filled.Star),
    Series("Series", Icons.AutoMirrored.Filled.List),
    Search("Search", Icons.Filled.Search),
    Settings("Settings", Icons.Filled.Settings),
}

/** Where focus lands the first time it moves from the rail into a page. */
class PageEntry {
    val requester = FocusRequester()
    var attached = 0
}

private val LocalPageEntry = staticCompositionLocalOf { PageEntry() }

/** Marks the composable this modifier goes on as the page's entry point (when [on]). */
@Composable
fun entryPoint(on: Boolean = true): Modifier {
    if (!on) return Modifier
    val e = LocalPageEntry.current
    DisposableEffect(e) { e.attached++; onDispose { e.attached-- } }
    return Modifier.focusRequester(e.requester)
}

/** Sends focus to the page's entry point, e.g. after closing an episode list. */
fun PageEntry.focus() { if (attached > 0) runCatching { requester.requestFocus() } }

@Composable
fun pageEntry(): PageEntry = LocalPageEntry.current

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(vm: MainViewModel, config: DeviceConfig) {
    val profile = config.profile ?: Profile()
    var page by rememberSaveable { mutableStateOf(Page.Home) }
    val rail = remember { Page.entries.associateWith { FocusRequester() } }
    var railOpen by remember { mutableStateOf(false) }
    var contentFocused by remember { mutableStateOf(false) }
    val entry = remember { PageEntry() }

    // Back inside a page goes to the rail; Back on the rail leaves the app as before.
    BackHandler(enabled = contentFocused) { runCatching { rail.getValue(page).requestFocus() } }
    LaunchedEffect(Unit) { runCatching { rail.getValue(page).requestFocus() } }

    Box(Modifier.fillMaxSize().background(Tv.Bg)) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = RAIL)
                .onFocusChanged { contentFocused = it.hasFocus }
                .focusRestorer { if (entry.attached > 0) entry.requester else FocusRequester.Default },
        ) {
            CompositionLocalProvider(LocalPageEntry provides entry) {
                Box(Modifier.fillMaxSize().padding(start = 28.dp, end = 36.dp, top = 24.dp)) {
                    when (page) {
                        Page.Home -> HomePage(vm, profile)
                        Page.Guide -> GuideScreen(vm, profile)
                        Page.Live -> LiveScreen(vm, profile)
                        Page.Movies -> MoviesScreen(vm, profile)
                        Page.Series -> SeriesScreen(vm, profile)
                        Page.Search -> SearchScreen(vm, profile)
                        Page.Settings -> SettingsScreen(vm, config)
                    }
                }
            }
        }
        if (railOpen) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF0000000), 0.6f to Color(0x99000000), 1f to Color(0x55000000))))
        Rail(page, rail, config.nickname, onOpen = { railOpen = it }, onSelect = { page = it })
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Rail(page: Page, requesters: Map<Page, FocusRequester>, nickname: String?, onOpen: (Boolean) -> Unit, onSelect: (Page) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf<Page?>(null) }
    val select by rememberUpdatedState(onSelect)
    val width by animateDpAsState(if (open) 240.dp else RAIL, label = "rail")
    // Follow focus like the old tabs did, but only once it rests, so passing over Guide doesn't load it.
    LaunchedEffect(hovered) { hovered?.let { delay(350); select(it) } }
    Column(
        Modifier
            .fillMaxHeight()
            .width(width)
            .background(Brush.horizontalGradient(listOf(Tv.Panel, if (open) Tv.Panel else Tv.Bg)))
            .onFocusChanged { open = it.hasFocus; onOpen(it.hasFocus); if (!it.hasFocus) hovered = null }
            .focusProperties { enter = { requesters.getValue(page) } }
            .focusGroup()
            .padding(horizontal = 12.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.height(40.dp).padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
            Text(if (open) "Family TV" else "TV", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Tv.Accent, maxLines = 1)
        }
        Spacer(Modifier.height(12.dp))
        Page.entries.forEach { p ->
            FocusBox(
                onClick = { select(p) },
                selected = p == page,
                color = Color.Transparent,
                onFocus = { hovered = p },
                modifier = Modifier.fillMaxWidth().height(48.dp).focusRequester(requesters.getValue(p)),
            ) { focused ->
                Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(p.icon, contentDescription = p.label, tint = if (p == page && !focused) Tv.Accent else Tv.Text, modifier = Modifier.size(24.dp))
                    if (open) {
                        Spacer(Modifier.width(16.dp))
                        Text(p.label, fontSize = 17.sp, maxLines = 1, fontWeight = if (p == page) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        if (open && nickname != null) {
            Spacer(Modifier.weight(1f))
            Text(nickname, color = Tv.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp))
        }
    }
}

// ---- Home ----

private data class Hero(val title: String, val subtitle: String, val detail: String? = null)

@Composable
private fun HomePage(vm: MainViewModel, profile: Profile) {
    val ctx = LocalContext.current
    val dao = ctx.app.db.catalog()
    val scope = rememberCoroutineScope()
    val hero = remember { mutableStateOf<Hero?>(null) }
    val nav = rememberSeriesNav()

    val liveCat = categories(vm, profile, Kind.LIVE).firstOrNull()
    val channels by remember(liveCat) { liveCat?.let { dao.channels(it.sourceId, it.categoryId) } ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val onNow = remember(channels) { channels.take(30) }
    val progs = rememberProgrammes(onNow)

    val movieCats = categories(vm, profile, Kind.MOVIE)
    val movieKeys = remember(movieCats) { movieCats.map { it.sourceId to it.categoryId }.toSet() }
    var recent by remember { mutableStateOf(emptyList<MovieEntity>()) }
    LaunchedEffect(movieKeys) { recent = dao.recentMovies(300).filter { (it.sourceId to it.categoryId) in movieKeys }.take(30) }
    val seriesCat = categories(vm, profile, Kind.SERIES).firstOrNull()
    val series by remember(seriesCat) { seriesCat?.let { dao.series(it.sourceId, it.categoryId) } ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())

    if (onNow.isEmpty() && recent.isEmpty() && series.isEmpty()) {
        return EmptyState(PlaybackStatus.lastRefreshError?.let { "Couldn't load channels: $it. Open Settings and run a check." } ?: "Nothing here yet.")
    }
    val firstRow = when { nav.back != null -> -1; onNow.isNotEmpty() -> 0; recent.isNotEmpty() -> 1; else -> 2 }
    nav.Host(profile) {
        Column {
            HeroHeader(hero)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                if (onNow.isNotEmpty()) item("now") {
                    Shelf("On now") {
                        itemsIndexed(onNow, key = { _, c -> c.streamId }) { i, c ->
                            val p = c.epgId?.let { progs[it] }.orEmpty()
                            ChannelCard(c, p.firstOrNull(), entryPoint(firstRow == 0 && i == 0),
                                onFocus = { hero.value = Hero(p.firstOrNull()?.title ?: c.name, "${c.num}  ${c.name}", p.firstOrNull()?.let { "${time(it.start)} – ${time(it.stop)}" + (p.getOrNull(1)?.let { n -> "   ·   Next: ${n.title}" } ?: "") }) },
                            ) { playLive(ctx, c.sourceId, c.categoryId, i) }
                        }
                    }
                }
                if (recent.isNotEmpty()) item("movies") {
                    Shelf("Recently added movies") {
                        itemsIndexed(recent, key = { _, m -> "${m.sourceId}/${m.streamId}" }) { i, m ->
                            PosterCard(m.name, m.poster, entryPoint(firstRow == 1 && i == 0), onFocus = { hero.value = Hero(m.name, "Movie", m.rating?.takeIf { it.isNotBlank() && it != "0" }?.let { "Rating $it" }) }) {
                                scope.launch { playMovieNow(ctx, profile, m) }
                            }
                        }
                    }
                }
                if (series.isNotEmpty()) item("series") {
                    Shelf("Series") {
                        itemsIndexed(series.take(30), key = { _, s -> "${s.sourceId}/${s.seriesId}" }) { i, s ->
                            PosterCard(s.name, s.poster, entryPoint(if (firstRow < 0) nav.back == s.key else firstRow == 2 && i == 0), onFocus = { hero.value = Hero(s.name, "Series", s.plot) }) { nav.open = s }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroHeader(hero: MutableState<Hero?>) {
    val h = hero.value
    Column(Modifier.fillMaxWidth().height(132.dp)) {
        Text(h?.title ?: "Welcome", fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(h?.subtitle ?: "Pick something to watch", fontSize = 16.sp, color = Tv.Accent, maxLines = 1)
        h?.detail?.let { Text(it, fontSize = 15.sp, color = Tv.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Shelf(title: String, content: LazyListScope.() -> Unit) {
    Column {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
        // focusRestorer: coming back up/down into a shelf returns to the card you left.
        LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = 40.dp), content = content)
    }
}

@Composable
fun ChannelCard(c: ChannelEntity, now: NowNext?, modifier: Modifier = Modifier, onFocus: () -> Unit = {}, onClick: () -> Unit) {
    FocusBox(onClick = onClick, onFocus = onFocus, modifier = modifier.width(280.dp).height(112.dp), scale = 1.04f) { focused ->
        Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(c.logo, 64)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${c.num}  ${c.name}", fontSize = 13.sp, color = if (focused) Tv.Text else Tv.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(now?.title ?: c.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (now != null) {
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(progress(now.start, now.stop), fill = if (focused) Tv.Text else Tv.Accent)
                }
            }
        }
    }
}

@Composable
fun PosterCard(name: String, poster: String?, modifier: Modifier = Modifier, width: Dp? = 140.dp, onFocus: () -> Unit = {}, onClick: () -> Unit) {
    Column(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()) {
        FocusBox(onClick = onClick, onFocus = onFocus, outline = true, scale = 1.06f, color = Tv.Raised, modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f), contentAlignment = Alignment.Center) {
            Text(name, Modifier.padding(8.dp), fontSize = 13.sp, maxLines = 4, color = Tv.Muted)
            if (poster != null) AsyncImage(poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = Tv.Muted, modifier = Modifier.padding(top = 6.dp))
    }
}

// ---- shared helpers ----

/** Categories for [kind], minus hidden ones and (until unlocked) adult ones. */
@Composable
fun categories(vm: MainViewModel, profile: Profile, kind: String): List<CategoryEntity> {
    val ctx = LocalContext.current
    val unlocked by vm.adultUnlocked.collectAsState()
    val all by remember(kind) { ctx.app.db.catalog().categories(kind) }.collectAsState(initial = emptyList())
    val hidden = profile.hiddenCategories.orEmpty().map { it.lowercase() }.toSet()
    return remember(all, unlocked, hidden) {
        all.filter { c ->
            c.name.lowercase() !in hidden && (unlocked || profile.parentalPin.isNullOrEmpty() || !ADULT.containsMatchIn(c.name))
        }
    }
}

/** Programmes overlapping [from, from + hours) for these channels, grouped by EPG id. */
@Composable
fun rememberProgrammes(channels: List<ChannelEntity>, from: Long = System.currentTimeMillis(), hours: Int = 3): Map<String, List<NowNext>> {
    val ctx = LocalContext.current
    val ids = remember(channels) { channels.mapNotNull { it.epgId }.distinct() }
    var state by remember { mutableStateOf(emptyMap<String, List<NowNext>>()) }
    LaunchedEffect(ids, from) {
        // SQLite caps bound parameters; query in chunks.
        state = ids.chunked(500).flatMap { ctx.app.db.catalog().programmes(it, from, from + hours * 3_600_000L) }.groupBy { it.epgId }
    }
    return state
}

@Composable
fun EmptyState(text: String) = Centered { Text(text, fontSize = 18.sp, color = Tv.Muted) }

@Composable
fun Logo(url: String?, sizeDp: Int) {
    Box(Modifier.size(sizeDp.dp).background(Color(0xFF0A1220), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
    }
}

fun time(t: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))

fun playLive(ctx: Context, sourceId: String, categoryId: String, index: Int, streamId: String? = null) {
    ctx.startActivity(
        Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_SOURCE, sourceId)
            .putExtra(PlayerActivity.EXTRA_CATEGORY, categoryId)
            .putExtra(PlayerActivity.EXTRA_INDEX, index)
            .putExtra(PlayerActivity.EXTRA_STREAM, streamId),
    )
}

fun play(ctx: Context, url: String, title: String) {
    ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URL, url).putExtra(PlayerActivity.EXTRA_TITLE, title))
}

suspend fun playMovieNow(ctx: Context, profile: Profile, m: MovieEntity) {
    Streams(ctx.app.db, profile).movie(m)?.let { play(ctx, it, m.name) }
}
