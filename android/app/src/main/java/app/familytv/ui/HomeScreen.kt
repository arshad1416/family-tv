package app.familytv.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import app.familytv.BuildConfig
import app.familytv.app
import app.familytv.data.CategoryEntity
import app.familytv.data.ChannelEntity
import app.familytv.data.Kind
import app.familytv.data.NowNext
import app.familytv.iptv.Streams
import app.familytv.iptv.XtreamParser
import app.familytv.portal.DeviceConfig
import app.familytv.portal.Profile
import app.familytv.sync.PlaybackStatus
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val TABS = listOf("Live TV", "Guide", "Movies", "Series", "Help")
private val ADULT = Regex("(?i)\\b(xxx|adult|18\\+|for adults)\\b")

@Composable
fun HomeScreen(vm: MainViewModel, config: DeviceConfig) {
    var tab by remember { mutableIntStateOf(0) }
    val profile = config.profile ?: Profile()
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onFocus = { tab = i }) {
                        Text(title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 16.sp)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            config.nickname?.let { Text(it, color = Color.Gray, fontSize = 14.sp) }
        }
        Spacer(Modifier.height(16.dp))
        when (tab) {
            0 -> LiveScreen(vm, profile)
            1 -> GuideScreen(vm, profile)
            2 -> MoviesScreen(vm, profile)
            3 -> SeriesScreen(vm, profile)
            4 -> HelpScreen(vm, config)
        }
    }
}

/** Categories for [kind], minus hidden ones and (until unlocked) adult ones. */
@Composable
private fun categories(vm: MainViewModel, profile: Profile, kind: String): List<CategoryEntity> {
    val ctx = LocalContext.current
    val unlocked by vm.adultUnlocked.collectAsState()
    val all by remember(kind) { ctx.app.db.catalog().categories(kind) }.collectAsState(initial = emptyList())
    val hidden = profile.hiddenCategories.orEmpty().map { it.lowercase() }.toSet()
    return all.filter { c ->
        c.name.lowercase() !in hidden && (unlocked || profile.parentalPin.isNullOrEmpty() || !ADULT.containsMatchIn(c.name))
    }
}

@Composable
private fun CategoryColumn(cats: List<CategoryEntity>, selected: CategoryEntity?, onSelect: (CategoryEntity) -> Unit) {
    LazyColumn(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(cats, key = { "${it.sourceId}/${it.categoryId}" }) { c ->
            ListItem(
                selected = c == selected,
                onClick = { onSelect(c) },
                headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun LiveScreen(vm: MainViewModel, profile: Profile) {
    val ctx = LocalContext.current
    val cats = categories(vm, profile, Kind.LIVE)
    var cat by remember { mutableStateOf<CategoryEntity?>(null) }
    LaunchedEffect(cats) { if (cat == null || cat !in cats) cat = cats.firstOrNull() }
    val channels by remember(cat) {
        cat?.let { ctx.app.db.catalog().channels(it.sourceId, it.categoryId) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val now = rememberNowNext(channels)
    if (cats.isEmpty()) return EmptyState(PlaybackStatus.lastRefreshError?.let { "Couldn't load channels: $it. Open Help and run a check." } ?: "No channels yet.")
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        CategoryColumn(cats, cat) { cat = it }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(channels, key = { _, c -> c.streamId }) { i, c ->
                val progs = c.epgId?.let { now[it] }.orEmpty()
                ListItem(
                    selected = false,
                    onClick = { playLive(ctx, c.sourceId, c.categoryId, i) },
                    leadingContent = { Logo(c.logo, 48) },
                    headlineContent = { Text("${c.num}  ${c.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        progs.firstOrNull()?.let { p ->
                            Text("Now: ${p.title}" + (progs.getOrNull(1)?.let { n -> "   ·   ${time(n.start)} ${n.title}" } ?: ""), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun rememberNowNext(channels: List<ChannelEntity>, hours: Int = 3): Map<String, List<NowNext>> {
    val ctx = LocalContext.current
    val ids = remember(channels) { channels.mapNotNull { it.epgId }.distinct() }
    val state by produceState(emptyMap<String, List<NowNext>>(), ids) {
        if (ids.isEmpty()) { value = emptyMap(); return@produceState }
        val now = System.currentTimeMillis()
        // SQLite caps bound parameters; query in chunks.
        value = ids.chunked(500).flatMap { ctx.app.db.catalog().programmes(it, now, now + hours * 3_600_000L) }.groupBy { it.epgId }
    }
    return state
}

@Composable
private fun GuideScreen(vm: MainViewModel, profile: Profile) {
    val ctx = LocalContext.current
    val cats = categories(vm, profile, Kind.LIVE)
    var cat by remember { mutableStateOf<CategoryEntity?>(null) }
    LaunchedEffect(cats) { if (cat == null || cat !in cats) cat = cats.firstOrNull() }
    val channels by remember(cat) {
        cat?.let { ctx.app.db.catalog().channels(it.sourceId, it.categoryId) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val progs = rememberNowNext(channels, hours = 6)
    if (cats.isEmpty()) return EmptyState("No guide yet.")
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        CategoryColumn(cats, cat) { cat = it }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(channels, key = { _, c -> c.streamId }) { i, c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, modifier = Modifier.width(180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    val list = c.epgId?.let { progs[it] }.orEmpty()
                    if (list.isEmpty()) {
                        Card(onClick = { playLive(ctx, c.sourceId, c.categoryId, i) }, modifier = Modifier.width(300.dp).height(56.dp)) {
                            Text("No guide information", Modifier.padding(10.dp), fontSize = 13.sp, color = Color.Gray)
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(list, key = { it.start }) { p ->
                            val now = System.currentTimeMillis()
                            val minutes = ((p.stop - maxOf(p.start, now)) / 60_000).coerceIn(15, 180)
                            Card(onClick = { playLive(ctx, c.sourceId, c.categoryId, i) }, modifier = Modifier.width((minutes * 4).toInt().dp).height(56.dp)) {
                                Column(Modifier.padding(8.dp)) {
                                    Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                                    Text("${time(p.start)}–${time(p.stop)}", fontSize = 11.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoviesScreen(vm: MainViewModel, profile: Profile) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cats = categories(vm, profile, Kind.MOVIE)
    var cat by remember { mutableStateOf<CategoryEntity?>(null) }
    LaunchedEffect(cats) { if (cat == null || cat !in cats) cat = cats.firstOrNull() }
    val movies by remember(cat) {
        cat?.let { ctx.app.db.catalog().movies(it.sourceId, it.categoryId) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    if (cats.isEmpty()) return EmptyState("No movies on this line.")
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        CategoryColumn(cats, cat) { cat = it }
        PosterGrid(movies.map { Triple(it.streamId, it.name, it.poster) }) { idx ->
            val m = movies[idx]
            scope.launch { Streams(ctx.app.db, profile).movie(m)?.let { play(ctx, it, m.name) } }
        }
    }
}

@Composable
private fun SeriesScreen(vm: MainViewModel, profile: Profile) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cats = categories(vm, profile, Kind.SERIES)
    var cat by remember { mutableStateOf<CategoryEntity?>(null) }
    var open by remember { mutableStateOf<Pair<String, String>?>(null) } // sourceId, seriesId
    var episodes by remember { mutableStateOf<List<XtreamParser.Episode>?>(null) }
    LaunchedEffect(cats) { if (cat == null || cat !in cats) cat = cats.firstOrNull() }
    val series by remember(cat) {
        cat?.let { ctx.app.db.catalog().series(it.sourceId, it.categoryId) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    if (cats.isEmpty()) return EmptyState("No series on this line.")

    val o = open
    if (o != null) {
        LaunchedEffect(o) {
            episodes = null
            val src = profile.sources.firstOrNull { it.id == o.first } ?: return@LaunchedEffect
            episodes = runCatching { ctx.app.catalog.episodes(o.first, o.second, src) }.getOrDefault(emptyList())
        }
        Column {
            Button(onClick = { open = null }) { Text("Back") }
            Spacer(Modifier.height(12.dp))
            val eps = episodes
            when {
                eps == null -> Text("Loading episodes…")
                eps.isEmpty() -> Text("No episodes found.")
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(eps, key = { it.id }) { e ->
                        ListItem(
                            selected = false,
                            onClick = { scope.launch { Streams(ctx.app.db, profile).episode(o.first, e)?.let { play(ctx, it, "S${e.season} E${e.episode} ${e.title}") } } },
                            headlineContent = { Text("S${e.season} E${e.episode}  ${e.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { e.plot?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) } },
                        )
                    }
                }
            }
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        CategoryColumn(cats, cat) { cat = it }
        PosterGrid(series.map { Triple(it.seriesId, it.name, it.poster) }) { idx -> open = series[idx].sourceId to series[idx].seriesId }
    }
}

@Composable
private fun PosterGrid(items: List<Triple<String, String, String?>>, onClick: (Int) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(items, key = { _, it -> it.first }) { i, (_, name, poster) ->
            Column {
                Card(onClick = { onClick(i) }, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF1F2937)), contentAlignment = Alignment.Center) {
                        Text(name, Modifier.padding(8.dp), fontSize = 13.sp, maxLines = 4)
                        if (poster != null) AsyncImage(poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun HelpScreen(vm: MainViewModel, config: DeviceConfig) {
    val ctx = LocalContext.current
    val report by vm.diagnosis.collectAsState()
    val running by vm.diagnosing.collectAsState()
    val unlocked by vm.adultUnlocked.collectAsState()
    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Not working?", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Run a check. It tests this TV's internet, the home network and the TV service, and sends the result to your TV admin.", fontSize = 16.sp)
            Button(onClick = { vm.runDiagnosis() }, enabled = !running) { Text(if (running) "Checking…" else "Run a check") }
            report?.let { r ->
                Text(r.verdict.tvMessage, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (r.verdict.severity == 0) Color(0xFF4ADE80) else Color(0xFFFBBF24))
                Text(r.summary, fontSize = 14.sp, color = Color.Gray)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.reload() }) { Text("Reload channels") }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This TV", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Name: ${config.nickname ?: "-"}")
            Text("Device ID: ${ctx.app.portal.deviceId ?: "-"}")
            Text("App version: ${BuildConfig.VERSION_NAME}")
            Text("Lists updated: ${if (PlaybackStatus.lastRefresh > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(PlaybackStatus.lastRefresh)) else "this session not yet"}")
            if (!config.profile?.parentalPin.isNullOrEmpty() && !unlocked) {
                Spacer(Modifier.height(16.dp))
                Text("Parental lock", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                PinPad(pin, onDigit = { if (pin.length < 8) pin += it; pinError = false }, onClear = { pin = "" }, onOk = {
                    pinError = !vm.unlock(pin); pin = ""
                })
                if (pinError) Text("Wrong PIN", color = Color(0xFFF87171))
            }
        }
    }
}

@Composable
private fun PinPad(pin: String, onDigit: (String) -> Unit, onClear: () -> Unit, onOk: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (pin.isEmpty()) "Enter PIN" else "•".repeat(pin.length), fontSize = 18.sp)
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("C", "0", "OK")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { k ->
                    Button(onClick = { when (k) { "C" -> onClear(); "OK" -> onOk(); else -> onDigit(k) } }, modifier = Modifier.width(64.dp)) { Text(k) }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) = Centered { Text(text, fontSize = 18.sp, color = Color.Gray) }

@Composable
private fun Logo(url: String?, sizeDp: Int) {
    Box(Modifier.size(sizeDp.dp).background(Color(0xFF111827)), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

private fun time(t: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))

private fun playLive(ctx: Context, sourceId: String, categoryId: String, index: Int) {
    ctx.startActivity(
        Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_SOURCE, sourceId)
            .putExtra(PlayerActivity.EXTRA_CATEGORY, categoryId)
            .putExtra(PlayerActivity.EXTRA_INDEX, index),
    )
}

private fun play(ctx: Context, url: String, title: String) {
    ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URL, url).putExtra(PlayerActivity.EXTRA_TITLE, title))
}
