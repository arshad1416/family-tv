package app.familytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.familytv.data.ChannelEntity
import app.familytv.data.NowNext

private val GUIDE_W = 440.dp

private fun List<NowNext>?.nowAndNext(now: Long): Pair<NowNext?, NowNext?> {
    val upcoming = this.orEmpty().filter { it.stop > now }
    return upcoming.firstOrNull { it.start <= now } to upcoming.firstOrNull { it.start > now }
}

/**
 * Live TV overlays drawn over the video: the info banner along the bottom and the mini guide on
 * the left. PlayerActivity owns the state and the keys; this only draws and handles list focus.
 */
@Composable
fun LiveOverlay(
    channels: List<ChannelEntity>,
    playing: Int,
    banner: Int?,
    guideOpen: Boolean,
    programmes: Map<String, List<NowNext>>,
    onTune: (Int) -> Unit,
    onFocusChannel: (Int) -> Unit,
) {
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (guideOpen) MiniGuide(channels, playing, programmes, onTune, onFocusChannel)
        banner?.let { channels.getOrNull(it) }?.let { ch ->
            InfoBanner(ch, programmes[ch.epgId], Modifier.align(Alignment.BottomStart).padding(start = if (guideOpen) GUIDE_W else 0.dp))
        }
    }
}

@Composable
private fun InfoBanner(ch: ChannelEntity, progs: List<NowNext>?, modifier: Modifier) {
    val now = System.currentTimeMillis()
    val (cur, next) = progs.nowAndNext(now)
    Box(
        modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6060A14), Color(0xF2060A14)))),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${ch.num}", fontSize = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 20.dp))
            Logo(ch.logo, 76)
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Text(ch.name, fontSize = 16.sp, color = Tv.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(cur?.title ?: "No guide information", fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (cur != null) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${time(cur.start)} – ${time(cur.stop)}", fontSize = 15.sp, color = Tv.Muted)
                    Spacer(Modifier.width(14.dp))
                    ProgressLine(progress(cur.start, cur.stop, now), Modifier.width(260.dp), height = 4.dp)
                }
                next?.let { Text("Next: ${time(it.start)}  ${it.title}", fontSize = 15.sp, color = Tv.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
            }
            Text(time(now), fontSize = 22.sp, color = Tv.Muted, modifier = Modifier.padding(start = 24.dp))
        }
    }
}

@Composable
private fun MiniGuide(channels: List<ChannelEntity>, playing: Int, programmes: Map<String, List<NowNext>>, onTune: (Int) -> Unit, onFocusChannel: (Int) -> Unit) {
    val current = remember { FocusRequester() }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (playing - 3).coerceAtLeast(0))
    LaunchedEffect(Unit) {
        // The playing row is composed on the first layout pass; try for a few frames.
        repeat(5) { withFrameNanos { }; if (runCatching { current.requestFocus() }.isSuccess) return@LaunchedEffect }
    }
    val now = System.currentTimeMillis()
    Column(Modifier.width(GUIDE_W).fillMaxHeight().background(Color(0xF2060A14)).padding(top = 32.dp, start = 20.dp, end = 16.dp)) {
        Text("Channels", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
        Text("OK to watch · Back to close", fontSize = 13.sp, color = Tv.Muted, modifier = Modifier.padding(start = 8.dp, bottom = 14.dp))
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            itemsIndexed(channels, key = { _, c -> c.streamId }) { i, c ->
                val cur = programmes[c.epgId].nowAndNext(now).first
                FocusBox(
                    onClick = { onTune(i) },
                    onFocus = { onFocusChannel(i) },
                    selected = i == playing,
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth().height(64.dp).then(if (i == playing) Modifier.focusRequester(current) else Modifier),
                ) { focused ->
                    Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.num}", Modifier.width(44.dp), fontSize = 15.sp, color = if (i == playing && !focused) Tv.Accent else Tv.Text)
                        Logo(c.logo, 40)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(cur?.title ?: "", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (focused) Tv.Text else Tv.Muted)
                            if (cur != null) ProgressLine(progress(cur.start, cur.stop, now), Modifier.padding(top = 4.dp), height = 2.dp, fill = if (focused) Tv.Text else Tv.Accent)
                        }
                    }
                }
            }
        }
    }
}
