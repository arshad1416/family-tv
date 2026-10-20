package app.familytv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.data.CatalogDao
import app.familytv.data.CategoryEntity
import app.familytv.data.Kind
import app.familytv.data.SearchText
import app.familytv.data.displayName
import app.familytv.data.searchMovies
import app.familytv.data.searchSeries
import app.familytv.iptv.SeriesTitles
import app.familytv.perf.Perf
import app.familytv.portal.Profile
import app.familytv.sync.PlaybackStatus
import app.familytv.trakt.ProgressRules
import app.familytv.usage.Usage
import app.familytv.user.ProgressEntity
import app.familytv.user.ProgressKey
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/** A movie or a show on the glass pages, so one grid can hold both (a provider's A to Z). */
internal sealed interface VodItem {
    val key: String
    val group: String
    val title: String

    class Movie(val g: MovieGroup) : VodItem {
        override val key get() = "m/${g.key}"
        override val group get() = g.key
        override val title get() = g.title
    }

    class Show(val g: SeriesGroup) : VodItem {
        override val key get() = "s/${g.key}"
        override val group get() = g.key
        override val title get() = g.title
    }
}

/** What every poster on a glass page needs to draw its marks: favourites, progress, new episodes. */
internal class VodMarks(
    val profile: Profile,
    val movieKeys: Set<Pair<String, String>>,
    val favKeys: Set<String>,
    val progress: Map<String, ProgressEntity>,
    val showFavs: ShowFavourites,
    val newKeys: Map<String, Int>,
)

@Composable
internal fun rememberVodMarks(profile: Profile): VodMarks {
    val favs = rememberFavourites()
    val movieCats = categories(profile, Kind.MOVIE)
    val progress = rememberProgress()
    return VodMarks(
        profile, remember(movieCats) { categoryKeys(movieCats) }, rememberFavouriteKeys(favs), progress,
        ShowFavourites(rememberFavouriteShowRows(favs)), rememberNewEpisodeKeys(progress),
    )
}

/**
 * A poster: a film opens its page, a show its seasons; hold OK (or Menu) for the menu or favourite.
 * In Continue watching ([continuing]) it shows the full title and the time left ([caption] for a show).
 */
@Composable
internal fun VodCard(item: VodItem, marks: VodMarks, modifier: Modifier, width: Dp?, bar: Float = 0f, continuing: Boolean = false, caption: String? = null, onOpen: () -> Unit = {}) {
    val ctx = LocalContext.current
    val nav = LocalSeriesNav.current
    when (item) {
        is VodItem.Movie -> MovieCard(item.g, modifier, width, marks.profile, marks.movieKeys, marks.favKeys, marks.progress, onOpen = onOpen, continuing = continuing)
        is VodItem.Show -> {
            val g = item.g
            val on = marks.showFavs.has(g)
            val badge = remember(g) { SeriesTitles.qualityBadge(g.copies.map { it.name }) }
            PosterCard(
                g.title, g.poster, modifier, width = width,
                favourite = on, onFavourite = { toggleShowFavourite(ctx, g, on) },
                progress = bar, newBadge = g.hasNew(marks.newKeys), newCount = g.newCount(marks.newKeys),
                badge = badge, caption = caption, fullName = continuing,
            ) { onOpen(); nav?.open = g }
        }
    }
}

private fun parentOf(path: String) = if (path.startsWith("adult/")) "adult" else ""

/**
 * Movies or TV Shows ([kind]) on the TV: a home of highlights, the Providers shelf, languages and
 * every category, and the pages those open ([path]: "provider/Netflix", "language/English",
 * "recent", "top", "new", "cat/…", "adult", "adult/…"). Back steps out one page at a time and
 * lands on the tile that opened it. The top bar's search searches this kind only.
 */
@Composable
internal fun VodTab(vm: MainViewModel, profile: Profile, kind: String) {
    val tab = if (kind == Kind.MOVIE) GlassTab.Movies else GlassTab.Series
    var path by rememberSaveable { mutableStateOf("") }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    val query = glassQuery(tab).trim()
    val nav = rememberSeriesNav()
    val home = rememberLazyListState()
    val pages = remember { HashMap<String, Collection>() }
    val movieCats = categories(profile, Kind.MOVIE)
    val seriesCats = categories(profile, Kind.SERIES)
    val marks = rememberVodMarks(profile)
    val entry = pageEntry()
    BackHandler(enabled = path.isNotEmpty()) { path = parentOf(path) }
    // Each step in or out moves focus to the new page's entry point, once it's there.
    var moved by remember { mutableStateOf(false) }
    LaunchedEffect(path) {
        if (!moved) { moved = true; return@LaunchedEffect }
        repeat(20) { delay(50); if (entry.attached > 0) return@LaunchedEffect entry.focus() }
    }
    val open = { p: String -> if (!p.startsWith("adult/")) opened = p; path = p }
    nav.Host(vm, profile) {
        when {
            path.isNotEmpty() -> VodPage(profile, kind, path, movieCats, seriesCats, marks, pages, nav, open) { path = parentOf(path) }
            query.length >= 2 -> VodSearch(profile, kind, query, marks)
            else -> VodHome(profile, kind, movieCats, seriesCats, marks, opened, home, open)
        }
    }
}

/** The Movies or TV Shows home. [opened] is the tile that opened the last page, to land back on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VodHome(
    profile: Profile,
    kind: String,
    movieCats: List<CategoryEntity>,
    seriesCats: List<CategoryEntity>,
    marks: VodMarks,
    opened: String?,
    list: LazyListState,
    open: (String) -> Unit,
) {
    val nav = LocalSeriesNav.current
    val movies = kind == Kind.MOVIE
    val cats = if (movies) movieCats else seriesCats
    if (movieCats.isEmpty() && seriesCats.isEmpty()) {
        return EmptyState(PlaybackStatus.lastRefreshError?.let { "Couldn't load the line: $it. Open Settings and run a check." } ?: "Nothing here yet.")
    }
    // The first frame with rows on it ends a cold start (see Perf).
    LaunchedEffect(Unit) { withFrameNanos { }; Perf.firstScreen() }

    val favs = rememberFavourites()
    val shelves: Map<String, List<VodItem>>
    var bars = emptyMap<Pair<String, String>, Float>()
    var lefts = emptyMap<Pair<String, String>, String>()
    if (movies) {
        shelves = rememberMovieShelves(movieCats, rememberFavouriteMovies(favs, movieCats), marks.progress).associate { (t, l) -> t to l.map { VodItem.Movie(it) } }
    } else {
        val newShows = rememberNewEpisodeShows(marks.newKeys, seriesCats)
        val watching = remember(marks.progress) { ProgressRules.continueWatching(marks.progress.values, ProgressKey.EPISODE) }
        bars = remember(watching) { watching.associate { (it.sourceId to it.itemId) to ProgressRules.bar(it) } }
        // "S2 E5 · 12 min left": the episode it picks up on, and how much of it is left.
        lefts = remember(watching) {
            watching.associate { p -> (p.sourceId to p.itemId) to listOfNotNull("S${p.season} E${p.episode}".takeIf { p.season > 0 }, ProgressRules.timeLeft(p)).joinToString("  ·  ") }
        }
        shelves = rememberSeriesShelves(seriesCats, newShows, rememberFavouriteSeries(favs, seriesCats), watching).associate { (t, l) -> t to l.map { VodItem.Show(it) } }
    }
    fun barOf(item: VodItem, shelf: String) =
        if (shelf == CONTINUE_WATCHING && item is VodItem.Show) item.g.copies.firstNotNullOfOrNull { bars[it.sourceId to it.seriesId] } ?: 0f else 0f
    fun leftOf(item: VodItem, shelf: String) =
        if (shelf == CONTINUE_WATCHING && item is VodItem.Show) item.g.copies.firstNotNullOfOrNull { lefts[it.sourceId to it.seriesId] }?.ifEmpty { null } else null

    val newest = if (movies) "Recently added" else "New episodes"
    val featured = shelves[CONTINUE_WATCHING]?.firstOrNull()?.let { it to CONTINUE_WATCHING }
        ?: shelves[newest]?.firstOrNull()?.let { it to newest }
        ?: shelves["Top rated"]?.firstOrNull()?.let { it to "Top rated" }
    // Continue watching is the first row under the highlights; Favourites comes after the browsing rows.
    val rows = listOf(CONTINUE_WATCHING, "Favourites").mapNotNull { t -> shelves[t]?.let { t to it } }
    val providers = remember(movieCats, seriesCats) { Provider.entries.filter { p -> movieCats.any { p.matches(it.name) } || seriesCats.any { p.matches(it.name) } } }
    val groups = remember(movieCats, seriesCats) { LanguageGroup.entries.filter { g -> movieCats.any { g.matches(it.name) } || seriesCats.any { g.matches(it.name) } } }
    val (regions, languages) = groups.partition { it.region }
    // The category chip wall starts folded away behind "Show all categories".
    var allCats by rememberSaveable(kind) { mutableStateOf<Boolean?>(null) }
    val ctx = LocalContext.current
    val all by remember(kind) { ctx.app.db.catalog().categories(kind) }.collectAsState(initial = emptyList())
    val mine by ctx.app.myCategoryOrder.order.collectAsState()
    val hasAdult = remember(all, profile, mine) { adultCategories(all, profile, mine[kind].orEmpty()).isNotEmpty() }

    // Where focus lands: the poster whose page just closed, else the tile that opened the last page, else the first.
    val back = nav?.back
    val homeKeys = buildSet {
        if (featured != null) add("hero")
        if (shelves[newest] != null) add("newest")
        if (shelves["Top rated"] != null) add("top")
        providers.forEach { add("provider/${it.name}") }
        groups.forEach { add("language/${it.name}") }
        cats.forEach { add("cat/${it.chipKey}") }
        if (hasAdult) add("adult")
    }
    val target = when {
        back != null && featured?.first?.group == back -> "hero"
        back != null -> rows.firstNotNullOfOrNull { (t, l) -> l.firstOrNull { it.group == back }?.let { "shelf/$t/${it.key}" } } ?: homeKeys.firstOrNull()
        opened != null && opened in homeKeys -> opened
        else -> homeKeys.firstOrNull()
    }
    // Until it's toggled, the chips are open only when coming back from a category page, so focus can land on its chip.
    val catsOpen = allCats ?: (target?.startsWith("cat/") == true || target == "adult")

    fun LazyListScope.shelf(title: String, items: List<VodItem>) = item("shelf/$title") {
        GlassShelf(title) {
            items(items, key = { it.key }) { x ->
                VodCard(x, marks, entryPoint(target == "shelf/$title/${x.key}"), POSTER_W, barOf(x, title), continuing = title == CONTINUE_WATCHING, caption = leftOf(x, title))
            }
        }
    }

    LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(22.gx), contentPadding = PaddingValues(bottom = 40.dp)) {
        // Always the first item, so the highlights loading in after the rest don't land above the scroll position.
        item("hero") {
            if (featured != null) Row(Modifier.fillMaxWidth().height(190.gx).padding(horizontal = FOCUS_ROOM), horizontalArrangement = Arrangement.spacedBy(20.gx)) {
                val (item, from) = featured
                HeroCard(
                    item.title, listOf(if (movies) "Movie" else "TV show", from).joinToString("  ·  "), item.poster, HERO_MAIN, 2.2f, entryPoint(target == "hero"),
                ) {
                    when (item) {
                        is VodItem.Movie -> nav?.movie = item.g to marks.movieKeys
                        is VodItem.Show -> nav?.open = item.g
                    }
                }
                shelves[newest]?.let { l -> HeroCard(newest, null, l.first().poster, HERO_NEW, 1f, entryPoint(target == "newest")) { open(if (movies) "recent" else "new") } }
                shelves["Top rated"]?.let { l -> HeroCard("Top rated", null, l.first().poster, HERO_TOP, 1f, entryPoint(target == "top")) { open("top") } }
            }
        }
        rows.filter { it.first == CONTINUE_WATCHING }.forEach { (title, items) -> shelf(title, items) }
        if (providers.isNotEmpty()) item("providers") {
            Column(verticalArrangement = Arrangement.spacedBy(10.gx)) {
                GlassHeading("Providers", Modifier.padding(start = FOCUS_ROOM))
                ProvidersShelf(providers, target) { open("provider/${it.name}") }
            }
        }
        // Languages and regions are separate rows (backlog item 26).
        listOf("Languages" to languages, "Regions" to regions).forEach { (heading, pills) ->
            if (pills.isNotEmpty()) item(heading) {
                Column(verticalArrangement = Arrangement.spacedBy(10.gx)) {
                    GlassHeading(heading, Modifier.padding(start = FOCUS_ROOM))
                    PillRow {
                        items(pills, key = { it.name }) { g ->
                            BigPill(g.label, entryPoint(target == "language/${g.name}")) { open("language/${g.name}") }
                        }
                    }
                }
            }
        }
        rows.filter { it.first != CONTINUE_WATCHING }.forEach { (title, items) -> shelf(title, items) }
        if (cats.isNotEmpty()) item("categories") {
            Column(Modifier.padding(horizontal = FOCUS_ROOM), verticalArrangement = Arrangement.spacedBy(10.gx)) {
                GlassHeading("All categories")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.gx), verticalArrangement = Arrangement.spacedBy(10.gx)) {
                    // The toggle stays put, so focus never drops when the chips open or close.
                    BigPill(if (catsOpen) "Hide categories" else "Show all categories (${cats.size + if (hasAdult) 1 else 0})") { allCats = !catsOpen }
                    if (catsOpen) {
                        cats.forEach { c -> BigPill(c.displayName, entryPoint(target == "cat/${c.chipKey}")) { open("cat/${c.chipKey}") } }
                        if (hasAdult) BigPill(ADULT_CONTENT, entryPoint(target == "adult"), lock = true) { open("adult") }
                    }
                }
            }
        }
    }
}

private const val ADULT_CONTENT = "Adult Content"
private val HERO_MAIN = listOf(Color(0xFF3B4A6B), Color(0xFF1D2638), Color(0xFF0F1420))
private val HERO_NEW = listOf(Color(0xFF5A3D2B), Color(0xFF1F1612))
private val HERO_TOP = listOf(Color(0xFF2B4A5A), Color(0xFF121C22))

/** A shelf poster's width: a seventh of the page, as in the grids. */
private val POSTER_W: Dp @Composable get() = if (LocalBigTiles.current) 190.gx else 150.gx

private val VodItem.poster: String? get() = when (this) { is VodItem.Movie -> g.poster; is VodItem.Show -> g.poster }

/** A glass card in the highlights row: artwork, a dark fade, and its title. */
@Composable
private fun RowScope.HeroCard(title: String, subtitle: String?, art: String?, tint: List<Color>, weight: Float, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.gx)
    FocusBox(onClick = onClick, outline = true, scale = 1.04f, color = Color.Transparent, shape = shape, modifier = modifier.weight(weight).fillMaxHeight()) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(tint)).border(1.dp, Color(0x24FFFFFF), shape))
        if (art != null) AsyncImage(art, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xC8000000))))
        OnDarkArt {
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 22.gx, vertical = 18.gx)) {
                Text(title, fontSize = (if (subtitle != null) 22 else 16).gsp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, fontSize = 15.gsp, color = Glass.Dim, maxLines = 1, modifier = Modifier.padding(top = 4.gx)) }
            }
        }
    }
}

/** The glass Providers panel: 16:9 button art, no labels; the D-pad scrolls the row. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ProvidersShelf(providers: List<Provider>, target: String?, onOpen: (Provider) -> Unit) {
    Box(Modifier.padding(horizontal = FOCUS_ROOM).fillMaxWidth().glass(RoundedCornerShape(26.gx))) {
        val row = rememberLazyListState()
        LazyRow(Modifier.focusRestorer(), state = row, contentPadding = PaddingValues(18.gx), horizontalArrangement = Arrangement.spacedBy(16.gx), verticalAlignment = Alignment.CenterVertically) {
            items(providers, key = { it.name }) { p ->
                FocusBox(
                    onClick = { onOpen(p) }, outline = true, scale = FOCUS_SCALE, lift = Lift.Tilt, color = Color.Black, shape = RoundedCornerShape(10.gx),
                    modifier = entryPoint(target == "provider/${p.name}").size(168.gx, 95.gx),
                ) {
                    Image(painterResource(p.art), contentDescription = p.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    // Brand-coloured art fills every tile (backlog item 10); a faint rim keeps dark ones edged on the dark panel.
                    Box(Modifier.matchParentSize().border(1.dp, Glass.Line, RoundedCornerShape(10.gx)))
                }
            }
        }
        MoreChevrons(row)
    }
}

/** A row of pills that keeps the one you left when focus comes back. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PillRow(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val row = rememberLazyListState()
    Box {
        LazyRow(Modifier.focusRestorer(), state = row, horizontalArrangement = Arrangement.spacedBy(10.gx), contentPadding = PaddingValues(start = FOCUS_ROOM, end = 40.dp, top = 4.dp, bottom = 4.dp), content = content)
        MoreChevrons(row)
    }
}

/** A language, category or Adult Content pill. */
@Composable
private fun BigPill(text: String, modifier: Modifier = Modifier, lock: Boolean = false, onClick: () -> Unit) {
    GlassPill(
        text, onClick, modifier, fill = Glass.Raised, border = true, fontSize = 15.gsp, padH = 22.gx, padV = 12.gx,
        icon = if (lock) ({ Icon(Icons.Filled.Lock, contentDescription = null, tint = Glass.Dim, modifier = Modifier.size(16.gx)) }) else null,
    )
}

/** A titled row of posters on a glass page. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun GlassShelf(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        GlassHeading(title, Modifier.padding(start = FOCUS_ROOM))
        val row = rememberLazyListState()
        Box {
            LazyRow(Modifier.focusRestorer(), state = row, horizontalArrangement = Arrangement.spacedBy(18.gx), contentPadding = PaddingValues(start = FOCUS_ROOM, end = 40.dp, top = FOCUS_ROOM, bottom = FOCUS_ROOM), content = content)
            MoreChevrons(row)
        }
    }
}

/** Small left and right chevrons at a row's ends while it continues off screen (backlog item 13). */
@Composable
internal fun BoxScope.MoreChevrons(row: LazyListState) {
    @Composable
    fun chevron(icon: ImageVector, align: Alignment) = Box(
        Modifier.align(align).size(28.gx).clip(RoundedCornerShape(50)).background(Glass.Raised),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = Glass.Soft, modifier = Modifier.size(20.gx)) }
    if (row.canScrollBackward) chevron(Icons.AutoMirrored.Filled.KeyboardArrowLeft, Alignment.CenterStart)
    if (row.canScrollForward) chevron(Icons.AutoMirrored.Filled.KeyboardArrowRight, Alignment.CenterEnd)
}

/** A page's own header in place of the top bar: Back, the provider's button art or a title, a detail, the clock. */
@Composable
internal fun PageHeader(onBack: () -> Unit, title: String, art: Int? = null, detail: String? = null) {
    GlassBarHidden()
    Row(Modifier.fillMaxWidth().padding(bottom = 22.gx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.gx)) {
        GlassBackButton(onBack)
        if (art != null) {
            val shape = RoundedCornerShape(12.gx)
            Image(painterResource(art), contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.size(112.gx, 63.gx).border(1.dp, Glass.LineSoft, shape).padding(1.dp).background(Color.Black, shape))
        } else {
            Text(title, fontSize = 34.gsp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        detail?.let { Text(it, fontSize = 15.gsp, color = Glass.Dim, maxLines = 1) }
        Spacer(Modifier.weight(1f))
        GlassClock()
    }
}

/** A page opened from a Movies or TV Shows home. */
@Composable
private fun VodPage(
    profile: Profile,
    kind: String,
    path: String,
    movieCats: List<CategoryEntity>,
    seriesCats: List<CategoryEntity>,
    marks: VodMarks,
    pages: HashMap<String, Collection>,
    nav: SeriesNav,
    open: (String) -> Unit,
    onBack: () -> Unit,
) {
    val (what, arg) = path.substringBefore('/') to path.substringAfter('/', "")
    val cats = if (kind == Kind.MOVIE) movieCats else seriesCats
    when (what) {
        "provider" -> Provider.entries.firstOrNull { it.name == arg }?.let { p ->
            GlassGlow(Color(p.glow))
            CollectionPage(p.label, p.art, providerCategories(movieCats, p), providerCategories(seriesCats, p), kind, marks, pages.getOrPut(path) { Collection() }, onBack)
        }
        "language" -> LanguageGroup.entries.firstOrNull { it.name == arg }?.let { g ->
            CollectionPage(g.label, null, languageCategories(movieCats, g), languageCategories(seriesCats, g), kind, marks, pages.getOrPut(path) { Collection() }, onBack)
        }
        "recent", "top", "new" -> ListPage(profile, kind, what, movieCats, seriesCats, marks, onBack)
        "cat" -> cats.firstOrNull { it.chipKey == arg }?.let { c ->
            Column { PageHeader(onBack, c.displayName); CategoryPosters(profile, c, nav) }
        }
        "adult" -> AdultPage(profile, kind, arg, nav, open, onBack)
    }
}

/** A provider's or a language's films and shows, loaded once while the tab stays open. */
private class Collection {
    var movies by mutableStateOf<List<MovieGroup>?>(null)
    var shows by mutableStateOf<List<SeriesGroup>?>(null)
    var view by mutableStateOf<View?>(null)
    var query by mutableStateOf("")
    val grids = HashMap<View, LazyGridState>()
}

private enum class View(val label: String) { Movies("Movies"), Shows("TV Shows"), Recent("Recently added"), AToZ("A to Z") }

/**
 * Inside a provider (or a language): its button art and counts, pill tabs (Movies, TV Shows,
 * Recently added, A to Z), a search of just these titles, and a seven-across poster grid.
 */
@Composable
private fun CollectionPage(
    title: String,
    art: Int?,
    movieCats: List<CategoryEntity>,
    showCats: List<CategoryEntity>,
    kind: String,
    marks: VodMarks,
    c: Collection,
    onBack: () -> Unit,
) {
    val dao = LocalContext.current.app.db.catalog()
    val nav = LocalSeriesNav.current
    LaunchedEffect(movieCats) { if (c.movies == null) c.movies = withContext(Dispatchers.IO) { loadMovies(dao, movieCats) } }
    LaunchedEffect(showCats) { if (c.shows == null) c.shows = withContext(Dispatchers.IO) { loadShows(dao, showCats) } }
    val movies = c.movies
    val shows = c.shows
    val views = buildList {
        if (!movies.isNullOrEmpty()) add(View.Movies)
        if (!shows.isNullOrEmpty()) add(View.Shows)
        if (!movies.isNullOrEmpty()) add(View.Recent)
        if (!movies.isNullOrEmpty() || !shows.isNullOrEmpty()) add(View.AToZ)
    }
    val view = c.view?.takeIf { it in views } ?: views.firstOrNull { it == (if (kind == Kind.MOVIE) View.Movies else View.Shows) } ?: views.firstOrNull()
    val q = c.query.trim()
    val n = NumberFormat.getIntegerInstance()
    val counts = listOfNotNull(movies?.takeIf { it.isNotEmpty() }?.let { "${n.format(it.size)} movies" }, shows?.takeIf { it.isNotEmpty() }?.let { "${n.format(it.size)} shows" })
        .joinToString("  ·  ").ifEmpty { null }
    val items: List<VodItem>? = remember(movies, shows, view, q) {
        if (movies == null || shows == null) null else {
            val m = movies.map { VodItem.Movie(it) }
            val s = shows.map { VodItem.Show(it) }
            val all = (m + s).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            if (q.length >= 2) all.filter { SearchText.matches(it.title, q) } else when (view) {
                View.Movies -> m
                View.Shows -> s
                View.Recent -> movies.sortedByDescending { g -> g.copies.maxOf { it.added } }.map { VodItem.Movie(it) }
                View.AToZ -> all
                null -> emptyList()
            }
        }
    }
    LaunchedEffect(q) { if (q.length >= 2) { delay(300); Usage.log(if (items.isNullOrEmpty()) Usage.Action.SEARCH_EMPTY else Usage.Action.SEARCH_SUBMIT, items?.size ?: 0) } }
    Column {
        PageHeader(onBack, title, art, counts)
        if (views.isNotEmpty()) Row(Modifier.padding(start = FOCUS_ROOM, bottom = 10.gx), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.glass(RoundedCornerShape(50)).padding(6.gx), horizontalArrangement = Arrangement.spacedBy(8.gx)) {
                views.forEach { v -> GlassPill(v.label, { c.view = v; c.query = "" }, selected = v == view && q.length < 2, fontSize = 15.gsp, padH = 22.gx, padV = 9.gx) }
            }
            Spacer(Modifier.width(18.gx))
            GlassSearchBox(c.query, { c.query = it }, "Search $title")
        }
        val shown = items
        when {
            shown == null -> EmptyState("Finding the titles…")
            shown.isEmpty() -> EmptyState(if (q.length >= 2) "No matches for \"$q\"." else "Nothing from $title on this line.")
            else -> {
                val grid = c.grids.getOrPut(if (q.length >= 2) View.AToZ else view ?: View.AToZ) { LazyGridState() }
                val entry = nav?.back?.let { b -> shown.firstOrNull { it.group == b }?.key }
                PosterGrid(shown, { it.key }, entry, grid) { x, mod, _ -> VodCard(x, marks, mod, null) }
            }
        }
    }
}

private suspend fun loadMovies(dao: CatalogDao, cats: List<CategoryEntity>): List<MovieGroup> {
    val rank = cats.withIndex().associate { (i, c) -> (c.sourceId to c.categoryId) to i }
    val rows = cats.groupBy { it.sourceId }.flatMap { (src, cs) -> cs.map { it.categoryId }.distinct().chunked(500).flatMap { dao.moviesIn(src, it) } }
    // In the categories' order, each newest first, as the categories list them.
    return groupMovies(rows.sortedWith(compareBy({ rank[it.sourceId to it.categoryId] ?: Int.MAX_VALUE }, { -it.added })))
}

private suspend fun loadShows(dao: CatalogDao, cats: List<CategoryEntity>): List<SeriesGroup> {
    val rank = cats.withIndex().associate { (i, c) -> (c.sourceId to c.categoryId) to i }
    val rows = cats.groupBy { it.sourceId }.flatMap { (src, cs) -> cs.map { it.categoryId }.distinct().chunked(500).flatMap { dao.seriesIn(src, it) } }
    return groupSeries(rows.sortedWith(compareBy({ rank[it.sourceId to it.categoryId] ?: Int.MAX_VALUE }, { it.name })))
}

/** Recently added, Top rated or New episodes, from the highlights row, as a full grid. */
@Composable
private fun ListPage(profile: Profile, kind: String, what: String, movieCats: List<CategoryEntity>, seriesCats: List<CategoryEntity>, marks: VodMarks, onBack: () -> Unit) {
    val dao = LocalContext.current.app.db.catalog()
    val nav = LocalSeriesNav.current
    val newShows = rememberNewEpisodeShows(marks.newKeys, seriesCats)
    val items by produceState(null as List<VodItem>?, what, movieCats, seriesCats, newShows) {
        value = withContext(Dispatchers.IO) {
            val m = categoryKeys(movieCats)
            val s = categoryKeys(seriesCats)
            when {
                what == "new" -> newShows.map { VodItem.Show(it) }
                kind == Kind.MOVIE && what == "recent" -> groupMovies(dao.recentMovies(LIST_ROWS).filter { (it.sourceId to it.categoryId) in m }).map { VodItem.Movie(it) }
                kind == Kind.MOVIE -> groupMovies(dao.topMovies(TOP_RATING, LIST_ROWS).filter { (it.sourceId to it.categoryId) in m }).map { VodItem.Movie(it) }
                else -> groupSeries(dao.topSeries(TOP_RATING, LIST_ROWS).filter { (it.sourceId to it.categoryId) in s }).map { VodItem.Show(it) }
            }
        }
    }
    Column {
        PageHeader(onBack, when (what) { "recent" -> "Recently added"; "new" -> "New episodes"; else -> "Top rated" })
        val shown = items
        when {
            shown == null -> EmptyState("Finding the titles…")
            shown.isEmpty() -> EmptyState("Nothing here yet.")
            else -> PosterGrid(shown, { it.key }, nav?.back?.let { b -> shown.firstOrNull { it.group == b }?.key }) { x, mod, _ -> VodCard(x, marks, mod, null) }
        }
    }
}

private const val LIST_ROWS = 600

/** Adult Content: the parental PIN (when the profile has one and it wasn't entered yet), then its categories. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdultPage(profile: Profile, kind: String, catKey: String, nav: SeriesNav, open: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val all by remember(kind) { ctx.app.db.catalog().categories(kind) }.collectAsState(initial = emptyList())
    val mine by ctx.app.myCategoryOrder.order.collectAsState()
    val unlocked by ctx.app.adultUnlocked.collectAsState()
    val adults = remember(all, profile, mine) { adultCategories(all, profile, mine[kind].orEmpty()) }
    val cat = adults.firstOrNull { it.chipKey == catKey }
    Column {
        PageHeader(onBack, cat?.displayName ?: ADULT_CONTENT)
        when {
            !adultOpen(profile, unlocked) -> PinPrompt(profile) { ctx.app.adultUnlocked.value = true }
            cat != null -> CategoryPosters(profile, cat, nav)
            adults.isEmpty() -> EmptyState("No adult categories on this line.")
            else -> FlowRow(Modifier.padding(horizontal = FOCUS_ROOM), horizontalArrangement = Arrangement.spacedBy(10.gx), verticalArrangement = Arrangement.spacedBy(10.gx)) {
                adults.forEachIndexed { i, c -> BigPill(c.displayName, entryPoint(i == 0)) { open("adult/${c.chipKey}") } }
            }
        }
    }
}

/** The top bar's search on Movies or TV Shows: this kind only, hidden and locked categories left out. */
@Composable
private fun VodSearch(profile: Profile, kind: String, q: String, marks: VodMarks) {
    val dao = LocalContext.current.app.db.catalog()
    val nav = LocalSeriesNav.current
    val cats = categories(profile, kind)
    val keys = remember(cats) { categoryKeys(cats) }
    var results by remember { mutableStateOf<List<VodItem>?>(null) }
    LaunchedEffect(q, keys) {
        delay(300) // let typing settle
        val found = if (kind == Kind.MOVIE) {
            groupMovies(dao.searchMovies(q, SEARCH_LIMIT * 3).filter { (it.sourceId to it.categoryId) in keys }.take(SEARCH_LIMIT)).map { VodItem.Movie(it) }
        } else {
            groupSeries(dao.searchSeries(q, SEARCH_LIMIT * 3).filter { (it.sourceId to it.categoryId) in keys }.take(SEARCH_LIMIT)).map { VodItem.Show(it) }
        }
        results = found
        found.size.let { Usage.log(if (it == 0) Usage.Action.SEARCH_EMPTY else Usage.Action.SEARCH_SUBMIT, it) }
    }
    val shown = results
    when {
        shown == null -> Unit
        shown.isEmpty() -> EmptyState("No matches for \"$q\".")
        else -> PosterGrid(shown, { it.key }, nav?.back?.let { b -> shown.firstOrNull { it.group == b }?.key }) { x, mod, _ ->
            VodCard(x, marks, mod, null) { Usage.log(Usage.Action.SEARCH_PICK, shown.indexOf(x)) }
        }
    }
}

internal const val SEARCH_LIMIT = 50
