package app.familytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.data.CategoryEntity
import app.familytv.data.ChannelEntity
import app.familytv.data.Kind
import app.familytv.data.MovieEntity
import app.familytv.data.SeriesEntity
import app.familytv.data.searchChannels
import app.familytv.data.searchMovies
import app.familytv.data.searchSeries
import app.familytv.portal.Profile
import app.familytv.usage.rememberSearchLog
import app.familytv.user.Favourites
import kotlinx.coroutines.delay

private const val LIMIT = 50

private data class Results(
    val channels: List<ChannelEntity> = emptyList(),
    val movies: List<MovieEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
) {
    val count get() = channels.size + movies.size + series.size
}

@Composable
fun SearchScreen(vm: MainViewModel, profile: Profile) {
    val ctx = LocalContext.current
    val dao = ctx.app.db.catalog()
    val nav = rememberSeriesNav()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    var fieldFocused by remember { mutableStateOf(false) }

    // Results respect hidden categories and the parental lock, like the browse pages.
    fun keys(cats: List<CategoryEntity>) = cats.map { it.sourceId to it.categoryId }.toSet()
    val live = keys(categories(profile, Kind.LIVE))
    val movies = keys(categories(profile, Kind.MOVIE))
    val series = keys(categories(profile, Kind.SERIES))
    val q = query.trim()
    val log = rememberSearchLog()
    val results by produceState(Results(), q, live, movies, series) {
        delay(300) // let typing settle
        value = if (q.length < 2) Results() else {
            val m = dao.searchMovies(q, LIMIT * 3).filter { (it.sourceId to it.categoryId) in movies }.take(LIMIT)
            val s = dao.searchSeries(q, LIMIT * 3).filter { (it.sourceId to it.categoryId) in series }.take(LIMIT)
            Results(
                dao.searchChannels(q, LIMIT * 3).filter { (it.sourceId to it.categoryId) in live }.take(LIMIT),
                m, s,
            )
        }
        if (q.length >= 2) log.settled(value.count) else log.ended()
    }
    val progs = rememberProgrammes(results.channels)
    val favs = rememberFavourites()
    val favKeys = rememberFavouriteKeys(favs)
    val showFavs = ShowFavourites(rememberFavouriteShowRows(favs))
    val openShowMenu = rememberShowMenu(profile)
    val shows = remember(results.series) { groupSeries(results.series) }
    val films = remember(results.movies) { groupMovies(results.movies) }
    val progress = rememberProgress()

    val touch = LocalFormFactor.current.touch
    nav.Host(vm, profile) {
        Column {
            PageTitle("Search")
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Tv.Text, fontSize = 20.sp),
                cursorBrush = SolidColor(Tv.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focusManager.moveFocus(FocusDirection.Down) }),
                modifier = (if (touch) Modifier.fillMaxWidth().widthIn(max = 620.dp) else Modifier.width(620.dp)).then(entryPoint(nav.back == null)).onFocusChanged { fieldFocused = it.isFocused },
                decorationBox = { inner ->
                    val shape = RoundedCornerShape(28.dp)
                    Box(
                        Modifier.clip(shape).background(Tv.Panel)
                            .border(if (fieldFocused) 3.dp else 1.dp, if (fieldFocused) Tv.Accent else Tv.Line, shape)
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                    ) {
                        if (query.isEmpty()) Text("Channels, movies or series", fontSize = 20.sp, color = Tv.Muted)
                        inner()
                    }
                },
            )
            Text(
                if (fieldFocused && !touch) "Press OK to type, then Search or Down for results" else "",
                fontSize = 13.sp, color = Tv.Muted, modifier = Modifier.padding(start = 24.dp, top = 6.dp),
            )
            Spacer(Modifier.height(12.dp))
            val r = results
            if (q.length >= 2 && r.channels.isEmpty() && films.isEmpty() && shows.isEmpty()) Text("No matches for \"$q\".", fontSize = 17.sp, color = Tv.Muted)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                if (r.channels.isNotEmpty()) item("ch") {
                    Shelf("Channels") {
                        itemsIndexed(r.channels, key = { _, c -> "${c.sourceId}/${c.streamId}" }) { i, c ->
                            ChannelCard(
                                c, c.epgId?.let { progs[it] }?.firstOrNull(),
                                favourite = Favourites.key(Kind.LIVE, c.sourceId, c.streamId) in favKeys,
                                onFavourite = { toggleFavourite(ctx, Kind.LIVE, c.sourceId, c.streamId, c.name) },
                            ) { log.picked(i); playLive(ctx, c.sourceId, c.categoryId, 0, c.streamId) }
                        }
                    }
                }
                if (films.isNotEmpty()) item("mv") {
                    Shelf("Movies") {
                        itemsIndexed(films, key = { _, g -> g.key }) { _, g ->
                            MovieCard(g, entryPoint(nav.back == g.key), profile = profile, visible = movies, favKeys = favKeys, progress = progress)
                        }
                    }
                }
                if (shows.isNotEmpty()) item("se") {
                    Shelf("Series") {
                        itemsIndexed(shows, key = { _, g -> g.key }) { i, g ->
                            val on = showFavs.has(g)
                            PosterCard(
                                g.title, g.poster, entryPoint(nav.back == g.key),
                                favourite = on,
                                onFavourite = { toggleShowFavourite(ctx, g, on) }, onMenu = { openShowMenu(g, on) },
                            ) { log.picked(i); nav.open = g }
                        }
                    }
                }
            }
        }
    }
}
