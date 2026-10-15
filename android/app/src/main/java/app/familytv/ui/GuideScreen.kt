package app.familytv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.data.CategoryEntity
import app.familytv.data.ChannelEntity
import app.familytv.data.Kind
import app.familytv.data.NowNext
import app.familytv.data.ProgrammeEntity
import app.familytv.portal.Profile
import app.familytv.trailer.ShowExtras
import app.familytv.user.Favourites
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

internal const val SLOT = 30 * 60_000L
internal const val HOURS = 6

/** Grid geometry per form factor. TV keeps its original sizes; a phone pins only logo and number. */
internal class GuideDims(val slotW: Dp, val chanW: Dp, val rowH: Dp) {
    val gridW = slotW * (HOURS * 2)
    fun span(ms: Long): Dp = slotW * (ms.toFloat() / SLOT)
}

internal val TV_DIMS = GuideDims(slotW = 190.dp, chanW = 230.dp, rowH = 60.dp)
private val PHONE_DIMS = GuideDims(slotW = 132.dp, chanW = 64.dp, rowH = 64.dp)
private val TABLET_DIMS = GuideDims(slotW = 170.dp, chanW = 200.dp, rowH = 64.dp)

/** Height of the player guide's small video window (16:9). */
internal val PICTURE_H = 176.dp

// Android TV's default keeps the focused item pinned 30% in, which would jerk every row sideways
// on each Up/Down; the interface default only scrolls as far as needed to show the cell.
@OptIn(ExperimentalFoundationApi::class)
private val MinimalScroll = object : BringIntoViewSpec {}

internal data class GuideFocus(val channel: ChannelEntity, val prog: NowNext?)

/**
 * Classic grid guide: channels down the left, half-hour slots across, six hours from the
 * current half hour. Every row and the time bar share one horizontal ScrollState, so moving
 * focus right in one row scrolls them all. Programmes are loaded per category, as before.
 */
@Composable
fun GuideScreen(vm: MainViewModel, profile: Profile) {
    val ctx = LocalContext.current
    val visible = categories(profile, Kind.LIVE)
    // Favourites leads the chips, as on the channel list; the guide still opens on the first real category.
    val cats = remember(visible) { if (visible.isEmpty()) visible else listOf(favouritesCategory(Kind.LIVE)) + visible }
    var cat by remember { mutableStateOf<CategoryEntity?>(null) }
    LaunchedEffect(cats) { if (cat == null || cat !in cats) cat = visible.firstOrNull() }
    val favs = rememberFavourites()
    val favKeys = rememberFavouriteKeys(favs)
    val favChannels = rememberFavouriteChannels(favs, visible)
    val inCategory by remember(cat) {
        cat?.takeUnless { it.isFavourites }?.let { ctx.app.db.catalog().channels(it.sourceId, it.categoryId) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val showingFavourites = cat?.isFavourites == true
    val channels = if (showingFavourites) favChannels else inCategory
    val now = produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val start by remember { derivedStateOf { now.value / SLOT * SLOT } }
    val progs = rememberProgrammes(channels, from = start, hours = HOURS)
    val focus = remember { mutableStateOf<GuideFocus?>(null) }
    val scroll = rememberScrollState()
    val form = LocalFormFactor.current
    val d = when (form) { FormFactor.Tv -> TV_DIMS; FormFactor.Phone -> PHONE_DIMS; FormFactor.Tablet -> TABLET_DIMS }
    val scope = rememberCoroutineScope()
    if (cats.isEmpty()) return EmptyState("No guide yet.")
    Column {
        // The TV header follows D-pad focus; touch has no focus to follow, so it gets a jump-to-now bar instead.
        if (form.touch) {
            CategoryChips(cats, cat) { cat = it }
            Spacer(Modifier.height(8.dp))
            NowBar(now) { scope.launch { scroll.animateScrollTo(0) } }
        } else {
            GuideHeader(focus, now)
            CategoryChips(cats, cat) { cat = it }
        }
        Spacer(Modifier.height(if (form.touch) 4.dp else 12.dp))
        TimeBar(d, start, scroll, now)
        Spacer(Modifier.height(6.dp))
        if (showingFavourites && channels.isEmpty()) {
            FavouritesHint(
                (if (form.touch) "Long-press a channel or show in the guide" else "Hold OK (or press Menu) on a channel or show in the guide") + " to add it here. " +
                    "Favourite channels stay on this device: Trakt has no live TV, so they don't sync.",
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            itemsIndexed(channels, key = { _, c -> "${c.sourceId}/${c.streamId}" }) { i, c ->
                GuideRow(
                    d, c, c.epgId?.let { progs[it] }.orEmpty(), start, scroll, now, entry = i == 0, compact = form == FormFactor.Phone,
                    favourite = Favourites.key(Kind.LIVE, c.sourceId, c.streamId) in favKeys,
                    onFocus = { focus.value = GuideFocus(c, it) },
                    onPlay = { if (showingFavourites) playLive(ctx, c.sourceId, c.categoryId, 0, c.streamId) else playLive(ctx, c.sourceId, c.categoryId, i) },
                    onFavourite = { toggleFavourite(ctx, Kind.LIVE, c.sourceId, c.streamId, c.name) },
                )
            }
        }
    }
}

/**
 * The focused programme's details: title, episode, genre, rating and year, the description and
 * cast, and its picture. [picture] (the player's small video window) takes the picture's place at
 * the left when given. Where the guide left the description or picture out, TMDB or TVmaze fills
 * them in, and the header says so.
 */
@Composable
internal fun GuideHeader(focus: MutableState<GuideFocus?>, now: State<Long>, picture: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current
    val f = focus.value
    // Details are read only for the cell that focus rests on.
    var detail by remember { mutableStateOf<ProgrammeEntity?>(null) }
    var extras by remember { mutableStateOf<ShowExtras?>(null) }
    LaunchedEffect(f) {
        detail = null
        extras = null
        val p = f?.prog ?: return@LaunchedEffect
        delay(250)
        val row = ctx.app.db.catalog().programme(p.epgId, p.start) ?: return@LaunchedEffect
        detail = row
        delay(250)
        extras = ctx.app.extras.showExtras(row)
    }
    val d = detail
    val desc = d?.description?.takeIf { it.isNotBlank() && (it.length >= 40 || extras?.overview == null) } ?: extras?.overview
    val image = d?.icon ?: extras?.image
    Row(Modifier.fillMaxWidth().height(if (picture != null) PICTURE_H else HEADER_H)) {
        when {
            picture != null -> { picture(); Spacer(Modifier.width(24.dp)) }
            image != null -> {
                AsyncImage(
                    image, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.width(HEADER_H * 16 / 9).fillMaxHeight().background(Tv.Panel, RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(20.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(f?.prog?.title ?: f?.channel?.name ?: "Guide", fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            f?.let {
                val p = it.prog
                Text(
                    listOfNotNull("${it.channel.num}  ${it.channel.name}", p?.let { "${time(it.start)} – ${time(it.stop)}  ·  ${(it.stop - it.start) / 60_000} min" }).joinToString("   ·   "),
                    fontSize = 15.sp, color = Tv.Accent, maxLines = 1, modifier = Modifier.padding(top = 2.dp),
                )
            }
            d?.let(::programmeFacts)?.let { Text(it, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
            desc?.let { Text(it, fontSize = 14.sp, lineHeight = 19.sp, color = Tv.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
            val credit = extras?.takeIf { e -> (e.overview != null && desc == e.overview) || (e.image != null && image == e.image) }?.let { "Details from ${it.source}" }
            listOfNotNull(d?.cast?.let { "With $it" }, credit).joinToString("   ·   ").ifEmpty { null }?.let {
                Text(it, fontSize = 12.sp, color = Tv.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Text(time(now.value), fontSize = 20.sp, color = Tv.Muted, modifier = Modifier.padding(start = 24.dp))
    }
}

private val HEADER_H = 140.dp

/** "S2 E5  ·  Episode title  ·  Drama, Comedy  ·  14+  ·  2019  ·  NEW": what the guide knows beyond the title. */
internal fun programmeFacts(p: ProgrammeEntity): String? = listOfNotNull(
    when {
        p.season != null && p.episode != null -> "S${p.season} E${p.episode}"
        p.episode != null -> "Episode ${p.episode}"
        else -> null
    },
    p.subTitle?.takeIf { !it.equals(p.title, ignoreCase = true) },
    p.category,
    p.rating,
    p.year?.toString(),
    if (p.isNew) "NEW" else null,
).joinToString("  ·  ").ifEmpty { null }

/** Touch guide: the clock and a button that scrolls the grid back to the current half hour. */
@Composable
private fun NowBar(now: State<Long>, onNow: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Now ${time(now.value)}", fontSize = 15.sp, color = Tv.Muted, modifier = Modifier.weight(1f))
        TvButton("Jump to now", onNow)
    }
}

@Composable
internal fun TimeBar(d: GuideDims, start: Long, scroll: ScrollState, now: State<Long>) {
    Row(Modifier.fillMaxWidth().height(30.dp)) {
        Box(Modifier.width(d.chanW).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            Text("Today", fontSize = 14.sp, color = Tv.Muted, maxLines = 1, modifier = Modifier.padding(start = 10.dp))
        }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll, enabled = false)) {
            Box(Modifier.width(d.gridW).fillMaxHeight().nowLine(d, start, now, Tv.Accent)) {
                repeat(HOURS * 2) { k ->
                    Box(Modifier.offset(x = d.slotW * k).width(d.slotW).fillMaxHeight().padding(start = 1.dp).background(Tv.Panel), contentAlignment = Alignment.CenterStart) {
                        Text(time(start + k * SLOT), fontSize = 13.sp, color = Tv.Muted, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GuideRow(
    d: GuideDims,
    c: ChannelEntity,
    list: List<NowNext>,
    start: Long,
    scroll: ScrollState,
    now: State<Long>,
    entry: Boolean,
    compact: Boolean,
    favourite: Boolean,
    onFocus: (NowNext?) -> Unit,
    onPlay: () -> Unit,
    onFavourite: () -> Unit,
) {
    val end = start + HOURS * 3_600_000L
    CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
        Row(Modifier.fillMaxWidth().height(d.rowH)) {
            if (compact) {
                // Phone: logo over number, pinned left, and the whole tile tunes like the cells do.
                FocusBox(onClick = onPlay, onLongClick = onFavourite, modifier = Modifier.width(d.chanW).fillMaxHeight(), shape = RoundedCornerShape(8.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Logo(c.logo, 34)
                        Text("${c.num}", fontSize = 12.sp, color = Tv.Muted, maxLines = 1)
                    }
                    MarkBadge(Modifier.align(Alignment.TopEnd), favourite = favourite, size = 16.dp)
                }
            } else {
                Row(Modifier.width(d.chanW).fillMaxHeight().background(Tv.Panel, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${c.num}", Modifier.width(42.dp), fontSize = 14.sp, color = Tv.Muted)
                    Logo(c.logo, 40)
                    Spacer(Modifier.width(10.dp))
                    Text(c.name, Modifier.weight(1f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (favourite) MarkBadge(favourite = true, size = 18.dp)
                }
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                Box(Modifier.width(d.gridW).fillMaxHeight().nowLine(d, start, now, Tv.Accent)) {
                    val cells = list.filter { minOf(it.stop, end) > maxOf(it.start, start) }
                    if (cells.isEmpty()) {
                        GuideCell(if (compact) c.name else "No guide information", if (compact) "No guide information" else null, 0.dp, d.slotW * 4, onAir = false, entryPoint(entry), { onFocus(null) }, onPlay, onFavourite)
                    }
                    cells.forEachIndexed { i, p ->
                        val s = maxOf(p.start, start)
                        GuideCell(
                            p.title, "${time(p.start)} – ${time(p.stop)}", d.span(s - start), d.span(minOf(p.stop, end) - s),
                            onAir = p.start <= now.value && now.value < p.stop, entryPoint(entry && i == 0), { onFocus(p) }, onPlay, onFavourite,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideCell(title: String, times: String?, x: Dp, w: Dp, onAir: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
    // Holding OK (or Menu, or a long press) on any cell favourites its channel, so the guide can stand in for the channel list.
    FocusBox(
        onClick = onClick, onFocus = onFocus, onLongClick = onLongClick, color = if (onAir) Tv.Raised else Tv.Panel, shape = RoundedCornerShape(6.dp),
        modifier = modifier.offset(x = x).width((w - 3.dp).coerceAtLeast(4.dp)).fillMaxHeight(),
    ) { focused ->
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            times?.let { Text(it, fontSize = 12.sp, maxLines = 1, color = if (focused) Tv.Text else Tv.Muted) }
        }
    }
}

/** Vertical "now" marker; reads the clock at draw time so ticking doesn't recompose rows. */
private fun Modifier.nowLine(d: GuideDims, start: Long, now: State<Long>, color: Color) = drawWithContent {
    drawContent()
    val x = (now.value - start).toFloat() / SLOT * d.slotW.toPx()
    drawRect(color, topLeft = Offset(x - 1.dp.toPx(), 0f), size = Size(2.dp.toPx(), size.height))
}
