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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.font.FontWeight
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
import app.familytv.portal.Profile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LIMIT = 50

private data class Results(
    val channels: List<ChannelEntity> = emptyList(),
    val movies: List<MovieEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
)

@Composable
fun SearchScreen(vm: MainViewModel, profile: Profile) {
    val ctx = LocalContext.current
    val dao = ctx.app.db.catalog()
    val scope = rememberCoroutineScope()
    val nav = rememberSeriesNav()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    var fieldFocused by remember { mutableStateOf(false) }

    // Results respect hidden categories and the parental lock, like the browse pages.
    fun keys(cats: List<CategoryEntity>) = cats.map { it.sourceId to it.categoryId }.toSet()
    val live = keys(categories(vm, profile, Kind.LIVE))
    val movies = keys(categories(vm, profile, Kind.MOVIE))
    val series = keys(categories(vm, profile, Kind.SERIES))
    val q = query.trim()
    val results by produceState(Results(), q, live, movies, series) {
        delay(300) // let typing settle
        value = if (q.length < 2) Results() else Results(
            dao.searchChannels(q, LIMIT * 3).filter { (it.sourceId to it.categoryId) in live }.take(LIMIT),
            dao.searchMovies(q, LIMIT * 3).filter { (it.sourceId to it.categoryId) in movies }.take(LIMIT),
            dao.searchSeries(q, LIMIT * 3).filter { (it.sourceId to it.categoryId) in series }.take(LIMIT),
        )
    }
    val progs = rememberProgrammes(results.channels)

    nav.Host(profile) {
        Column {
            Text("Search", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 14.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Tv.Text, fontSize = 20.sp),
                cursorBrush = SolidColor(Tv.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.width(620.dp).then(entryPoint(nav.back == null)).onFocusChanged { fieldFocused = it.isFocused },
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
                if (fieldFocused) "Press OK to type, then Search or Down for results" else "",
                fontSize = 13.sp, color = Tv.Muted, modifier = Modifier.padding(start = 24.dp, top = 6.dp),
            )
            Spacer(Modifier.height(12.dp))
            val r = results
            if (q.length >= 2 && r == Results()) Text("No matches for \"$q\".", fontSize = 17.sp, color = Tv.Muted)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                if (r.channels.isNotEmpty()) item("ch") {
                    Shelf("Channels") {
                        itemsIndexed(r.channels, key = { _, c -> "${c.sourceId}/${c.streamId}" }) { _, c ->
                            ChannelCard(c, c.epgId?.let { progs[it] }?.firstOrNull()) { playLive(ctx, c.sourceId, c.categoryId, 0, c.streamId) }
                        }
                    }
                }
                if (r.movies.isNotEmpty()) item("mv") {
                    Shelf("Movies") {
                        itemsIndexed(r.movies, key = { _, m -> "${m.sourceId}/${m.streamId}" }) { _, m ->
                            PosterCard(m.name, m.poster) { scope.launch { playMovieNow(ctx, profile, m) } }
                        }
                    }
                }
                if (r.series.isNotEmpty()) item("se") {
                    Shelf("Series") {
                        itemsIndexed(r.series, key = { _, s -> s.key }) { _, s ->
                            PosterCard(s.name, s.poster, entryPoint(nav.back == s.key)) { nav.open = s }
                        }
                    }
                }
            }
        }
    }
}
