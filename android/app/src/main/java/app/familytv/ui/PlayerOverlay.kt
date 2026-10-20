package app.familytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.data.ChannelEntity
import app.familytv.data.NowNext
import app.familytv.data.ProgrammeEntity
import app.familytv.data.displayName
import kotlin.math.abs

private val GUIDE_W = 440.dp

private fun List<NowNext>?.nowAndNext(now: Long): Pair<NowNext?, NowNext?> {
    val upcoming = this.orEmpty().filter { it.stop > now }
    return upcoming.firstOrNull { it.start <= now } to upcoming.firstOrNull { it.start > now }
}

/**
 * Live TV overlays drawn over the video: the info banner along the bottom and the mini guide on
 * the left. PlayerController owns the state and the keys; this only draws and handles list focus.
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
    touch: TouchControls? = null,
) {
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        // Touch: a tap on the video shows the controls (or closes the guide), and a swipe up or down
        // changes channel like the remote's up and down; it sits under everything else.
        if (touch != null) Box(
            Modifier.fillMaxSize()
                .pointerInput(touch.onZap) {
                    var drag = 0f
                    detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                        if (abs(drag) > SWIPE_DP.toPx()) touch.onZap(if (drag < 0) -1 else 1)
                    }) { _, dy -> drag += dy }
                }
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = touch.onTap),
        )
        if (guideOpen) MiniGuide(channels, playing, programmes, onTune, onFocusChannel, focusPlaying = touch == null)
        // The info bar hides while the list is open (backlog item 25): beside it, it was squashed, and the list already shows what's on.
        if (!guideOpen) banner?.let { channels.getOrNull(it) }?.let { ch ->
            InfoBanner(ch, programmes[ch.epgId], Modifier.align(Alignment.BottomStart), hint = if (touch == null) "→  Captions & info" else null)
        }
        if (touch != null && touch.visible && !guideOpen) {
            RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Close player", Modifier.align(Alignment.TopStart).padding(16.dp), touch.onClose)
            Row(Modifier.align(Alignment.TopEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CaptionButton(touch.captionsOn, onClick = touch.onCaptions)
                touch.onTracks?.let { RoundIcon(Icons.Filled.Settings, "Picture and sound", onClick = it) }
                RoundIcon(Icons.Filled.Info, "Stream info", onClick = touch.onInfo)
            }
            Column(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RoundIcon(Icons.Filled.KeyboardArrowUp, "Previous channel", onClick = { touch.onZap(-1) })
                RoundIcon(Icons.AutoMirrored.Filled.List, "Channel guide", onClick = touch.onGuide)
                RoundIcon(Icons.Filled.KeyboardArrowDown, "Next channel", onClick = { touch.onZap(1) })
            }
        }
    }
}

private val SWIPE_DP = 48.dp

/** Touch controls for live TV on phones and tablets; null on TV, where the remote drives everything. */
class TouchControls(
    val visible: Boolean,
    val onTap: () -> Unit,
    val onZap: (Int) -> Unit,
    val onGuide: () -> Unit,
    val onClose: () -> Unit,
    val captionsOn: Boolean = false,
    val onCaptions: () -> Unit = {},
    /** Picture format, and audio/video tracks when there is a choice; null hides the button. */
    val onTracks: (() -> Unit)? = null,
    val onInfo: () -> Unit = {},
)

@Composable
fun RoundIcon(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, color = Color(0x99000000), shape = CircleShape, modifier = modifier.size(56.dp), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = Tv.Text, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun InfoBanner(ch: ChannelEntity, progs: List<NowNext>?, modifier: Modifier, hint: String? = null) {
    val now = System.currentTimeMillis()
    val (cur, next) = progs.nowAndNext(now)
    // The current programme's episode, genre and description, from the guide.
    val db = LocalContext.current.app.db
    var detail by remember { mutableStateOf<ProgrammeEntity?>(null) }
    LaunchedEffect(cur) { detail = cur?.let { db.catalog().programme(it.epgId, it.start) } }
    Box(
        modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6060A14), Color(0xF2060A14)))),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${ch.num}", fontSize = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 20.dp))
            Logo(ch.logo, 76)
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Text(ch.displayName, fontSize = 16.sp, color = Tv.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(cur?.title ?: "No guide information", fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                detail?.let(::programmeFacts)?.let { Text(it, fontSize = 15.sp, color = Tv.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
                detail?.description?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 14.sp, color = Tv.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
                if (cur != null) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${time(cur.start)} – ${time(cur.stop)}", fontSize = 15.sp, color = Tv.Muted)
                    Spacer(Modifier.width(14.dp))
                    ProgressLine(progress(cur.start, cur.stop, now), Modifier.width(260.dp), height = 4.dp)
                }
                next?.let { Text("Next: ${time(it.start)}  ${it.title}", fontSize = 15.sp, color = Tv.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
            }
            Column(Modifier.padding(start = 24.dp), horizontalAlignment = Alignment.End) {
                Text(time(now), fontSize = 22.sp, color = Tv.Muted)
                hint?.let { Text(it, fontSize = 13.sp, color = Tv.Muted, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
    }
}

@Composable
private fun MiniGuide(channels: List<ChannelEntity>, playing: Int, programmes: Map<String, List<NowNext>>, onTune: (Int) -> Unit, onFocusChannel: (Int) -> Unit, focusPlaying: Boolean) {
    val current = remember { FocusRequester() }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (playing - 3).coerceAtLeast(0))
    if (focusPlaying) LaunchedEffect(Unit) {
        // The playing row is composed on the first layout pass; try for a few frames.
        repeat(5) { withFrameNanos { }; if (runCatching { current.requestFocus() }.isSuccess) return@LaunchedEffect }
    }
    val now = System.currentTimeMillis()
    Column(Modifier.width(GUIDE_W).fillMaxHeight().background(Color(0xF2060A14)).padding(top = 32.dp, start = 20.dp, end = 16.dp)) {
        Text("Channels", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
        Text(if (focusPlaying) "OK to watch · Back to close" else "Tap to watch · Back to close", fontSize = 13.sp, color = Tv.Muted, modifier = Modifier.padding(start = 8.dp, bottom = 14.dp))
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.displayName, Modifier.weight(1f, fill = false), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (i == playing) PlayingTag(Modifier.padding(start = 8.dp), onAccent = focused)
                            }
                            Text(cur?.title ?: "", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (focused) Tv.Text else Tv.Muted)
                            if (cur != null) ProgressLine(progress(cur.start, cur.stop, now), Modifier.padding(top = 4.dp), height = 2.dp, fill = if (focused) Tv.Text else Tv.Accent)
                        }
                    }
                }
            }
        }
    }
}
