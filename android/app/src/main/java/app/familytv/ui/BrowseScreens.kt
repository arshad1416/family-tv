package app.familytv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import app.familytv.data.MovieEntity
import app.familytv.portal.Profile
import app.familytv.sync.PlaybackStatus
import app.familytv.trakt.ProgressRules
import app.familytv.user.Favourites
import app.familytv.user.ProgressKey
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Remembers the selected category, falling back to the first real one (not Favourites) when it disappears. */
@Composable
private fun selectedCategory(cats: List<CategoryEntity>): Pair<CategoryEntity?, (CategoryEntity) -> Unit> {
    var cat by remember { mutableStateOf<CategoryEntity?>(null) }
    LaunchedEffect(cats) { if (cat == null || cat !in cats) cat = cats.firstOrNull { !it.isFavourites } ?: cats.firstOrNull() }
    return cat to { c: CategoryEntity -> cat = c }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CategoryColumn(cats: List<CategoryEntity>, selected: CategoryEntity?, onSelect: (CategoryEntity) -> Unit) {
    LazyColumn(Modifier.width(if (LocalFormFactor.current.touch) 220.dp else 270.dp).fillMaxHeight().focusRestorer(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
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
            val minH = if (LocalFormFactor.current.touch) 48.dp else 0.dp
            FocusBox(onClick = { onSelect(c) }, selected = c == selected, color = Tv.Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.heightIn(min = minH), contentAlignment = Alignment.CenterStart) { focused ->
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
    val visible = categories(vm, profile, Kind.LIVE)
    val cats = remember(visible) { if (visible.isEmpty()) visible else listOf(favouritesCategory(Kind.LIVE)) + visible }
    val (cat, select) = selectedCategory(cats)
    val favs = rememberFavourites()
    val favKeys = rememberFavouriteKeys(favs)
    val favChannels = rememberFavouriteChannels(favs, visible)
    val inCategory by remember(cat) {
        cat?.takeUnless { it.isFavourites }?.let { ctx.app.db.catalog().channels(it.sourceId, it.categoryId) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val showingFavourites = cat?.isFavourites == true
    val channels = if (showingFavourites) favChannels else inCategory
    val progs = rememberProgrammes(channels)
    if (cats.isEmpty()) return EmptyState(PlaybackStatus.lastRefreshError?.let { "Couldn't load channels: $it. Open Settings and run a check." } ?: "No channels yet.")
    // Phone: category chips over one column of channels. TV and tablet: categories beside channels.
    val form = LocalFormFactor.current
    val phone = form == FormFactor.Phone
    val list: @Composable () -> Unit = {
        if (showingFavourites && channels.isEmpty()) {
            FavouritesHint(
                (if (form.touch) "Tap the heart on a channel" else "Hold OK (or press Menu) on a channel") + " to add it here. " +
                    "Favourite channels stay on this device: Trakt has no live TV, so they don't sync.",
            )
        } else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            itemsIndexed(channels, key = { _, c -> "${c.sourceId}/${c.streamId}" }) { i, c ->
                val p = c.epgId?.let { progs[it] }.orEmpty()
                val now = p.firstOrNull()
                val next = p.getOrNull(1)
                val favourite = Favourites.key(Kind.LIVE, c.sourceId, c.streamId) in favKeys
                val onFavourite = { toggleFavourite(ctx, Kind.LIVE, c.sourceId, c.streamId, c.name) }
                FocusBox(
                    onClick = { if (showingFavourites) playLive(ctx, c.sourceId, c.categoryId, 0, c.streamId) else playLive(ctx, c.sourceId, c.categoryId, i) },
                    onLongClick = onFavourite,
                    modifier = Modifier.fillMaxWidth().height(if (phone) 72.dp else 80.dp).then(entryPoint(i == 0)),
                ) { focused ->
                    Row(Modifier.fillMaxSize().padding(start = if (phone) 10.dp else 14.dp, end = if (form.touch) 4.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.num}", Modifier.width(if (phone) 40.dp else 52.dp), fontSize = if (phone) 15.sp else 17.sp, color = if (focused) Tv.Text else Tv.Muted)
                        Logo(c.logo, if (phone) 44 else 54)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(now?.title ?: "No guide information", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (focused) Tv.Text else Tv.Muted)
                            if (now != null) ProgressLine(progress(now.start, now.stop), Modifier.padding(top = 6.dp).widthIn(max = 220.dp), fill = if (focused) Tv.Text else Tv.Accent)
                        }
                        if (next != null && !phone) Column(Modifier.width(240.dp).padding(start = 16.dp)) {
                            Text("Next · ${time(next.start)}", fontSize = 12.sp, color = if (focused) Tv.Text else Tv.Muted)
                            Text(next.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (form.touch) FavouriteButton(favourite, onFavourite) else MarkBadge(favourite = favourite, size = 20.dp)
                    }
                }
            }
        }
    }
    if (phone) {
        Column {
            CategoryChips(cats, cat, select)
            Spacer(Modifier.height(12.dp))
            list()
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            CategoryColumn(cats, cat, select)
            list()
        }
    }
}

@Composable
fun MoviesScreen(vm: MainViewModel, profile: Profile) {
    val ctx = LocalContext.current
    val menus = LocalItemMenus.current
    val scope = rememberCoroutineScope()
    val visible = categories(vm, profile, Kind.MOVIE)
    val cats = remember(visible) { if (visible.isEmpty()) visible else listOf(favouritesCategory(Kind.MOVIE)) + visible }
    val (cat, select) = selectedCategory(cats)
    val favs = rememberFavourites()
    val favKeys = rememberFavouriteKeys(favs)
    val favMovies = rememberFavouriteMovies(favs, visible)
    val progress = rememberProgress()
    val inCategory by remember(cat) {
        cat?.takeUnless { it.isFavourites }?.let { ctx.app.db.catalog().movies(it.sourceId, it.categoryId) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val showingFavourites = cat?.isFavourites == true
    val movies = if (showingFavourites) favMovies else inCategory
    if (cats.isEmpty()) return EmptyState("No movies on this line.")
    Column {
        PageTitle("Movies")
        CategoryChips(cats, cat, select)
        Spacer(Modifier.height(18.dp))
        if (showingFavourites && movies.isEmpty()) FavouritesHint(vodHint(LocalFormFactor.current, "a movie"))
        PosterGrid(
            movies.map { m ->
                val p = progress[ProgressKey.movie(m.sourceId, m.streamId)]
                val fav = Favourites.key(Kind.MOVIE, m.sourceId, m.streamId) in favKeys
                GridItem(
                    "${m.sourceId}/${m.streamId}", m.name, m.poster,
                    favourite = fav,
                    watched = p?.watched == true, progress = ProgressRules.bar(p),
                    onFavourite = { toggleFavourite(ctx, Kind.MOVIE, m.sourceId, m.streamId, m.name) },
                    movie = m,
                    onMenu = { menus.open = movieMenu(ctx, m, fav, p?.watched == true) },
                )
            },
            back = null, profile = profile,
        ) { idx ->
            scope.launch { playMovieNow(ctx, profile, movies[idx]) }
        }
    }
}

@Composable
fun SeriesScreen(vm: MainViewModel, profile: Profile) {
    val ctx = LocalContext.current
    val visible = categories(vm, profile, Kind.SERIES)
    val cats = remember(visible) { if (visible.isEmpty()) visible else listOf(favouritesCategory(Kind.SERIES)) + visible }
    val (cat, select) = selectedCategory(cats)
    val nav = rememberSeriesNav()
    val grid = rememberLazyGridState()
    val favs = rememberFavourites()
    val favSeries = rememberFavouriteSeries(favs, visible)
    val showFavs = ShowFavourites(rememberFavouriteShowRows(favs))
    val newKeys = rememberNewEpisodeKeys()
    val inCategory by remember(cat) {
        cat?.takeUnless { it.isFavourites }?.let { ctx.app.db.catalog().series(it.sourceId, it.categoryId) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val showingFavourites = cat?.isFavourites == true
    // One poster per show, however many languages the provider lists it in.
    val series = remember(showingFavourites, favSeries, inCategory) { groupSeries(if (showingFavourites) favSeries else inCategory) }
    if (cats.isEmpty()) return EmptyState("No series on this line.")
    nav.Host(vm, profile) {
        Column {
            PageTitle("Series")
            CategoryChips(cats, cat, select)
            Spacer(Modifier.height(18.dp))
            if (showingFavourites && series.isEmpty()) FavouritesHint(vodHint(LocalFormFactor.current, "a series"))
            PosterGrid(
                series.map { g ->
                    val on = showFavs.has(g)
                    GridItem(g.key, g.title, g.poster, favourite = on, onFavourite = { toggleShowFavourite(ctx, g, on) }, isNew = g.hasNew(newKeys))
                },
                back = nav.back, state = grid,
            ) { idx -> nav.open = series[idx] }
        }
    }
}

/** One poster in a [PosterGrid]. */
private class GridItem(
    val key: String,
    val name: String,
    val poster: String?,
    val favourite: Boolean = false,
    val watched: Boolean = false,
    val progress: Float = 0f,
    val onFavourite: (() -> Unit)? = null,
    /** Set for movies: the card gets a Trailer button (looked up when pressed). */
    val movie: MovieEntity? = null,
    val onMenu: (() -> Unit)? = null,
    val isNew: Boolean = false,
)

@Composable
private fun PosterGrid(
    items: List<GridItem>,
    back: String?,
    state: LazyGridState = rememberLazyGridState(),
    profile: Profile? = null,
    onClick: (Int) -> Unit,
) {
    val hasBack = back != null && items.any { it.key == back }
    // Three posters across a phone, more on wider screens.
    val cell = when (LocalFormFactor.current) { FormFactor.Phone -> 100.dp; FormFactor.Tablet -> 130.dp; FormFactor.Tv -> 150.dp }
    val gap = if (LocalFormFactor.current == FormFactor.Phone) 12.dp else 18.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(cell),
        state = state,
        contentPadding = PaddingValues(top = 6.dp, bottom = 32.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        itemsIndexed(items, key = { _, it -> it.key }) { i, it ->
            PosterCard(
                it.name, it.poster, entryPoint(if (hasBack) it.key == back else i == 0), width = null,
                favourite = it.favourite, onFavourite = it.onFavourite, watched = it.watched, progress = it.progress,
                onTrailer = if (it.movie != null && profile != null) movieTrailerAction(profile, it.movie) else null,
                onMenu = it.onMenu, newBadge = it.isNew,
            ) { onClick(i) }
        }
    }
}

/** What an empty Favourites category says, and how to fill it. */
@Composable
internal fun FavouritesHint(text: String) {
    Text(text, fontSize = 16.sp, color = Tv.Muted, modifier = Modifier.padding(bottom = 16.dp).widthIn(max = 560.dp))
}

private fun vodHint(form: FormFactor, what: String) =
    (if (form.touch) "Tap the heart on $what" else "Hold OK (or press Menu) on $what") +
        " to add it here (on a movie that also lets you mark it watched). With Trakt connected in Settings, favourite movies and series follow your Trakt watchlist on every device."
