package app.familytv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.data.CategoryEntity
import app.familytv.data.ChannelEntity
import app.familytv.data.Kind
import app.familytv.data.MovieEntity
import app.familytv.data.SeriesEntity
import app.familytv.data.displayName
import app.familytv.portal.Profile
import app.familytv.trakt.ProgressRules
import app.familytv.user.Favourites
import app.familytv.user.ProgressEntity
import app.familytv.user.ProgressKey
import kotlinx.coroutines.flow.flowOf

/**
 * The selected category, kept while another page is open. Falls back to the first entry that isn't
 * Favourites (Highlights, on Movies and Series) when it disappears.
 */
@Composable
private fun selectedCategory(cats: List<CategoryEntity>): Pair<CategoryEntity?, (CategoryEntity) -> Unit> {
    var id by rememberSaveable { mutableStateOf<String?>(null) }
    val cat = cats.firstOrNull { it.chipKey == id } ?: cats.firstOrNull { !it.isFavourites } ?: cats.firstOrNull()
    return cat to { c: CategoryEntity -> id = c.chipKey }
}

internal val CategoryEntity.chipKey get() = "$sourceId/$categoryId"

/** Category shortcuts across the top of Movies, Series and the Guide. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CategoryChips(cats: List<CategoryEntity>, selected: CategoryEntity?, start: Dp = 0.dp, onSelect: (CategoryEntity) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().focusRestorer(), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(start = start, end = 40.dp)) {
        items(cats, key = { it.chipKey }) { c ->
            val minH = if (LocalFormFactor.current.touch) 48.dp else 0.dp
            FocusBox(onClick = { onSelect(c) }, selected = c == selected, color = Tv.Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.heightIn(min = minH), contentAlignment = Alignment.CenterStart) { focused ->
                Text(
                    c.displayName, Modifier.padding(horizontal = 18.dp, vertical = 9.dp), fontSize = 15.sp, maxLines = 1,
                    color = if (c == selected && !focused) Tv.Accent else Tv.Text,
                )
            }
        }
    }
}

@Composable
fun MoviesScreen(vm: MainViewModel, profile: Profile) {
    val ctx = LocalContext.current
    val tv = !LocalFormFactor.current.touch
    val visible = categories(profile, Kind.MOVIE)
    val cats = remember(visible) { if (visible.isEmpty()) visible else listOf(highlightsCategory(Kind.MOVIE), favouritesCategory(Kind.MOVIE)) + visible }
    val (cat, select) = selectedCategory(cats)
    val favs = rememberFavourites()
    val favKeys = rememberFavouriteKeys(favs)
    val favMovies = rememberFavouriteMovies(favs, visible)
    val progress = rememberProgress()
    val shelves = rememberMovieShelves(visible, favMovies, progress)
    val visibleKeys = remember(visible) { categoryKeys(visible) }
    // The card to come back to (after switching pages, the rail or a film's page), and the one the spotlight shows.
    var lastCard by rememberSaveable { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf<MovieGroup?>(null) }
    val nav = rememberSeriesNav()
    // Hoisted, so the grid or shelves come back where they were when a film's page closes.
    val grid = rememberLazyGridState()
    val list = rememberLazyListState()
    val inCategory by remember(cat) {
        cat?.takeUnless { it.isVirtual }?.let { ctx.app.db.catalog().movies(it.sourceId, it.categoryId) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val showingFavourites = cat?.isFavourites == true
    val movies = remember(showingFavourites, favMovies, inCategory) { groupMovies(if (showingFavourites) favMovies else inCategory) }
    if (cats.isEmpty()) return EmptyState("No movies on this line.")
    val card: @Composable (MovieGroup, Modifier, String, Dp?) -> Unit = { g, modifier, key, width ->
        MovieCard(g, modifier, width, profile, visibleKeys, favKeys, progress) { lastCard = key; focused = g }
    }
    nav.Host(vm, profile) {
        Column {
            if (tv) MovieSpotlight(focused, focused?.progressIn(progress), visibleKeys) else PageTitle("Movies")
            CategoryChips(cats, cat, FOCUS_ROOM, select)
            Spacer(Modifier.height(8.dp))
            when {
                cat?.isHighlights == true -> Shelves(shelves, MovieGroup::key, lastCard, "Films you're watching, recently added and top rated movies show up here once the line has loaded.", list) { g, mod, k ->
                    card(g, mod, k, 140.dp)
                }
                showingFavourites && favMovies.isEmpty() -> FavouritesHint(vodHint(LocalFormFactor.current, "a movie"))
                else -> PosterGrid(movies, MovieGroup::key, lastCard, grid) { g, mod, k -> card(g, mod, k, null) }
            }
        }
    }
}

/**
 * Live channels as logo cards. OK plays one; Up/Down in the player then zaps through the channel's
 * own category (a list like Sports > Hockey mixes categories).
 */
@Composable
internal fun ChannelPosters(channels: List<ChannelEntity>, key: String) {
    val ctx = LocalContext.current
    val favKeys = rememberFavouriteKeys(rememberFavourites())
    var lastCard by rememberSaveable(key) { mutableStateOf<String?>(null) }
    PosterGrid(channels, { "${it.sourceId}/${it.streamId}" }, lastCard) { c, mod, k ->
        val fav = Favourites.key(Kind.LIVE, c.sourceId, c.streamId) in favKeys
        PosterCard(
            c.displayName, c.logo, mod, width = null, logo = true,
            favourite = fav, onFavourite = { toggleFavourite(ctx, Kind.LIVE, c.sourceId, c.streamId, c.name) },
            onFocus = { lastCard = k },
        ) { playLive(ctx, c.sourceId, c.categoryId, 0, c.streamId) }
    }
}

/**
 * One category of [kind] as posters: live channels and movies play on OK, a show opens through
 * [nav] (the Categories page, Adult Content included).
 */
@Composable
internal fun CategoryPosters(profile: Profile, cat: CategoryEntity, nav: SeriesNav) {
    val ctx = LocalContext.current
    val dao = ctx.app.db.catalog()
    val favs = rememberFavourites()
    val favKeys = rememberFavouriteKeys(favs)
    var lastCard by rememberSaveable(cat.chipKey) { mutableStateOf<String?>(null) }
    when (cat.kind) {
        Kind.LIVE -> {
            val channels by remember(cat) { dao.channels(cat.sourceId, cat.categoryId) }.collectAsState(initial = emptyList())
            ChannelPosters(channels, cat.chipKey)
        }
        Kind.MOVIE -> {
            val progress = rememberProgress()
            val rows by remember(cat) { dao.movies(cat.sourceId, cat.categoryId) }.collectAsState(initial = emptyList())
            val movies = remember(rows) { groupMovies(rows) }
            val movieCats = categories(profile, Kind.MOVIE)
            val visibleKeys = remember(movieCats, cat) { categoryKeys(movieCats) + (cat.sourceId to cat.categoryId) }
            PosterGrid(movies, MovieGroup::key, nav.back ?: lastCard) { g, mod, k -> MovieCard(g, mod, null, profile, visibleKeys, favKeys, progress) { lastCard = k } }
        }
        else -> {
            val showFavs = ShowFavourites(rememberFavouriteShowRows(favs))
            val newKeys = rememberNewEpisodeKeys()
            val rows by remember(cat) { dao.series(cat.sourceId, cat.categoryId) }.collectAsState(initial = emptyList())
            val series = remember(rows) { groupSeries(rows) }
            PosterGrid(series, SeriesGroup::key, nav.back ?: lastCard) { g, mod, k ->
                val on = showFavs.has(g)
                PosterCard(
                    g.title, g.poster, mod, width = null,
                    favourite = on, onFavourite = { toggleShowFavourite(ctx, g, on) },
                    newBadge = g.hasNew(newKeys), newCount = g.newCount(newKeys),
                    onFocus = { lastCard = k },
                ) { nav.open = g }
            }
        }
    }
}

/**
 * Movies' Highlights: continue watching (films started and not finished, latest first), recently
 * added, favourites and top rated, from the categories this profile shows.
 */
@Composable
internal fun rememberMovieShelves(visible: List<CategoryEntity>, favMovies: List<MovieEntity>, progress: Map<String, ProgressEntity>): List<Pair<String, List<MovieGroup>>> {
    val dao = LocalContext.current.app.db.catalog()
    var watching by remember { mutableStateOf(emptyList<MovieGroup>()) }
    var recent by remember { mutableStateOf(emptyList<MovieGroup>()) }
    var top by remember { mutableStateOf(emptyList<MovieGroup>()) }
    LaunchedEffect(visible, progress) {
        val ok = categoryKeys(visible)
        val started = ProgressRules.continueWatching(progress.values, ProgressKey.MOVIE).take(SHELF_SIZE)
        watching = groupMovies(started.mapNotNull { dao.movie(it.sourceId, it.itemId) }.filter { (it.sourceId to it.categoryId) in ok })
    }
    LaunchedEffect(visible) {
        val ok = categoryKeys(visible)
        recent = groupMovies(dao.recentMovies(300).filter { (it.sourceId to it.categoryId) in ok }).take(SHELF_SIZE)
        top = groupMovies(dao.topMovies(TOP_RATING, 300).filter { (it.sourceId to it.categoryId) in ok }).take(SHELF_SIZE)
    }
    return remember(watching, recent, favMovies, top) {
        listOf(CONTINUE_WATCHING to watching, "Recently added" to recent, "Favourites" to groupMovies(favMovies), "Top rated" to top).filter { it.second.isNotEmpty() }
    }
}

@Composable
fun SeriesScreen(vm: MainViewModel, profile: Profile) {
    val ctx = LocalContext.current
    val tv = !LocalFormFactor.current.touch
    val visible = categories(profile, Kind.SERIES)
    val cats = remember(visible) { if (visible.isEmpty()) visible else listOf(highlightsCategory(Kind.SERIES), favouritesCategory(Kind.SERIES)) + visible }
    val (cat, select) = selectedCategory(cats)
    val nav = rememberSeriesNav()
    // Hoisted, so the grid or shelves come back where they were when a show's page closes.
    val grid = rememberLazyGridState()
    val list = rememberLazyListState()
    val favs = rememberFavourites()
    val favSeries = rememberFavouriteSeries(favs, visible)
    val showFavs = ShowFavourites(rememberFavouriteShowRows(favs))
    val newKeys = rememberNewEpisodeKeys()
    val newShows = rememberNewEpisodeShows(newKeys, visible)
    val progress = rememberProgress()
    val watchingEpisodes = remember(progress) { ProgressRules.continueWatching(progress.values, ProgressKey.EPISODE) }
    // A Continue watching poster's line: how far into the episode being watched.
    val watchingBars = remember(watchingEpisodes) { watchingEpisodes.associate { (it.sourceId to it.itemId) to ProgressRules.bar(it) } }
    val shelves = rememberSeriesShelves(visible, newShows, favSeries, watchingEpisodes)
    var lastCard by rememberSaveable { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf<SeriesGroup?>(null) }
    val inCategory by remember(cat) {
        cat?.takeUnless { it.isVirtual }?.let { ctx.app.db.catalog().series(it.sourceId, it.categoryId) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val showingFavourites = cat?.isFavourites == true
    // One poster per show, however many languages the provider lists it in.
    val series = remember(showingFavourites, favSeries, inCategory) { groupSeries(if (showingFavourites) favSeries else inCategory) }
    if (cats.isEmpty()) return EmptyState("No series on this line.")
    val card: @Composable (SeriesGroup, Modifier, String, Dp?) -> Unit = { g, modifier, key, width ->
        val on = showFavs.has(g)
        PosterCard(
            g.title, g.poster, modifier, width = width,
            favourite = on, onFavourite = { toggleShowFavourite(ctx, g, on) },
            progress = if (key.startsWith("$CONTINUE_WATCHING/")) g.copies.firstNotNullOfOrNull { watchingBars[it.sourceId to it.seriesId] } ?: 0f else 0f,
            newBadge = g.hasNew(newKeys), newCount = g.newCount(newKeys),
            onFocus = { lastCard = key; focused = g },
        ) { nav.open = g }
    }
    nav.Host(vm, profile) {
        Column {
            if (tv) SeriesSpotlight(focused, focused?.newCount(newKeys) ?: 0) else PageTitle("Series")
            CategoryChips(cats, cat, FOCUS_ROOM, select)
            Spacer(Modifier.height(8.dp))
            when {
                cat?.isHighlights == true -> Shelves(shelves, SeriesGroup::key, lastCard, "Shows you're watching, top rated shows and ones with new episodes show up here once the line has loaded.", list) { g, mod, k ->
                    card(g, mod, k, 140.dp)
                }
                showingFavourites && series.isEmpty() -> FavouritesHint(vodHint(LocalFormFactor.current, "a series"))
                else -> PosterGrid(series, SeriesGroup::key, lastCard, grid) { g, mod, k -> card(g, mod, k, null) }
            }
        }
    }
}

/**
 * Series' Highlights: continue watching (shows with an episode started and not finished, latest
 * first; the show's page opens at that episode), shows with new episodes, favourites and top rated.
 * There's no Recently added: the catalog keeps no date for a show.
 */
@Composable
internal fun rememberSeriesShelves(
    visible: List<CategoryEntity>,
    newShows: List<SeriesGroup>,
    favSeries: List<SeriesEntity>,
    watchingEpisodes: List<ProgressEntity>,
): List<Pair<String, List<SeriesGroup>>> {
    val dao = LocalContext.current.app.db.catalog()
    var watching by remember { mutableStateOf(emptyList<SeriesGroup>()) }
    var top by remember { mutableStateOf(emptyList<SeriesGroup>()) }
    LaunchedEffect(visible, watchingEpisodes) {
        val ok = categoryKeys(visible)
        watching = groupSeries(watchingEpisodes.take(SHELF_SIZE).mapNotNull { dao.seriesItem(it.sourceId, it.itemId) }.filter { (it.sourceId to it.categoryId) in ok })
    }
    LaunchedEffect(visible) {
        val ok = categoryKeys(visible)
        top = groupSeries(dao.topSeries(TOP_RATING, 400).filter { (it.sourceId to it.categoryId) in ok }).take(SHELF_SIZE)
    }
    return remember(watching, newShows, favSeries, top) {
        listOf(CONTINUE_WATCHING to watching, "New episodes" to newShows, "Favourites" to groupSeries(favSeries), "Top rated" to top).filter { it.second.isNotEmpty() }
    }
}

internal const val CONTINUE_WATCHING = "Continue watching"

private const val SHELF_SIZE = 30

/**
 * Rows of posters under a title each. The page's entry point is the card last focused ([entry],
 * a "shelf/key" pair, so the same title on two shelves stays two cards), else the first one.
 */
@Composable
private fun <T> Shelves(
    shelves: List<Pair<String, List<T>>>,
    key: (T) -> String,
    entry: String?,
    empty: String,
    state: LazyListState = rememberLazyListState(),
    card: @Composable (T, Modifier, String) -> Unit,
) {
    if (shelves.isEmpty()) return FavouritesHint(empty)
    val target = entry?.takeIf { e -> shelves.any { (t, l) -> l.any { "$t/${key(it)}" == e } } }
        ?: shelves.first().let { (t, l) -> "$t/${key(l.first())}" }
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        items(shelves, key = { it.first }) { (title, row) ->
            Shelf(title) {
                items(row, key = { "$title/${key(it)}" }) { x ->
                    val k = "$title/${key(x)}"
                    card(x, entryPoint(k == target), k)
                }
            }
        }
    }
}

/** One category as a grid of posters; the entry point is the card last focused ([entry]) when it's here, else the first. */
@Composable
internal fun <T> PosterGrid(
    items: List<T>,
    key: (T) -> String,
    entry: String?,
    state: LazyGridState = rememberLazyGridState(),
    card: @Composable (T, Modifier, String) -> Unit,
) {
    val hasEntry = entry != null && items.any { key(it) == entry }
    // Three posters across a phone, more on wider screens, seven on the TV's glass pages.
    val cell = when (LocalFormFactor.current) { FormFactor.Phone -> 100.dp; FormFactor.Tablet -> 130.dp; FormFactor.Tv -> 150.dp }
    val glass = LocalGlass.current
    val gap = when { glass -> 18.gx; LocalFormFactor.current == FormFactor.Phone -> 12.dp; else -> 18.dp }
    LazyVerticalGrid(
        columns = if (glass) GridCells.Fixed(7) else GridCells.Adaptive(cell),
        state = state,
        // Room for a focused poster's zoom, which the grid would otherwise clip.
        contentPadding = PaddingValues(start = FOCUS_ROOM, top = FOCUS_ROOM, bottom = 32.dp, end = FOCUS_ROOM),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        itemsIndexed(items, key = { _, it -> key(it) }) { i, it ->
            val k = key(it)
            card(it, entryPoint(if (hasEntry) k == entry else i == 0), k)
        }
    }
}

/** What an empty Favourites category says, and how to fill it. */
@Composable
internal fun FavouritesHint(text: String) {
    Text(text, fontSize = 16.sp, color = MutedText, modifier = Modifier.padding(bottom = 16.dp).widthIn(max = 560.dp))
}

private fun vodHint(form: FormFactor, what: String) =
    (if (form.touch) "Tap the heart on $what" else "Hold OK (or press Menu) on $what") +
        " to add it here (on a movie that also lets you mark it watched). With Trakt connected in Settings, favourite movies and series follow your Trakt watchlist on every device."
