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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import app.familytv.debrid.RdResume
import app.familytv.debrid.RdTitle
import app.familytv.discovery.DiscoveryPolicy
import app.familytv.discovery.DiscoverySession
import app.familytv.discovery.SourceUnavailable
import app.familytv.debrid.RdCopy
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import app.familytv.portal.Profile
import app.familytv.trakt.TitleMatch
import app.familytv.user.Favourites
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LIMIT = 50

private data class Results(
    val channels: List<ChannelEntity> = emptyList(),
    val movies: List<MovieEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
    val metadata: List<app.familytv.discovery.DiscoveryTitle> = emptyList(),
    val onlineStatus: String? = null,
)

@Composable
fun SearchScreen(vm: MainViewModel, profile: Profile) {
    val ctx = LocalContext.current
    val dao = ctx.app.db.catalog()
    val menus = LocalItemMenus.current
    val scope = rememberCoroutineScope()
    val nav = rememberSeriesNav()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    var fieldFocused by remember { mutableStateOf(false) }
    var external by remember(profile) { mutableStateOf<app.familytv.discovery.DiscoveryTitle?>(null) }
    var protectedAction by remember(profile) { mutableStateOf<(() -> Unit)?>(null) }
    var libraryGrant by remember(profile) { mutableStateOf<DiscoverySession?>(null) }
    var libraryUnlocking by remember(profile) { mutableStateOf(false) }
    val libraryUnlocked = profile.parentalPin.isNullOrEmpty() || libraryGrant != null
    val client = remember(ctx.app.portal.cachedConfig?.tmdbApiKey) {
        app.familytv.discovery.DiscoveryClient(app.familytv.core.Http.client, ctx.app.portal.cachedConfig?.tmdbApiKey.orEmpty())
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(profile) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle, profile) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { foreground = false; libraryGrant = null; protectedAction = null; external = null }
            else if (event == Lifecycle.Event.ON_START) foreground = true }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(libraryGrant) {
        val grant = libraryGrant ?: return@LaunchedEffect
        try {
            while (true) { grant.verify(); delay(15_000) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { libraryGrant = null }
    }

    // Results respect hidden categories and the parental lock, like the browse pages.
    fun keys(cats: List<CategoryEntity>) = cats.map { it.sourceId to it.categoryId }.toSet()
    val live = keys(categories(profile, Kind.LIVE))
    val movies = keys(categories(profile, Kind.MOVIE))
    val series = keys(categories(profile, Kind.SERIES))
    val q = query.trim()
    val publication = remember { LatestSearchResults(Results()) }
    val results by produceState(Results(), q, live, movies, series, profile, libraryUnlocked, libraryGrant, foreground, client) {
        publication.load(
            local = {
                if (q.length < 2) Results() else Results(
                    channels = dao.searchChannels(q, LIMIT * 3).filter { (it.sourceId to it.categoryId) in live }.take(LIMIT),
                    movies = dao.searchMovies(q, LIMIT * 3).filter { (it.sourceId to it.categoryId) in movies }.take(LIMIT),
                    series = dao.searchSeries(q, LIMIT * 3).filter { (it.sourceId to it.categoryId) in series }.take(LIMIT),
                    onlineStatus = if (libraryUnlocked) "Searching online metadata…" else "Unlock online metadata to discover more titles.",
                )
            },
            enrich = enrich@{ local ->
                if (q.length < 2 || !libraryUnlocked || !foreground) return@enrich local
                try {
                    var grant = libraryGrant
                    val matches = automaticMetadataSearch(q, client::search, {
                        val current = grant ?: DiscoverySession.open(ctx.app, profile).also { grant = it }
                        current.verify()
                    }, CatalogDiscoveryGuard(ctx.app.db, profile)::filterTitles)
                    local.copy(metadata = matches, onlineStatus = if (matches.isEmpty()) "No online metadata matches. IPTV results remain available." else "Metadata matches · select a catalog result above when available to keep its IPTV options and history.")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    local.copy(onlineStatus = (e as? SourceUnavailable)?.message ?: "Online metadata unavailable. IPTV results remain available; edit the search to retry.")
                }
            },
            publish = { value = it },
        )
    }

    val progs = rememberProgrammes(results.channels)
    val favs = rememberFavourites()
    val favKeys = rememberFavouriteKeys(favs)
    val showFavs = ShowFavourites(rememberFavouriteShowRows(favs))
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
            if (!libraryUnlocked) TvButton(if (libraryUnlocking) "Checking profile…" else "Unlock online metadata", {
                protectedAction = {
                    scope.launch {
                        libraryUnlocking = true
                        try {
                            val grant = DiscoverySession.open(ctx.app, profile)
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) libraryGrant = grant
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { toast(ctx, "Cannot verify the current profile. Try again later.") }
                        finally { libraryUnlocking = false }
                    }
                }
            }, enabled = !libraryUnlocking && q.length >= 2)
            val r = results
            if (q.length >= 2 && r.channels.isEmpty() && films.isEmpty() && shows.isEmpty()) Text("No IPTV matches for \"$q\".", fontSize = 17.sp, color = Tv.Muted)
            r.onlineStatus?.let { Text(it, fontSize = 14.sp, color = Tv.Muted) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                if (r.channels.isNotEmpty()) item("ch") {
                    Shelf("Channels") {
                        itemsIndexed(r.channels, key = { _, c -> "${c.sourceId}/${c.streamId}" }) { _, c ->
                            ChannelCard(
                                c, c.epgId?.let { progs[it] }?.firstOrNull(),
                                favourite = Favourites.key(Kind.LIVE, c.sourceId, c.streamId) in favKeys,
                                onFavourite = { toggleFavourite(ctx, Kind.LIVE, c.sourceId, c.streamId, c.name) },
                            ) { playLive(ctx, c.sourceId, c.categoryId, 0, c.streamId) }
                        }
                    }
                }
                if (films.isNotEmpty()) item("mv") {
                    Shelf("Movies") {
                        itemsIndexed(films, key = { _, g -> g.key }) { _, g ->
                            MovieCard(g, profile = profile, visible = movies, favKeys = favKeys, progress = progress)
                        }
                    }
                }
                if (shows.isNotEmpty()) item("se") {
                    Shelf("Series") {
                        itemsIndexed(shows, key = { _, g -> g.key }) { _, g ->
                            val on = showFavs.has(g)
                            PosterCard(
                                g.title, g.poster, entryPoint(nav.back == g.key),
                                favourite = on,
                                onFavourite = { toggleShowFavourite(ctx, g, on) },
                            ) { nav.open = g }
                        }
                    }
                }
                if (libraryUnlocked && r.metadata.isNotEmpty()) item("metadata") {
                    Shelf("Online metadata · Torrentio") {
                        itemsIndexed(r.metadata, key = { _, t -> t.key }) { _, t ->
                            PosterCard("${t.title}${t.year?.let { " ($it)" }.orEmpty()} · ${if (t.kind == "tv") "Series" else "Movie"} · Metadata", t.poster) { external = t }
                        }
                    }
                }
            }
        }
    }
    external?.let { ExternalDiscovery(profile, q, { external = null }, initialTitle = it) }
    protectedAction?.let { action ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { protectedAction = null }) {
            Column(Modifier.background(Tv.Raised, Tv.Shape).padding(24.dp)) {
                PinPrompt(profile) { protectedAction = null; action() }
                TvButton("Cancel", { protectedAction = null })
            }
        }
    }
}
