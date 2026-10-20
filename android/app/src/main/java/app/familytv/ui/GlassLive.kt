package app.familytv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.data.CatalogDao
import app.familytv.data.CategoryEntity
import app.familytv.data.ChannelEntity
import app.familytv.data.Kind
import app.familytv.data.NowNext
import app.familytv.data.displayName
import app.familytv.data.searchChannels
import app.familytv.portal.Profile
import app.familytv.usage.TrackScreen
import app.familytv.usage.Usage
import app.familytv.user.Favourites
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val FAVOURITES = "Favourites"
private const val ALL = "All"
private const val TV_GUIDE = "TV guide"

/**
 * Live TV on the TV: a Category column, its countries (with channel counts), the channel list, and
 * at the right the focused channel's live preview over its guide ([GuideBox]). Both columns come from the provider's category names
 * ([LiveGenre], [liveCountry]); All, first, has every non-adult channel by country, so none is out of reach
 * whatever its category is called. Adult categories show only once the PIN is entered. TV guide, at the
 * foot of the Category column, opens the classic grid guide. Above them, "Recently watched" ([RecentStrip]).
 * The top bar's search searches channels.
 */
@Composable
internal fun LiveTab(vm: MainViewModel, profile: Profile) {
    var guide by rememberSaveable { mutableStateOf(false) }
    val q = glassQuery(GlassTab.Live).trim()
    val entry = pageEntry()
    BackHandler(enabled = guide) { guide = false }
    var moved by remember { mutableStateOf(false) }
    LaunchedEffect(guide) {
        if (!moved) { moved = true; return@LaunchedEffect }
        repeat(20) { delay(50); if (entry.attached > 0) return@LaunchedEffect entry.focus() }
    }
    when {
        guide -> Column {
            TrackScreen(Usage.Screen.GUIDE)
            GlassDarkPage()
            PageHeader({ guide = false }, TV_GUIDE)
            GuideScreen(vm, profile)
        }
        q.length >= 2 -> ChannelSearch(profile, q, sports = false)
        else -> LiveBrowser(profile) { guide = true }
    }
}

@Composable
private fun LiveBrowser(profile: Profile, onGuide: () -> Unit) {
    val ctx = LocalContext.current
    val dao = ctx.app.db.catalog()
    val cats = categories(profile, Kind.LIVE, liveTv = true)
    val favs = rememberFavourites()
    val favKeys = rememberFavouriteKeys(favs)
    val favChannels = rememberFavouriteChannels(favs, cats)
    val byGenre = remember(cats) { cats.groupBy { LiveGenre.of(it.name, isAdult(it)) } }
    // All: every channel on the line by country, whatever its category says; adult ones keep their own genre.
    val allCats = remember(cats) { cats.filterNot(::isAdult) }
    val genres = buildList {
        if (allCats.isNotEmpty()) add(ALL)
        if (favChannels.isNotEmpty()) add(FAVOURITES)
        LiveGenre.entries.forEach { if (byGenre[it] != null) add(it.label) }
    }
    if (genres.isEmpty()) return EmptyState("No channels on this line.")
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var country by rememberSaveable { mutableStateOf<String?>(null) }
    val g = genre?.takeIf { it in genres } ?: genres.first()
    val genreCats = if (g == ALL) allCats else LiveGenre.entries.firstOrNull { it.label == g }?.let { byGenre[it] }.orEmpty()
    var loaded by remember { mutableStateOf<List<ChannelEntity>?>(null) }
    LaunchedEffect(genreCats) {
        loaded = null
        loaded = withContext(Dispatchers.IO) { channelsOf(dao, genreCats) }
    }
    val byCountry: Map<String, List<ChannelEntity>> = remember(g, loaded, favChannels, genreCats) {
        if (g == FAVOURITES) mapOf(ALL to favChannels) else {
            val where = genreCats.associate { (it.sourceId to it.categoryId) to (liveCountry(it.name) ?: OTHER_COUNTRY) }
            loaded.orEmpty().groupBy { where[it.sourceId to it.categoryId] ?: OTHER_COUNTRY }
        }
    }
    val countries = remember(byCountry) { orderCountries(byCountry.mapValues { it.value.size }) }
    val c = country?.takeIf { it in countries } ?: countries.firstOrNull()
    val channels = c?.let { byCountry[it] }.orEmpty()

    val hoverGenre = remember { mutableStateOf<String?>(null) }
    val hoverCountry = remember { mutableStateOf<String?>(null) }
    FollowFocus(hoverGenre) { if (it != g && it != TV_GUIDE) { genre = it; country = null } }
    FollowFocus(hoverCountry) { if (it != c) country = it }
    // Right from a country goes to the channel list: by position it often went to the top bar's
    // search box instead, which is level with the columns' headings. Left comes back to the chosen country.
    val toChannels = remember { PageEntry() }
    val toCountry = remember { PageEntry() }
    // OK on a country moves focus into its channel list (backlog item 1), once the list has its rows.
    var intoList by remember { mutableIntStateOf(0) }
    LaunchedEffect(intoList) {
        if (intoList == 0) return@LaunchedEffect
        repeat(40) { delay(50); if (toChannels.attached > 0) return@LaunchedEffect toChannels.focus() }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.gx)) {
        RecentStrip(profile, cats)
        Row(Modifier.fillMaxWidth().weight(1f).focusGroup(), horizontalArrangement = Arrangement.spacedBy(18.gx)) {
            GlassColumnPanel(Modifier.width(200.gx).fillMaxHeight()) {
                GlassLabel("Category")
                // Leaving the column before the 300 ms rest cancels the pending pick, so the list never changes under focus.
                LazyColumn(Modifier.weight(1f).onFocusChanged { if (!it.hasFocus) hoverGenre.value = null }, verticalArrangement = Arrangement.spacedBy(6.gx)) {
                    items(genres, key = { it }) { x ->
                        // Right goes to the chosen country, not whichever sits level with this genre.
                        GlassRowItem(x, x == g, { genre = x; country = null }, entryPoint(x == g).focusProperties { right = toCountry.orNearest }, onFocus = { hoverGenre.value = x })
                    }
                    item(TV_GUIDE) { GlassRowItem(TV_GUIDE, false, onGuide, onFocus = { hoverGenre.value = TV_GUIDE }) }
                }
            }
            GlassColumnPanel(Modifier.width(200.gx).fillMaxHeight()) {
                GlassLabel("$g · Country")
                LazyColumn(Modifier.weight(1f).onFocusChanged { if (!it.hasFocus) hoverCountry.value = null }, verticalArrangement = Arrangement.spacedBy(6.gx)) {
                    items(countries, key = { it }) { x ->
                        GlassRowItem(
                            x, x == c, { country = x; if (byCountry[x].orEmpty().isNotEmpty()) intoList++ },
                            Modifier.focusProperties { right = if (channels.isEmpty()) FocusRequester.Cancel else toChannels.orNearest }
                                .then(landing(toCountry, x == c)),
                            count = byCountry[x]?.size, onFocus = { hoverCountry.value = x },
                        )
                    }
                }
            }
            ChannelBrowser(channels, favKeys, Modifier.weight(1f), side = false, header = null, loading = loaded == null && g != FAVOURITES, arrival = toChannels, leftTo = toCountry, backTo = profile)
        }
    }
}

/**
 * "Recently watched" (backlog item 2): the last channels this profile played on this TV, newest
 * first, as one row of small pills; OK plays one. Channels the provider dropped, or in hidden or
 * locked categories, are left out, and with none the row isn't shown at all.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun RecentStrip(profile: Profile, visible: List<CategoryEntity>) {
    val ctx = LocalContext.current
    val last = ctx.app.lastChannels
    // The player records each channel as it starts, so coming back from it refreshes the row.
    val changed by last.changes.collectAsState()
    var rows by remember { mutableStateOf(emptyList<ChannelEntity>()) }
    LaunchedEffect(changed, visible, profile) {
        val ok = visible.mapTo(HashSet()) { it.sourceId to it.categoryId }
        val dao = ctx.app.db.catalog()
        rows = last.recent(profile).mapNotNull { dao.channel(it.sourceId, it.streamId) }.filter { (it.sourceId to it.categoryId) in ok }
    }
    if (rows.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassLabel("Recently watched", Modifier.width(200.gx))
        val row = rememberLazyListState()
        Box(Modifier.weight(1f)) {
            LazyRow(Modifier.focusRestorer(), state = row, horizontalArrangement = Arrangement.spacedBy(10.gx), contentPadding = PaddingValues(horizontal = 6.gx, vertical = 6.gx)) {
                items(rows, key = { "${it.sourceId}/${it.streamId}" }) { ch ->
                    GlassPill(
                        ch.displayName, { playLive(ctx, ch.sourceId, ch.categoryId, 0, ch.streamId) },
                        Modifier.widthIn(max = 280.gx), fill = Glass.Raised, border = true, fontSize = 15.gsp, padH = 14.gx, padV = 6.gx,
                        icon = ch.logo?.let { logo -> { AsyncImage(logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(36.gx, 22.gx)) } },
                    )
                }
            }
            MoreChevrons(row)
        }
    }
}

/** The live channels of [cats], in the categories' order, then by number. */
private suspend fun channelsOf(dao: CatalogDao, cats: List<CategoryEntity>): List<ChannelEntity> {
    val rank = cats.withIndex().associate { (i, c) -> (c.sourceId to c.categoryId) to i }
    return cats.groupBy { it.sourceId }
        .flatMap { (src, cs) -> cs.map { it.categoryId }.distinct().chunked(500).flatMap { dao.channelsIn(src, it) } }
        .sortedWith(compareBy({ rank[it.sourceId to it.categoryId] ?: Int.MAX_VALUE }, { it.num }, { it.name }))
}

/**
 * The channel list and the focused channel: on Live TV its preview over its guide, right of the
 * list; on Sports and in search results ([side]) just the preview, left of it. OK plays a channel (Up and Down then zap
 * through its own category); hold OK (or Menu) favourites it.
 */
@Composable
private fun ChannelBrowser(
    channels: List<ChannelEntity>,
    favKeys: Set<String>,
    modifier: Modifier,
    side: Boolean,
    header: String?,
    loading: Boolean = false,
    entry: Boolean = false,
    arrival: PageEntry? = null,
    leftTo: PageEntry? = null,
    backTo: Profile? = null,
    onPick: (Int) -> Unit = {},
) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf<ChannelEntity?>(null) }
    val rows = rememberLazyListState()
    // Back from full screen lands here (with [backTo], Live TV): focus moves to the channel last
    // playing, which may be one zapped to with Up and Down, and the preview picks it up.
    var away by remember { mutableStateOf(false) }
    var inList by remember { mutableStateOf(false) }
    val shown = focused?.takeIf { f -> channels.any { it.sourceId == f.sourceId && it.streamId == f.streamId } } ?: channels.firstOrNull()
    // Only a channel focus is resting on plays, never the first one shown while focus is in the columns.
    val video = rememberLivePreview()
    PreviewFollows(video, shown.takeIf { inList && focused != null })
    LaunchedEffect(video.resumed) {
        val profile = backTo?.takeIf { away } ?: return@LaunchedEffect
        away = false
        val last = ctx.app.lastChannels.recent(profile).firstOrNull() ?: return@LaunchedEffect
        val i = channels.indexOfFirst { it.sourceId == last.sourceId && it.streamId == last.streamId }
        if (i < 0 || arrival == null) return@LaunchedEffect
        focused = channels[i]
        rows.scrollToItem(i)
        repeat(20) { delay(50); if (arrival.attached > 0) return@LaunchedEffect arrival.focus() }
    }
    // A start that moves every half hour, not with focus, so moving down the list doesn't re-read the guide.
    val minute = rememberMinute()
    val from = remember(channels, minute / HALF_HOUR) { System.currentTimeMillis() }
    // Six hours, so the guide box has the next few programmes.
    val progs = rememberProgrammes(channels, from = from, hours = if (side) 3 else 6)
    val preview: @Composable (Modifier) -> Unit = { m -> ChannelPreview(shown, shown?.epgId?.let { progs[it] }.orEmpty(), live = side, video, modifier = m) }
    val list: @Composable (Modifier) -> Unit = { m ->
        Column(m.glass(RoundedCornerShape(24.gx), Glass.Panel, Glass.PanelLine).onFocusChanged { inList = it.hasFocus }.padding(10.gx)) {
            header?.let { GlassLabel(it) }
            when {
                loading -> Text("Finding the channels…", fontSize = 15.gsp, color = Glass.Dim, modifier = Modifier.padding(12.gx))
                channels.isEmpty() -> Text("No channels here.", fontSize = 15.gsp, color = Glass.Dim, modifier = Modifier.padding(12.gx))
                else -> LazyColumn(state = rows, verticalArrangement = Arrangement.spacedBy(6.gx), contentPadding = PaddingValues(vertical = 2.dp)) {
                    itemsIndexed(channels, key = { _, ch -> "${ch.sourceId}/${ch.streamId}" }) { i, ch ->
                        ChannelRow(
                            ch, ch.epgId?.let { progs[it] }.orEmpty(), minute, numbers = !side,
                            favourite = Favourites.key(Kind.LIVE, ch.sourceId, ch.streamId) in favKeys,
                            modifier = entryPoint(entry && i == 0)
                                .then(if (arrival != null) landing(arrival, ch == shown) else Modifier)
                                .then(if (leftTo != null) Modifier.focusProperties { left = leftTo.orNearest } else Modifier),
                            onFocus = { focused = ch },
                            onFavourite = { toggleFavourite(ctx, Kind.LIVE, ch.sourceId, ch.streamId, ch.name) },
                        ) {
                            onPick(i)
                            away = backTo != null
                            // The line's one connection goes to full screen.
                            video.stop()
                            playLive(ctx, ch.sourceId, ch.categoryId, 0, ch.streamId)
                        }
                    }
                }
            }
        }
    }
    if (side) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(18.gx)) {
            preview(Modifier.width(520.gx).fillMaxHeight())
            list(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Row(modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(18.gx)) {
            list(Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.width(400.gx).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.gx)) {
                preview(Modifier.fillMaxWidth().height(225.gx))
                if (shown != null) GuideBox(shown, shown.epgId?.let { progs[it] }.orEmpty(), Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

/**
 * The guide for [c], from the same programmes as the TV guide (extra guides and pins included):
 * what's on now with its times, progress and description, then the next four with their start
 * times. It follows focus at once; only the description is read per channel.
 */
@Composable
private fun GuideBox(c: ChannelEntity, progs: List<NowNext>, modifier: Modifier) {
    val ctx = LocalContext.current
    val minute = rememberMinute()
    val upcoming = progs.filter { it.stop > minute }
    val now = upcoming.firstOrNull()?.takeIf { it.start <= minute }
    val next = upcoming.drop(if (now != null) 1 else 0).take(4)
    var about by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(now) {
        about = null
        val p = now ?: return@LaunchedEffect
        about = ctx.app.db.catalog().programme(p.epgId, p.start)?.description?.takeIf { it.isNotBlank() }
    }
    Column(
        modifier.glass(RoundedCornerShape(24.gx), Glass.Panel, Glass.PanelLine).padding(horizontal = 18.gx, vertical = 14.gx),
        verticalArrangement = Arrangement.spacedBy(8.gx),
    ) {
        GlassLabel("Guide · ${c.displayName}")
        if (now == null && next.isEmpty()) {
            Text("No guide for this channel", fontSize = 15.gsp, color = Glass.Dim)
            return@Column
        }
        if (now != null) {
            Text(now.title, fontSize = 17.gsp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${time(now.start)} – ${time(now.stop)}", fontSize = 15.gsp, color = Glass.Dim)
            ProgressLine(progress(now.start, now.stop, minute), track = Glass.Line, fill = Glass.Text)
            about?.let { Text(it, fontSize = 15.gsp, color = Glass.Soft, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
        if (next.isNotEmpty()) {
            Spacer(Modifier.height(2.gx))
            next.forEach { p ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.gx)) {
                    Text(time(p.start), fontSize = 14.gsp, color = Glass.Dim, maxLines = 1, modifier = Modifier.width(76.gx))
                    Text(p.title, fontSize = 14.gsp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * The focused channel: its live [video] once it plays, until then its programme's picture (or its
 * logo); on Sports a badge saying whether the game on is live, a replay, upcoming or over; under the
 * picture, the name and Now / Next.
 */
@Composable
private fun ChannelPreview(c: ChannelEntity?, progs: List<NowNext>, live: Boolean, video: LivePreview, modifier: Modifier) {
    val ctx = LocalContext.current
    val minute = rememberMinute()
    val now = progs.firstOrNull { it.stop > minute }
    val next = progs.getOrNull(progs.indexOf(now) + 1)
    // The picture is read only once focus rests on a channel, as the guide does.
    var picture by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(now) {
        picture = null
        val p = now ?: return@LaunchedEffect
        delay(250)
        picture = ctx.app.db.catalog().programme(p.epgId, p.start)?.icon
    }
    val shape = RoundedCornerShape(24.gx)
    Column(modifier) {
        // Over the dark art the badge stays light in either look.
        OnDarkArt {
            Box(Modifier.weight(1f).fillMaxWidth().clip(shape).background(Brush.linearGradient(if (live) listOf(Color(0xFF24533A), Color(0xFF0E1D15)) else listOf(Color(0xFF2A4456), Color(0xFF101A22)))).border(1.dp, Color(0x2EFFFFFF), shape)) {
                val art = picture
                when {
                    art != null -> AsyncImage(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    c?.logo != null -> AsyncImage(c.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.align(Alignment.Center).fillMaxSize(0.42f))
                }
                if (video.active) PreviewVideo(video, Modifier.fillMaxSize().background(Color.Black))
                // Sports: LIVE, REPLAY, UPCOMING or ENDED from the programme on now (backlog item 22); none when nothing is.
                if (live && c != null) EventStatus.of(progs.onAt(minute)?.title)?.let { status ->
                    Text(
                        status.label, fontSize = 13.gsp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, color = Color.White,
                        modifier = Modifier.padding(16.gx).clip(RoundedCornerShape(50)).background(status.color).padding(horizontal = 12.gx, vertical = 6.gx),
                    )
                }
            }
        }
        // The caption sits under the picture, not over it (backlog item 22).
        if (c != null) {
            Column(Modifier.fillMaxWidth().padding(start = 8.gx, end = 8.gx, top = 10.gx)) {
                Text(listOfNotNull(c.num.takeIf { !live && it > 0 }?.toString(), c.displayName).joinToString("  ·  "), fontSize = 16.gsp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = listOfNotNull(now?.let { "Now: ${it.title}" }, next?.let { "Next: ${it.title}" }).joinToString("  ·  ")
                if (line.isNotEmpty()) Text(line, fontSize = 15.gsp, color = Glass.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One channel in a glass list: number (Live TV), logo, name, what's on now and the next two hours as a thin bar. */
@Composable
private fun ChannelRow(
    c: ChannelEntity,
    progs: List<NowNext>,
    minute: Long,
    numbers: Boolean,
    favourite: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onFavourite: () -> Unit,
    onClick: () -> Unit,
) {
    FocusBox(onClick = onClick, onLongClick = onFavourite, onFocus = onFocus, lift = Lift.Glow, color = Color.Transparent, shape = RoundedCornerShape(14.gx), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.gx, vertical = 8.gx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.gx)) {
            if (numbers) Text("${c.num}", fontSize = 15.gsp, color = Glass.Dim, maxLines = 1, softWrap = false, modifier = Modifier.width(54.gx))
            Box(Modifier.size(54.gx, 32.gx).clip(RoundedCornerShape(8.gx)).background(Glass.Raised), contentAlignment = Alignment.Center) {
                if (c.logo != null) AsyncImage(c.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(3.gx))
            }
            // What's on goes under the name, so the name gets the row's whole width (a fixed name column cut Sports names in half).
            Column(Modifier.weight(1f)) {
                Text(c.displayName, fontSize = 16.gsp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                progs.onAt(minute)?.let { Text("Now: ${it.title}", fontSize = 15.gsp, color = Glass.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (progs.any { it.stop > minute && it.start < minute + TIMELINE }) Timeline(progs, minute, Modifier.padding(top = 5.gx).fillMaxWidth().height(4.gx))
            }
            if (favourite) MarkBadge(favourite = true, size = 18.dp)
        }
    }
}

/**
 * The mini guide timeline (backlog item 19): the next two hours from [minute], one segment per
 * programme with a small gap at each change; the one on now is brighter. Drawn, not laid out, so a long list stays cheap.
 */
@Composable
private fun Timeline(progs: List<NowNext>, minute: Long, modifier: Modifier) {
    val now = Glass.Dim
    val later = Glass.LineSoft
    Canvas(modifier) {
        val end = minute + TIMELINE
        val gap = 2.dp.toPx()
        val r = CornerRadius(size.height / 2)
        progs.forEach { p ->
            if (p.stop <= minute || p.start >= end) return@forEach
            val x0 = size.width * (maxOf(p.start, minute) - minute) / TIMELINE
            val x1 = size.width * (minOf(p.stop, end) - minute) / TIMELINE - gap
            if (x1 > x0) drawRoundRect(if (p.start <= minute) now else later, Offset(x0, 0f), Size(x1 - x0, size.height), r)
        }
    }
}

/** The top bar's search on Live TV or Sports: channels only (sports categories only on Sports), no adult ones. */
@Composable
private fun ChannelSearch(profile: Profile, q: String, sports: Boolean) {
    val dao = LocalContext.current.app.db.catalog()
    val cats = categories(profile, Kind.LIVE)
    val keys = remember(cats, sports) { categoryKeys(if (sports) cats.filter { Sport.isSportsCategory(it.name) } else cats) }
    val favKeys = rememberFavouriteKeys(rememberFavourites())
    var results by remember { mutableStateOf<List<ChannelEntity>?>(null) }
    LaunchedEffect(q, keys) {
        delay(300) // let typing settle
        val found = dao.searchChannels(q, SEARCH_LIMIT * 3).filter { (it.sourceId to it.categoryId) in keys }.take(SEARCH_LIMIT)
        results = found
        found.size.let { Usage.log(if (it == 0) Usage.Action.SEARCH_EMPTY else Usage.Action.SEARCH_SUBMIT, it) }
    }
    val r = results ?: return
    if (r.isEmpty()) return EmptyState("No matches for \"$q\".")
    ChannelBrowser(r, favKeys, Modifier.fillMaxSize(), side = true, header = "Results", entry = true) { Usage.log(Usage.Action.SEARCH_PICK, it) }
}

/** Sports' tiles, in their order, and the sports each one holds (sports without a tile go under More). */
private enum class SportTile(val label: String, val sports: List<Sport>, private val path: String) {
    Hockey("Hockey", listOf(Sport.Hockey), "M2 20v-3q0-.425.288-.712T3 16h1v4zm3 0v-4h4l.85-1.95l1.6 3.5l-.85 1.9q-.125.275-.375.413T9.7 20zm15 0v-4h1q.425 0 .713.288T22 17v3zm-1 0h-4.7q-.275 0-.525-.137t-.375-.413L6.35 4H9.7L12 9.2L14.3 4h3.35l-4.05 8.85L15 16h4z"),
    Soccer("Soccer", listOf(Sport.Soccer), "M12 22q-2.075 0-3.9-.788t-3.175-2.137T2.788 15.9T2 12t.788-3.9t2.137-3.175T8.1 2.788T12 2t3.9.788t3.175 2.137T21.213 8.1T22 12t-.788 3.9t-2.137 3.175t-3.175 2.138T12 22m5-12.5l1.35-.45l.4-1.35q-.8-1.2-1.925-2.062T14.35 4.35L13 5.3v1.4zm-10 0l4-2.8V5.3l-1.35-.95q-1.35.425-2.475 1.287T5.25 7.7l.4 1.35zm-1.05 7.7l1.15-.1l.75-1.35L6.4 11.4L5 10.9l-1 .75q0 1.625.45 2.962t1.5 2.588m7.325 2.7q.625-.1 1.225-.3l.7-1.5l-.65-1.1h-5.1l-.65 1.1l.7 1.5q.6.2 1.225.3T12 20t1.275-.1M9.75 15h4.5l1.4-4L12 8.45L8.4 11zm8.3 2.2q1.05-1.25 1.5-2.588T20 11.65l-1-.7l-1.4.45l-1.45 4.35l.75 1.35z"),
    Basketball("Basketball", listOf(Sport.Basketball), "M2.05 10.975q.125-1.275.525-2.412t1.1-2.138q.875.9 1.463 2.088t.762 2.462zm16.05 0q.175-1.275.75-2.45t1.475-2.075q.7.975 1.1 2.125t.525 2.4zM3.675 17.55q-.7-.975-1.1-2.113t-.525-2.412H5.9q-.175 1.275-.763 2.45T3.676 17.55m16.65 0q-.9-.9-1.475-2.075t-.75-2.45h3.85q-.125 1.25-.525 2.4t-1.1 2.125m-12.4-6.575q-.2-1.8-.975-3.325T4.925 4.9q1.2-1.2 2.738-1.925t3.312-.925v8.925zm5.1 0V2.05q1.775.2 3.313.925T19.075 4.9Q17.8 6.1 17.038 7.638t-.963 3.337zm-2.05 10.975q-1.8-.2-3.325-.937t-2.725-1.938q1.275-1.2 2.038-2.725t.962-3.325h3.05zm2.05 0v-8.925h3.05q.2 1.8.963 3.338t2.037 2.737q-1.2 1.2-2.738 1.925t-3.312.925"),
    AmericanFootball("Am. Football", listOf(Sport.AmericanFootball), "M9.075 20.975q-1.175.125-2.837-.062T3.7 20.3q-.35-.8-.587-2.5T3 14.9zm2.375-.4l-8.05-8.05q.425-1.875 1.238-3.412t1.887-2.638Q7.6 5.4 9.138 4.613t3.312-1.188l8.1 8.1q-.4 1.85-1.175 3.4t-1.85 2.625q-1.125 1.1-2.687 1.888t-3.388 1.137M9.4 16L16 9.4L14.6 8L8 14.6zm11.55-6.85l-6.075-6.125q1.2-.15 2.95.05T20.3 3.7q.45 1 .625 2.588t.025 2.862"),
    Baseball("Baseball", listOf(Sport.Baseball), "M4.175 18.225q-1.05-1.35-1.612-2.937T2 12t.563-3.287t1.612-2.938Q5.625 6.9 6.45 8.538T7.275 12t-.825 3.463t-2.275 2.762M8.563 21.4q-1.638-.6-2.988-1.75q1.725-1.425 2.7-3.412T9.25 12t-.975-4.237t-2.7-3.413Q6.925 3.2 8.563 2.6T12 2t3.438.6t2.987 1.75q-1.725 1.425-2.7 3.413T14.75 12t.975 4.238t2.7 3.412q-1.35 1.15-2.988 1.75T12 22t-3.437-.6m11.262-3.175q-1.45-1.125-2.275-2.762T16.725 12t.825-3.463t2.275-2.762q1.05 1.35 1.613 2.938T22 12t-.562 3.288t-1.613 2.937"),
    Cricket("Cricket", listOf(Sport.Cricket), "M15 14.2L12.2 17q-.3.3-.7.3t-.7-.3L2.3 8.5Q2 8.2 2 7.825t.3-.675l2.8-2.8q.3-.3.725-.3t.725.3L15 12.8q.3.3.3.7t-.3.7m3.6 7.8l-4.25-4.25l1.4-1.4L20 20.6zM16.025 7.975Q15 6.95 15 5.5t1.025-2.475T18.5 2t2.475 1.025T22 5.5t-1.025 2.475T18.5 9t-2.475-1.025"),
    Formula1("Formula 1", listOf(Sport.Motorsport), "M14.475 19.45H2.4q-.125 0-.225-.1t-.1-.225q0-1.175.038-2.062t.112-1.588h7.85q1.725 0 2.938-1.225t1.212-3q0-1.275-.712-2.312T11.6 7.4L8.25 6.075q1.35-.75 2.838-1.137t3.087-.388q3.2 0 5.475 2.163T21.925 12t-2.162 5.288t-5.288 2.162M2.55 13.5q.5-1.775 1.475-3.338T6.35 7.4l4.5 1.825q.65.275 1.025.825t.375 1.2q0 .95-.637 1.6t-1.588.65z"),
    Fighting("UFC & Boxing", listOf(Sport.Combat), "M8 21q-.425 0-.712-.288T7 20v-3h10v3q0 .425-.288.713T16 21zM19 8v3q0 .075-.05.2L18.2 15q-.075.45-.413.725T17 16H7q-.45 0-.788-.275T5.8 15l-.75-3.8Q5 11.075 5 11V5q0-.825.588-1.412T7 3h8q.825 0 1.413.588T17 5v3q0-.425.288-.712T18 7t.713.288T19 8M8 10h6V7H8z"),
    Tennis("Tennis", listOf(Sport.Tennis), "M3.425 20L2 18.6l4.1-4.1q.775-.775 1.063-1.937T7.45 9q0-1.45.65-2.85t1.85-2.6q2.275-2.275 5.025-2.575T19.5 2.5q1.8 1.8 1.5 4.55t-2.55 5q-1.2 1.2-2.6 1.85t-2.85.65q-2.425 0-3.55.275T7.525 15.9zm6.875-8.35q1.175 1.15 3.175.85t3.575-1.875q1.6-1.6 1.913-3.588T18.1 3.925q-1.2-1.2-3.137-.9t-3.563 1.9Q9.825 6.5 9.488 8.488t.812 3.162m4.875 10.175Q14 20.65 14 19t1.175-2.825T18 15t2.825 1.175T22 19t-1.175 2.825T18 23t-2.825-1.175"),
    Golf("Golf", listOf(Sport.Golf), "M19.5 21q-.625 0-1.062-.437T18 19.5t.438-1.062T19.5 18t1.063.438T21 19.5t-.437 1.063T19.5 21M10 22q-2.5 0-4.25-.587T4 20q0-.575.825-1.025T7 18.25V20h2V2l8 3.9L11 9v9.05q2.15.125 3.575.663T16 20q0 .825-1.75 1.413T10 22"),
    More(
        "More sports",
        listOf(Sport.More, Sport.Rugby, Sport.AussieRules, Sport.Gaelic, Sport.Darts, Sport.HorseRacing, Sport.Cycling, Sport.Volleyball),
        "M6 14q-.825 0-1.412-.587T4 12t.588-1.412T6 10t1.413.588T8 12t-.587 1.413T6 14m6 0q-.825 0-1.412-.587T10 12t.588-1.412T12 10t1.413.588T14 12t-.587 1.413T12 14m6 0q-.825 0-1.412-.587T16 12t.588-1.412T18 10t1.413.588T20 12t-.587 1.413T18 14",
    ),
    ;

    /** Material Symbols' icon for it (Apache 2.0), as the mockup shows. */
    val icon: ImageVector by lazy {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.White)).build()
    }
}

/**
 * Sports on the TV: a glass row of sport tiles, then the focused channel's preview beside that
 * sport's live channels. Sorting is the Sports categories' own ([groupBySport]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SportsTab(profile: Profile) {
    val q = glassQuery(GlassTab.Sports).trim()
    if (q.length >= 2) return ChannelSearch(profile, q, sports = true)
    val dao = LocalContext.current.app.db.catalog()
    val cats = categories(profile, Kind.LIVE)
    val favKeys = rememberFavouriteKeys(rememberFavourites())
    var groups by remember { mutableStateOf<Map<Sport, List<ChannelEntity>>?>(null) }
    LaunchedEffect(cats) { groups = withContext(Dispatchers.IO) { sportsChannels(dao, cats) } }
    val g = groups ?: return EmptyState("Finding the sports channels…")
    val tiles = SportTile.entries.filter { t -> t.sports.any { !g[it].isNullOrEmpty() } }
    if (tiles.isEmpty()) return EmptyState("No sports channels on this line.")
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val tile = tiles.firstOrNull { it.name == picked } ?: tiles.first()
    val channels = remember(g, tile) { tile.sports.flatMap { g[it].orEmpty() }.distinctBy { "${it.sourceId}/${it.streamId}" } }
    val hover = remember { mutableStateOf<SportTile?>(null) }
    FollowFocus(hover) { picked = it.name }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.gx)) {
        Box(Modifier.fillMaxWidth().glass(RoundedCornerShape(26.gx))) {
            LazyRow(Modifier.focusRestorer().onFocusChanged { if (!it.hasFocus) hover.value = null }, contentPadding = PaddingValues(horizontal = 18.gx, vertical = 16.gx), horizontalArrangement = Arrangement.spacedBy(12.gx)) {
                items(tiles, key = { it.name }) { t ->
                    val on = t == tile
                    FocusBox(
                        onClick = { picked = t.name }, onFocus = { hover.value = t }, scale = 1.06f,
                        color = if (on) Glass.Selected else Glass.Fill, shape = RoundedCornerShape(16.gx),
                        modifier = entryPoint(on).size(112.gx, 92.gx), contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(t.icon, contentDescription = null, tint = if (on) Glass.OnSelected else Glass.Soft, modifier = Modifier.size(34.gx))
                            Spacer(Modifier.height(8.gx))
                            Text(t.label, fontSize = 14.gsp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = if (on) Glass.OnSelected else Glass.Soft)
                        }
                    }
                }
            }
        }
        ChannelBrowser(channels, favKeys, Modifier.weight(1f).fillMaxWidth(), side = true, header = "${tile.label} · live channels")
    }
}

private const val HALF_HOUR = 30 * 60_000L
private const val TIMELINE = 2 * 60 * 60_000L

/** The programme on at [t], if any (one that has ended is not "now"). */
private fun List<NowNext>.onAt(t: Long): NowNext? = firstOrNull { it.start <= t && it.stop > t }
