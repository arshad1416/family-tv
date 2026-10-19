package app.familytv.ui

import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.familytv.data.Names
import app.familytv.data.NowNext
import app.familytv.data.displayName
import app.familytv.iptv.Streams
import app.familytv.portal.DeviceConfig
import app.familytv.portal.Profile
import app.familytv.sync.NewEpisodes
import app.familytv.sync.PlaybackStatus
import app.familytv.trakt.PlayItem
import app.familytv.usage.TrackScreen
import app.familytv.usage.Usage
import app.familytv.user.Favourites
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Adult category names. "Adult Swim" is a cartoon channel, not adult content. */
private val ADULT = Regex("(?i)\\b(xxx|adults?|18\\+|for adults)\\b(?![\\s_()-]*swim)")
private val RAIL = 76.dp

/** One focus style for artwork: a 1.1x zoom plus the outline (Android TV's guideline). */
const val FOCUS_SCALE = 1.1f
/** Room around a row of artwork for a focused card's zoom. */
val FOCUS_ROOM = 12.dp

enum class Page(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Categories("Categories", Icons.Filled.Menu),
    Live("Live TV", Icons.Filled.PlayArrow),
    Movies("Movies", Icons.Filled.Star),
    Series("Series", Icons.AutoMirrored.Filled.List),
    Search("Search", Icons.Filled.Search),
    Settings("Settings", Icons.Outlined.Settings),
    ;

    /** This page in the usage stats. */
    val usage: Usage.Screen get() = when (this) {
        Home -> Usage.Screen.HOME
        Categories -> Usage.Screen.CATEGORIES
        Live -> Usage.Screen.LIVE
        Movies -> Usage.Screen.MOVIES
        Series -> Usage.Screen.SERIES
        Search -> Usage.Screen.SEARCH
        Settings -> Usage.Screen.SETTINGS
    }
}

/** Where focus lands the first time it moves from the rail into a page. */
class PageEntry {
    val requester = FocusRequester()
    var attached = 0
}

internal val LocalPageEntry = staticCompositionLocalOf { PageEntry() }

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

/**
 * Coming back from the rail returns to the item you left, or the page's entry point.
 * Needs Compose 1.8+: on 1.7 this restorer also stopped D-pad Up from a grid's top row
 * reaching the category chips (TvFocusTest covers both).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.restoresFocus(entry: PageEntry): Modifier =
    focusRestorer { if (entry.attached > 0) entry.requester else FocusRequester.Default }

@Composable
fun pageEntry(): PageEntry = LocalPageEntry.current

@Composable
private fun PageBody(page: Page, vm: MainViewModel, config: DeviceConfig, profile: Profile, onNavigate: (Page) -> Unit) {
    // Each page keeps its saveable state (category, scroll, last card) while another is open.
    val pages = rememberSaveableStateHolder()
    pages.SaveableStateProvider(page.name) { PageContent(page, vm, config, profile, onNavigate) }
}

@Composable
private fun PageContent(page: Page, vm: MainViewModel, config: DeviceConfig, profile: Profile, onNavigate: (Page) -> Unit) {
    TrackScreen(page.usage)
    when (page) {
        Page.Home -> HomePage(vm, profile, onNavigate)
        Page.Categories -> CategoriesScreen(vm, profile)
        // Live TV is the guide grid on every device.
        Page.Live -> GuideScreen(vm, profile)
        Page.Movies -> MoviesScreen(vm, profile)
        Page.Series -> SeriesScreen(vm, profile)
        Page.Search -> SearchScreen(vm, profile)
        Page.Settings -> SettingsScreen(vm, config)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(vm: MainViewModel, config: DeviceConfig) {
    // Phones and tablets get the touch layout; TV keeps the D-pad rail below.
    val form = LocalFormFactor.current
    if (form.touch) return TouchHome(vm, config, form)
    val profile = config.profile ?: Profile()
    var page by rememberSaveable { mutableStateOf(Page.Home) }
    val rail = remember { Page.entries.associateWith { FocusRequester() } }
    var railOpen by remember { mutableStateOf(false) }
    var contentFocused by remember { mutableStateOf(false) }
    val entry = remember { PageEntry() }

    // Back inside a page goes to the rail; Back on the rail leaves the app as before.
    BackHandler(enabled = contentFocused) { Usage.log(Usage.Action.BACK); runCatching { rail.getValue(page).requestFocus() } }
    LaunchedEffect(Unit) { runCatching { rail.getValue(page).requestFocus() } }

    Box(Modifier.fillMaxSize().background(Tv.Bg)) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = RAIL)
                .onFocusChanged { contentFocused = it.hasFocus }
                .restoresFocus(entry),
        ) {
            CompositionLocalProvider(LocalPageEntry provides entry) {
                // Inside the 5% overscan safe zone (48dp across, 27dp down on a 960x540dp screen).
                Box(Modifier.fillMaxSize().padding(start = 28.dp, end = 48.dp, top = 28.dp)) {
                    PageBody(page, vm, config, profile) { page = it }
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
    // Follow focus like the old tabs did, but only once it rests, so passing over Live TV doesn't load the guide.
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

// ---- Phone and tablet ----

/** The bottom tabs on a phone and the side rail on a tablet; Settings lives in the header. */
private val TOUCH_TABS = listOf(Page.Home, Page.Categories, Page.Live, Page.Movies, Page.Series, Page.Search)
private val TOUCH_RAIL = 88.dp

/**
 * Touch layout: a header (context line, big title, Search and Settings icons) over the page,
 * with bottom tabs on a phone or a side rail on a tablet. Back from any page returns Home.
 */
@Composable
private fun TouchHome(vm: MainViewModel, config: DeviceConfig, form: FormFactor) {
    val profile = config.profile ?: Profile()
    var page by rememberSaveable { mutableStateOf(Page.Home) }
    val entry = remember { PageEntry() }
    BackHandler(enabled = page != Page.Home) { page = Page.Home }
    val phone = form == FormFactor.Phone
    val body: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.fillMaxHeight()) {
            TouchHeader(page, config.nickname, Modifier.padding(horizontal = if (phone) 16.dp else 24.dp)) { page = it }
            CompositionLocalProvider(LocalPageEntry provides entry) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (phone) 16.dp else 24.dp)) {
                    PageBody(page, vm, config, profile) { page = it }
                }
            }
        }
    }
    if (phone) {
        Column(Modifier.fillMaxSize().background(Tv.Bg)) {
            body(Modifier.weight(1f))
            BottomTabs(page) { page = it }
        }
    } else {
        Row(Modifier.fillMaxSize().background(Tv.Bg)) {
            TouchRail(page) { page = it }
            body(Modifier.weight(1f))
        }
    }
}

@Composable
private fun TouchHeader(page: Page, nickname: String?, modifier: Modifier, onSelect: (Page) -> Unit) {
    val context = if (page == Page.Live) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())
    } else {
        nickname ?: "Family TV"
    }
    Row(modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(context, fontSize = 13.sp, color = Tv.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(page.label, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        HeaderIcon(Page.Settings, page == Page.Settings) { onSelect(Page.Settings) }
    }
}

@Composable
private fun HeaderIcon(p: Page, selected: Boolean, onClick: () -> Unit) {
    FocusBox(onClick = onClick, color = Color.Transparent, selected = selected, shape = RoundedCornerShape(24.dp), modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) { focused ->
        Icon(p.icon, contentDescription = p.label, tint = if (selected && !focused) Tv.Accent else Tv.Text, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun BottomTabs(page: Page, onSelect: (Page) -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).background(Tv.Panel)) {
        TOUCH_TABS.forEach { p -> TabItem(p, p == page, Modifier.weight(1f).fillMaxHeight()) { onSelect(p) } }
    }
}

@Composable
private fun TouchRail(page: Page, onSelect: (Page) -> Unit) {
    Column(
        Modifier.width(TOUCH_RAIL).fillMaxHeight().background(Tv.Panel).padding(horizontal = 8.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("TV", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Tv.Accent, modifier = Modifier.padding(bottom = 12.dp))
        TOUCH_TABS.forEach { p -> TabItem(p, p == page, Modifier.fillMaxWidth().height(64.dp)) { onSelect(p) } }
    }
}

@Composable
private fun TabItem(p: Page, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, color = Color.Transparent, modifier = modifier, contentAlignment = Alignment.Center) { focused ->
        val tint = if (selected && !focused) Tv.Accent else if (selected || focused) Tv.Text else Tv.Muted
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(p.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(4.dp))
            Text(p.label, fontSize = 11.sp, color = tint, maxLines = 1, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

private class HeroItem(val title: String, val blurb: String, val art: String?, val poster: Boolean, val play: () -> Unit)

/** Full-width swipeable hero: artwork, title, one line, Watch now, page dots. */
@Composable
private fun HeroCarousel(items: List<HeroItem>) {
    val pager = rememberPagerState { items.size }
    val tall = if (LocalFormFactor.current == FormFactor.Phone) 220.dp else 280.dp
    Column {
        HorizontalPager(pager, Modifier.fillMaxWidth().height(tall), pageSpacing = 12.dp) { i ->
            val h = items[i]
            Box(Modifier.fillMaxSize().clip(Tv.Shape).background(Tv.Panel)) {
                if (h.art != null) {
                    if (h.poster) AsyncImage(h.art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else AsyncImage(h.art, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.align(Alignment.TopEnd).padding(20.dp).size(96.dp))
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color(0x33060A14), 1f to Color(0xF2060A14))))
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(h.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(h.blurb, fontSize = 14.sp, color = Tv.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    TvButton("Watch now", h.play)
                }
            }
        }
        if (items.size > 1) Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
            repeat(items.size) { i ->
                Box(Modifier.padding(horizontal = 3.dp).size(if (i == pager.currentPage) 8.dp else 6.dp).clip(RoundedCornerShape(4.dp)).background(if (i == pager.currentPage) Tv.Accent else Tv.Line))
            }
        }
    }
}

// ---- Home ----

private data class Hero(val title: String, val subtitle: String, val detail: String? = null)

@Composable
private fun HomePage(vm: MainViewModel, profile: Profile, onNavigate: (Page) -> Unit) {
    val ctx = LocalContext.current
    val menus = LocalItemMenus.current
    val touch = LocalFormFactor.current.touch
    val dao = ctx.app.db.catalog()
    val scope = rememberCoroutineScope()
    val hero = remember { mutableStateOf<Hero?>(null) }
    val nav = rememberSeriesNav()

    val liveCats = categories(profile, Kind.LIVE)
    val liveCat = liveCats.firstOrNull()
    val channels by remember(liveCat) { liveCat?.let { dao.channels(it.sourceId, it.categoryId) } ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val onNow = remember(channels) { channels.take(30) }
    val progs = rememberProgrammes(onNow)

    val movieCats = categories(profile, Kind.MOVIE)
    val movieKeys = remember(movieCats) { categoryKeys(movieCats) }
    var recent by remember { mutableStateOf(emptyList<MovieGroup>()) }
    LaunchedEffect(movieKeys) { recent = groupMovies(dao.recentMovies(300).filter { (it.sourceId to it.categoryId) in movieKeys }).take(30) }
    val seriesCats = categories(profile, Kind.SERIES)
    val seriesCat = seriesCats.firstOrNull()
    val seriesRows by remember(seriesCat) { seriesCat?.let { dao.series(it.sourceId, it.categoryId) } ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val series = remember(seriesRows) { groupSeries(seriesRows) }

    // Favourites of every kind, newest first, as one row.
    val favs = rememberFavourites()
    val favKeys = rememberFavouriteKeys(favs)
    val favChannels = rememberFavouriteChannels(favs, liveCats)
    val favMovies = rememberFavouriteMovies(favs, movieCats)
    val favSeries = rememberFavouriteSeries(favs, seriesCats)
    val showFavs = ShowFavourites(rememberFavouriteShowRows(favs))
    val favRow: List<Pair<String, Any>> = remember(favs, favChannels, favMovies, favSeries) {
        val byKey = HashMap<String, Pair<String, Any>>()
        favChannels.forEach { val k = Favourites.key(Kind.LIVE, it.sourceId, it.streamId); byKey[k] = k to it }
        // Copies of one show or film in several languages are one entry.
        groupMovies(favMovies).forEach { g -> g.copies.forEach { byKey[Favourites.key(Kind.MOVIE, it.sourceId, it.streamId)] = "movie/${g.key}" to g } }
        groupSeries(favSeries).forEach { g -> g.copies.forEach { byKey[Favourites.key(Kind.SERIES, it.sourceId, it.seriesId)] = "series/${g.key}" to g } }
        favs.mapNotNull { f -> byKey[Favourites.key(f.kind, f.sourceId, f.itemId)] }.distinctBy { it.first }
    }
    val progress = rememberProgress()
    var openedFromFavourites by remember { mutableStateOf(false) }
    val newKeys = rememberNewEpisodeKeys()
    val newShows = rememberNewEpisodeShows(newKeys, seriesCats)
    // Android 13+: ask once to notify about new episodes, when there is a favourite show to watch.
    val activity = ctx as? android.app.Activity
    LaunchedEffect(favSeries.isNotEmpty()) { if (favSeries.isNotEmpty() && activity != null) NewEpisodes.askOnce(activity) }

    if (onNow.isEmpty() && recent.isEmpty() && series.isEmpty() && favRow.isEmpty()) {
        return EmptyState(PlaybackStatus.lastRefreshError?.let { "Couldn't load channels: $it. Open Settings and run a check." } ?: "Nothing here yet.")
    }
    val firstRow = when { nav.back != null -> -1; favRow.isNotEmpty() -> 3; onNow.isNotEmpty() -> 0; recent.isNotEmpty() -> 1; else -> 2 }
    // Touch: a swipeable hero of what's on and what's new, since there's no focus to follow.
    val heroes = remember(touch, onNow, progs, recent, progress) {
        if (!touch) emptyList() else buildList {
            onNow.take(3).forEachIndexed { i, c ->
                val p = c.epgId?.let { progs[it] }?.firstOrNull()
                add(HeroItem(p?.title ?: c.displayName, "Live now  ·  ${c.num}  ${c.displayName}", c.logo, poster = false) { playLive(ctx, c.sourceId, c.categoryId, i) })
            }
            recent.take(3).forEach { g ->
                add(HeroItem(g.title, "New movie", g.poster, poster = true) { scope.launch { playMovie(ctx, profile, g, movieKeys, progress) } })
            }
        }
    }
    fun viewAll(p: Page): (() -> Unit)? = if (touch) ({ onNavigate(p) }) else null
    nav.Host(vm, profile) {
        Column {
            if (!touch) HeroHeader(hero)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                if (heroes.isNotEmpty()) item("hero") { HeroCarousel(heroes) }
                if (newShows.isNotEmpty()) item("new-episodes") {
                    Shelf("New episodes") {
                        itemsIndexed(newShows, key = { _, g -> "new/${g.key}" }) { _, g ->
                            PosterCard(
                                g.title, g.poster, favourite = true, newBadge = true, newCount = g.newCount(newKeys),
                                onFocus = { hero.value = Hero(g.title, "New episodes", g.plot) },
                            ) { nav.open = g }
                        }
                    }
                }
                if (favRow.isNotEmpty()) item("favourites") {
                    Shelf("Favourites") {
                        itemsIndexed(favRow, key = { _, (k, _) -> k }) { i, (_, x) ->
                            val entry = if (firstRow < 0) openedFromFavourites && nav.back != null && nav.back == ((x as? SeriesGroup)?.key ?: (x as? MovieGroup)?.key) else firstRow == 3 && i == 0
                            when (x) {
                                is ChannelEntity -> PosterCard(
                                    x.displayName, x.logo, entryPoint(entry), logo = true, favourite = true,
                                    onFavourite = { toggleFavourite(ctx, Kind.LIVE, x.sourceId, x.streamId, x.name) },
                                    onFocus = { hero.value = Hero(x.displayName, "Favourite channel  ·  ${x.num}", null) },
                                ) { playLive(ctx, x.sourceId, x.categoryId, 0, x.streamId) }
                                is MovieGroup -> MovieCard(
                                    x, entryPoint(entry), profile = profile, visible = movieKeys, favKeys = favKeys, progress = progress,
                                    onOpen = { openedFromFavourites = true },
                                    onFocus = { hero.value = Hero(x.title, "Favourite movie", null) },
                                )
                                is SeriesGroup -> PosterCard(
                                    x.title, x.poster, entryPoint(entry), favourite = true, newBadge = x.hasNew(newKeys), newCount = x.newCount(newKeys),
                                    onFavourite = { toggleShowFavourite(ctx, x, true) },
                                    onFocus = { hero.value = Hero(x.title, "Favourite series", x.plot) },
                                ) { openedFromFavourites = true; nav.open = x }
                            }
                        }
                    }
                }
                if (onNow.isNotEmpty()) item("now") {
                    Shelf("On now", viewAll(Page.Live)) {
                        itemsIndexed(onNow, key = { _, c -> c.streamId }) { i, c ->
                            val p = c.epgId?.let { progs[it] }.orEmpty()
                            ChannelCard(c, p.firstOrNull(), entryPoint(firstRow == 0 && i == 0),
                                favourite = Favourites.key(Kind.LIVE, c.sourceId, c.streamId) in favKeys,
                                onFavourite = { toggleFavourite(ctx, Kind.LIVE, c.sourceId, c.streamId, c.name) },
                                onFocus = { hero.value = Hero(p.firstOrNull()?.title ?: c.displayName, "${c.num}  ${c.displayName}", p.firstOrNull()?.let { "${time(it.start)} – ${time(it.stop)}" + (p.getOrNull(1)?.let { n -> "   ·   Next: ${n.title}" } ?: "") }) },
                            ) { playLive(ctx, c.sourceId, c.categoryId, i) }
                        }
                    }
                }
                if (recent.isNotEmpty()) item("movies") {
                    Shelf("Recently added movies", viewAll(Page.Movies)) {
                        itemsIndexed(recent, key = { _, g -> g.key }) { i, g ->
                            MovieCard(
                                g, entryPoint(if (firstRow < 0) !openedFromFavourites && nav.back == g.key else firstRow == 1 && i == 0),
                                profile = profile, visible = movieKeys, favKeys = favKeys, progress = progress,
                                onOpen = { openedFromFavourites = false },
                                onFocus = { hero.value = Hero(g.title, "Movie", g.first.rating?.takeIf { it.isNotBlank() && it != "0" }?.let { "Rating $it" }) },
                            )
                        }
                    }
                }
                if (series.isNotEmpty()) item("series") {
                    Shelf("Series", viewAll(Page.Series)) {
                        itemsIndexed(series.take(30), key = { _, s -> s.key }) { i, s ->
                            val on = showFavs.has(s)
                            PosterCard(
                                s.title, s.poster, entryPoint(if (firstRow < 0) !openedFromFavourites && nav.back == s.key else firstRow == 2 && i == 0),
                                favourite = on,
                                onFavourite = { toggleShowFavourite(ctx, s, on) },
                                onFocus = { hero.value = Hero(s.title, "Series", s.plot) },
                            ) { openedFromFavourites = false; nav.open = s }
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
    Column(Modifier.fillMaxWidth().height(132.dp).padding(start = FOCUS_ROOM)) {
        Text(h?.title ?: "Welcome", fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(h?.subtitle ?: "Pick something to watch", fontSize = 16.sp, color = Tv.Accent, maxLines = 1)
        h?.detail?.let { Text(it, fontSize = 15.sp, color = Tv.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Shelf(title: String, onViewAll: (() -> Unit)? = null, content: LazyListScope.() -> Unit) {
    Column {
        if (onViewAll == null) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = FOCUS_ROOM))
        } else {
            Row(Modifier.fillMaxWidth().padding(start = FOCUS_ROOM), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                FocusBox(onClick = onViewAll, color = Color.Transparent, modifier = Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) { focused ->
                    Text("View all", fontSize = 15.sp, color = if (focused) Tv.Text else Tv.Accent, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
        // focusRestorer: coming back up/down into a shelf returns to the card you left. The padding
        // leaves room for a focused card's zoom, which the row would otherwise clip.
        LazyRow(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(start = FOCUS_ROOM, end = 40.dp, top = FOCUS_ROOM, bottom = FOCUS_ROOM), content = content)
    }
}

@Composable
fun ChannelCard(
    c: ChannelEntity,
    now: NowNext?,
    modifier: Modifier = Modifier,
    favourite: Boolean = false,
    onFavourite: (() -> Unit)? = null,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    val heart = LocalFormFactor.current.touch && onFavourite != null
    FocusBox(onClick = onClick, onLongClick = onFavourite, onFocus = onFocus, modifier = modifier.width(280.dp).height(112.dp), scale = 1.04f) { focused ->
        Row(Modifier.fillMaxSize().padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = if (heart) 0.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(c.logo, 64)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${c.num}  ${c.displayName}", fontSize = 13.sp, color = if (focused) Tv.Text else Tv.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(now?.title ?: c.displayName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (now != null) {
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(progress(now.start, now.stop), fill = if (focused) Tv.Text else Tv.Accent)
                }
            }
            if (heart) FavouriteButton(favourite, onFavourite!!)
            else MarkBadge(favourite = favourite, size = 20.dp)
        }
    }
}

/**
 * Artwork tile. [onFavourite] adds hold-OK or Menu (TV) or a heart button (touch) to toggle the
 * favourite; [onMenu], when set, takes over hold-OK, Menu and long press (an [ItemMenu]);
 * [newBadge] marks a show with new episodes ([newCount] of them, when known); [watched] and [progress] draw the tick and the resume line. [logo] fits a channel
 * logo instead of cropping a poster. [onTrailer] adds a Trailer button (over the poster on touch,
 * beside the name on TV).
 */
@Composable
fun PosterCard(
    name: String,
    poster: String?,
    modifier: Modifier = Modifier,
    width: Dp? = 140.dp,
    logo: Boolean = false,
    favourite: Boolean = false,
    onFavourite: (() -> Unit)? = null,
    watched: Boolean = false,
    progress: Float = 0f,
    onTrailer: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    newBadge: Boolean = false,
    newCount: Int = 0,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    val touch = LocalFormFactor.current.touch
    val heart = touch && onFavourite != null
    Column(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()) {
        FocusBox(onClick = onClick, onLongClick = onMenu ?: onFavourite, onFocus = onFocus, outline = true, scale = FOCUS_SCALE, color = Tv.Raised, modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f), contentAlignment = Alignment.Center) {
            Text(name, Modifier.padding(8.dp), fontSize = 13.sp, maxLines = 4, color = Tv.Muted)
            if (poster != null) {
                if (logo) AsyncImage(poster, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(20.dp))
                else AsyncImage(poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            MarkBadge(Modifier.align(Alignment.TopStart), watched = watched)
            if (newBadge) NewBadge(Modifier.align(Alignment.BottomEnd), newCount)
            if (heart) FavouriteButton(favourite, onFavourite!!, Modifier.align(Alignment.TopEnd))
            else MarkBadge(Modifier.align(Alignment.TopEnd), favourite = favourite)
            if (progress > 0f) ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(8.dp), height = 4.dp)
            if (touch && onTrailer != null) TrailerButton(onTrailer, Modifier.align(Alignment.BottomStart), compact = true)
        }
        if (!touch && onTrailer != null) {
            Row(Modifier.padding(top = 4.dp).height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = Tv.Muted, modifier = Modifier.weight(1f))
                TrailerButton(onTrailer, compact = true)
            }
        } else {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = Tv.Muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ---- shared helpers ----

/**
 * Categories for [kind], minus hidden and adult ones (those live under Categories' Adult Content).
 * With [liveTv], adult live categories show too once unlocked, or when the profile has no PIN.
 */
@Composable
fun categories(profile: Profile, kind: String, liveTv: Boolean = false): List<CategoryEntity> {
    val ctx = LocalContext.current
    val all by remember(kind) { ctx.app.db.catalog().categories(kind) }.collectAsState(initial = emptyList())
    val mine by ctx.app.myCategoryOrder.order.collectAsState()
    val unlocked by ctx.app.adultUnlocked.collectAsState()
    return remember(all, profile, mine, unlocked) { visibleCategories(all, profile, mine[kind].orEmpty(), liveTv && adultOpen(profile, unlocked)) }
}

/** True when adult content may show without asking: the PIN was entered this run, or the profile has none. */
fun adultOpen(profile: Profile?, unlocked: Boolean): Boolean = unlocked || profile != null && profile.parentalPin.isNullOrEmpty()

/**
 * [all] (one kind) minus the profile's hidden categories and adult ones; adult live categories stay
 * when [adultLive]. Categories named in [mine] (this device's order, from Settings) come first, then
 * those in the profile's order, then the rest in the provider's order.
 */
fun visibleCategories(all: List<CategoryEntity>, profile: Profile?, mine: List<String> = emptyList(), adultLive: Boolean = false): List<CategoryEntity> =
    arranged(all, profile, mine) { c -> !isAdult(c) || adultLive && c.kind == Kind.LIVE }

/** Only the adult categories of [all], arranged like [visibleCategories]. */
fun adultCategories(all: List<CategoryEntity>, profile: Profile?, mine: List<String> = emptyList()): List<CategoryEntity> =
    arranged(all, profile, mine, ::isAdult)

private fun isAdult(c: CategoryEntity) = ADULT.containsMatchIn(c.name)

private fun arranged(all: List<CategoryEntity>, profile: Profile?, mine: List<String>, keep: (CategoryEntity) -> Boolean): List<CategoryEntity> {
    val hidden = profile?.hiddenCategories.orEmpty().map { it.lowercase() }.toSet()
    val shown = all.filter { c -> c.name.lowercase() !in hidden && !Names.isSeparator(c.name) && keep(c) }
    fun rank(names: List<String>?) = names.orEmpty().withIndex().associate { (i, n) -> n.lowercase() to i }
    val own = rank(mine)
    val order = rank(profile?.categoryOrder?.get(all.firstOrNull()?.kind))
    if (own.isEmpty() && order.isEmpty()) return shown
    // sortedWith is stable, so unnamed categories keep the provider's order after the named ones.
    return shown.sortedWith(compareBy({ own[it.name.lowercase()] ?: Int.MAX_VALUE }, { order[it.name.lowercase()] ?: Int.MAX_VALUE }))
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
    ctx.startPlayer(
        Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerController.EXTRA_SOURCE, sourceId)
            .putExtra(PlayerController.EXTRA_CATEGORY, categoryId)
            .putExtra(PlayerController.EXTRA_INDEX, index)
            .putExtra(PlayerController.EXTRA_STREAM, streamId),
    )
}

/** [item] names the catalog movie or episode, so the player can resume it and tell Trakt. */
fun play(ctx: Context, url: String, title: String, item: PlayItem? = null) {
    val i = Intent(ctx, PlayerActivity::class.java).putExtra(PlayerController.EXTRA_URL, url).putExtra(PlayerController.EXTRA_TITLE, title)
    ctx.startPlayer(item?.putInto(i) ?: i)
}

/** Phones and tablets play at the top of the home screen ([DockedPlayer]); TV opens [PlayerActivity]. */
private fun Context.startPlayer(intent: Intent) {
    val main = generateSequence(this) { (it as? ContextWrapper)?.baseContext }.firstOrNull { it is MainActivity } as MainActivity?
    if (main != null && main.formFactor().touch) main.dock.play(intent) else startActivity(intent)
}

/** [fromStart]: Start over, from the beginning whatever the resume point. */
suspend fun playMovieNow(ctx: Context, profile: Profile, m: MovieEntity, fromStart: Boolean = false) {
    Streams(ctx.app.db, profile).movie(m)?.let { play(ctx, it, m.name, PlayItem.movie(m.sourceId, m.streamId, m.name).copy(fromStart = fromStart)) }
}
