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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import app.familytv.portal.Profile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf

private const val SLOT = 30 * 60_000L
private const val HOURS = 6
private val SLOT_W = 190.dp
private val CHAN_W = 230.dp
private val ROW_H = 60.dp
private val GRID_W = SLOT_W * (HOURS * 2)

private fun span(ms: Long): Dp = SLOT_W * (ms.toFloat() / SLOT)

// Android TV's default keeps the focused item pinned 30% in, which would jerk every row sideways
// on each Up/Down; the interface default only scrolls as far as needed to show the cell.
@OptIn(ExperimentalFoundationApi::class)
private val MinimalScroll = object : BringIntoViewSpec {}

private data class GuideFocus(val channel: ChannelEntity, val prog: NowNext?)

/**
 * Classic grid guide: channels down the left, half-hour slots across, six hours from the
 * current half hour. Every row and the time bar share one horizontal ScrollState, so moving
 * focus right in one row scrolls them all. Programmes are loaded per category, as before.
 */
@Composable
fun GuideScreen(vm: MainViewModel, profile: Profile) {
    val ctx = LocalContext.current
    val cats = categories(vm, profile, Kind.LIVE)
    var cat by remember { mutableStateOf<CategoryEntity?>(null) }
    LaunchedEffect(cats) { if (cat == null || cat !in cats) cat = cats.firstOrNull() }
    val channels by remember(cat) {
        cat?.let { ctx.app.db.catalog().channels(it.sourceId, it.categoryId) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val now = produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val start by remember { derivedStateOf { now.value / SLOT * SLOT } }
    val progs = rememberProgrammes(channels, from = start, hours = HOURS)
    val focus = remember { mutableStateOf<GuideFocus?>(null) }
    val scroll = rememberScrollState()
    if (cats.isEmpty()) return EmptyState("No guide yet.")
    Column {
        GuideHeader(focus, now)
        CategoryChips(cats, cat) { cat = it }
        Spacer(Modifier.height(12.dp))
        TimeBar(start, scroll, now)
        Spacer(Modifier.height(6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            itemsIndexed(channels, key = { _, c -> c.streamId }) { i, c ->
                GuideRow(
                    c, c.epgId?.let { progs[it] }.orEmpty(), start, scroll, now, entry = i == 0,
                    onFocus = { focus.value = GuideFocus(c, it) },
                    onPlay = { playLive(ctx, c.sourceId, c.categoryId, i) },
                )
            }
        }
    }
}

@Composable
private fun GuideHeader(focus: MutableState<GuideFocus?>, now: State<Long>) {
    val ctx = LocalContext.current
    val f = focus.value
    // Description is fetched only for the cell that focus rests on.
    var desc by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(f) {
        desc = null
        desc = f?.prog?.let { p -> delay(250); ctx.app.db.catalog().description(p.epgId, p.start) }
    }
    Row(Modifier.fillMaxWidth().height(128.dp)) {
        Column(Modifier.weight(1f)) {
            Text(f?.prog?.title ?: f?.channel?.name ?: "Guide", fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            f?.let {
                val p = it.prog
                Text(
                    listOfNotNull("${it.channel.num}  ${it.channel.name}", p?.let { "${time(it.start)} – ${time(it.stop)}  ·  ${(it.stop - it.start) / 60_000} min" }).joinToString("   ·   "),
                    fontSize = 15.sp, color = Tv.Accent, maxLines = 1, modifier = Modifier.padding(top = 4.dp),
                )
            }
            desc?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 14.sp, color = Tv.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
        }
        Text(time(now.value), fontSize = 20.sp, color = Tv.Muted, modifier = Modifier.padding(start = 24.dp))
    }
}

@Composable
private fun TimeBar(start: Long, scroll: ScrollState, now: State<Long>) {
    Row(Modifier.fillMaxWidth().height(30.dp)) {
        Box(Modifier.width(CHAN_W).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            Text("Today", fontSize = 14.sp, color = Tv.Muted, modifier = Modifier.padding(start = 10.dp))
        }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll, enabled = false)) {
            Box(Modifier.width(GRID_W).fillMaxHeight().nowLine(start, now, Tv.Accent)) {
                repeat(HOURS * 2) { k ->
                    Box(Modifier.offset(x = SLOT_W * k).width(SLOT_W).fillMaxHeight().padding(start = 1.dp).background(Tv.Panel), contentAlignment = Alignment.CenterStart) {
                        Text(time(start + k * SLOT), fontSize = 13.sp, color = Tv.Muted, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideRow(
    c: ChannelEntity,
    list: List<NowNext>,
    start: Long,
    scroll: ScrollState,
    now: State<Long>,
    entry: Boolean,
    onFocus: (NowNext?) -> Unit,
    onPlay: () -> Unit,
) {
    val end = start + HOURS * 3_600_000L
    CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
        Row(Modifier.fillMaxWidth().height(ROW_H)) {
            Row(Modifier.width(CHAN_W).fillMaxHeight().background(Tv.Panel, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${c.num}", Modifier.width(42.dp), fontSize = 14.sp, color = Tv.Muted)
                Logo(c.logo, 40)
                Spacer(Modifier.width(10.dp))
                Text(c.name, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                Box(Modifier.width(GRID_W).fillMaxHeight().nowLine(start, now, Tv.Accent)) {
                    val cells = list.filter { minOf(it.stop, end) > maxOf(it.start, start) }
                    if (cells.isEmpty()) {
                        GuideCell("No guide information", null, 0.dp, SLOT_W * 4, onAir = false, entryPoint(entry), { onFocus(null) }, onPlay)
                    }
                    cells.forEachIndexed { i, p ->
                        val s = maxOf(p.start, start)
                        GuideCell(
                            p.title, "${time(p.start)} – ${time(p.stop)}", span(s - start), span(minOf(p.stop, end) - s),
                            onAir = p.start <= now.value && now.value < p.stop, entryPoint(entry && i == 0), { onFocus(p) }, onPlay,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideCell(title: String, times: String?, x: Dp, w: Dp, onAir: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    FocusBox(
        onClick = onClick, onFocus = onFocus, color = if (onAir) Tv.Raised else Tv.Panel, shape = RoundedCornerShape(6.dp),
        modifier = modifier.offset(x = x).width((w - 3.dp).coerceAtLeast(4.dp)).fillMaxHeight(),
    ) { focused ->
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            times?.let { Text(it, fontSize = 12.sp, maxLines = 1, color = if (focused) Tv.Text else Tv.Muted) }
        }
    }
}

/** Vertical "now" marker; reads the clock at draw time so ticking doesn't recompose rows. */
private fun Modifier.nowLine(start: Long, now: State<Long>, color: Color) = drawWithContent {
    drawContent()
    val x = (now.value - start).toFloat() / SLOT * SLOT_W.toPx()
    drawRect(color, topLeft = Offset(x - 1.dp.toPx(), 0f), size = Size(2.dp.toPx(), size.height))
}
