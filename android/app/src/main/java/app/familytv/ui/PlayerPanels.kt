package app.familytv.ui

import app.familytv.core.foregroundTicks
import androidx.lifecycle.compose.LocalLifecycleOwner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.familytv.player.Aspect
import app.familytv.player.InfoSection
import app.familytv.player.OnlineSubtitles
import app.familytv.player.PlayerTracks
import app.familytv.player.StreamInfo
import app.familytv.player.StreamStats
import app.familytv.player.TrackLabels
import kotlinx.coroutines.delay
import java.util.Locale

/** The player's side panels. Menu is the TV entry point (D-pad right / Menu key) to the other three. */
enum class PlayerPanel { Menu, Captions, Tracks, Info }

private val PanelBg = Color(0xF2060A14)

/**
 * Draws the open panel on the right. On TV the list panels take D-pad focus (PlayerActivity hands
 * focus to this ComposeView); Info is passive so the remote keeps zapping or seeking while the
 * numbers update. On touch, a tap outside the panel closes it.
 */
@Composable
fun PlayerPanels(
    panel: PlayerPanel?,
    tracks: PlayerTracks,
    online: OnlineSubtitles?,
    live: Boolean,
    touch: Boolean,
    aspect: Aspect,
    onAspect: (Aspect) -> Unit,
    stats: () -> StreamStats,
    onOpen: (PlayerPanel) -> Unit,
    onClose: () -> Unit,
) {
    if (panel == null) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (touch) Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose))
        val wide = panel == PlayerPanel.Info && maxWidth >= 720.dp
        val width = if (wide) 680.dp else minOf(420.dp, maxWidth)
        Column(
            Modifier.align(Alignment.CenterEnd).width(width).fillMaxHeight().background(PanelBg)
                // Swallow taps on the panel itself so they don't reach the scrim behind.
                .pointerInput(Unit) { detectTapGestures { } }
                .safeDrawingPadding().padding(start = 20.dp, end = 16.dp, top = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                Text(panelTitle(panel), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (touch) FocusBox(onClick = onClose, color = Color.Transparent, shape = CircleShape, modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Tv.Text, modifier = Modifier.size(24.dp))
                }
            }
            Text(
                if (touch) "Tap outside to close" else if (panel == PlayerPanel.Info) "Info or Back to close" else "OK to choose · Back to close",
                fontSize = 13.sp, color = Tv.Muted, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
            )
            when (panel) {
                PlayerPanel.Info -> InfoContent(stats, wide)
                else -> {
                    val first = remember(panel) { FocusRequester() }
                    if (!touch) LaunchedEffect(panel) {
                        repeat(5) { withFrameNanos { }; if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                        when (panel) {
                            PlayerPanel.Menu -> menuRows(tracks, aspect, stats, first, onOpen)
                            PlayerPanel.Captions -> captionRows(tracks, online, live, first, onClose)
                            PlayerPanel.Tracks -> trackRows(tracks, aspect, onAspect, first)
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

private fun panelTitle(p: PlayerPanel) = when (p) {
    PlayerPanel.Menu -> "Options"
    PlayerPanel.Captions -> "Captions"
    PlayerPanel.Tracks -> "Picture & sound"
    PlayerPanel.Info -> "Stream info"
}

@Composable
private fun PanelRow(title: String, subtitle: String? = null, selected: Boolean = false, focus: FocusRequester? = null, onClick: () -> Unit) {
    val minH = if (LocalFormFactor.current.touch) 48.dp else 52.dp
    FocusBox(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().heightIn(min = minH).then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
    ) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (selected && !focused) Tv.Accent else Tv.Text)
                subtitle?.let { Text(it, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (focused) Tv.Text else Tv.Muted) }
            }
            if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = if (focused) Tv.Text else Tv.Accent, modifier = Modifier.padding(start = 8.dp).size(20.dp))
        }
    }
}

private fun LazyListScope.header(text: String) = item(key = "h-$text") {
    Text(text.uppercase(Locale.getDefault()), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Tv.Muted, modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp))
}

private fun LazyListScope.note(key: String, text: String) = item(key = key) {
    Text(text, fontSize = 13.sp, color = Tv.Muted, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
}

private fun LazyListScope.menuRows(tracks: PlayerTracks, aspect: Aspect, stats: () -> StreamStats, first: FocusRequester, onOpen: (PlayerPanel) -> Unit) {
    item(key = "cc") { PanelRow("Captions", tracks.captionSummary(), focus = first) { onOpen(PlayerPanel.Captions) } }
    item(key = "tracks") {
        val counts = if (tracks.hasChoices) " · ${tracks.audio.size} audio · ${tracks.video.size} video" else ""
        PanelRow("Picture & sound", "Picture: ${aspect.label}$counts") { onOpen(PlayerPanel.Tracks) }
    }
    item(key = "info") { PanelRow("Stream info", StreamInfo.summary(stats()) ?: "Codec, resolution, bitrate") { onOpen(PlayerPanel.Info) } }
}

private fun LazyListScope.captionRows(tracks: PlayerTracks, online: OnlineSubtitles?, live: Boolean, first: FocusRequester, onClose: () -> Unit) {
    item(key = "off") { PanelRow("Off", selected = !tracks.captionsOn, focus = first) { tracks.captionsOff(); onClose() } }
    if (tracks.text.isEmpty()) {
        note("none", if (live) "This channel isn't sending captions." else "This video has no captions of its own.")
    } else {
        tracks.text.forEachIndexed { i, c ->
            item(key = "t$i-${c.label}") { PanelRow(c.label, selected = tracks.captionsOn && c.selected) { tracks.selectText(c); onClose() } }
        }
        if (live && tracks.captionsOn && !tracks.cuesSeen) note("cc-wait", "Captions appear when the channel broadcasts them.")
    }
    if (online == null) return
    header("Online subtitles · OpenSubtitles")
    when (online.phase) {
        OnlineSubtitles.Phase.Idle -> item(key = "search") { PanelRow("Find subtitles online", "Matched to this title") { online.search() } }
        OnlineSubtitles.Phase.Searching -> note("searching", "Searching…")
        OnlineSubtitles.Phase.Loading -> note("loading", "Loading subtitles…")
        OnlineSubtitles.Phase.Empty -> {
            note("empty", "No subtitles found online for this title.")
            item(key = "again") { PanelRow("Search again") { online.search() } }
        }
        OnlineSubtitles.Phase.Error -> {
            note("error", online.message ?: "Something went wrong.")
            item(key = "retry") { PanelRow("Try again") { online.search() } }
        }
        OnlineSubtitles.Phase.Results -> online.hits.forEach { h ->
            item(key = "os-${h.fileId}") {
                val lang = TrackLabels.language(h.language) ?: h.language.uppercase(Locale.ROOT)
                val sub = listOfNotNull(
                    "%,d downloads".format(h.downloads),
                    "for the hard of hearing".takeIf { h.hearingImpaired },
                    "machine translated".takeIf { h.machineTranslated },
                ).joinToString(" · ")
                PanelRow("$lang · ${h.release.ifBlank { "Subtitles" }}", sub, selected = online.loadedId == h.fileId) { online.pick(h) }
            }
        }
    }
}

private fun LazyListScope.trackRows(tracks: PlayerTracks, aspect: Aspect, onAspect: (Aspect) -> Unit, first: FocusRequester) {
    header("Picture")
    Aspect.entries.forEachIndexed { i, a ->
        item(key = "p$i") { PanelRow(a.label, a.hint, selected = a == aspect, focus = if (i == 0) first else null) { onAspect(a) } }
    }
    if (tracks.audio.size > 1) {
        header("Audio")
        tracks.audio.forEachIndexed { i, c -> item(key = "a$i") { PanelRow(c.label, selected = c.selected) { tracks.selectAudio(c) } } }
    }
    if (tracks.video.size > 1) {
        header("Video quality")
        val playing = tracks.video.firstOrNull { it.selected }?.let { TrackLabels.quality(-1, it.height) }
        item(key = "auto") { PanelRow("Auto", playing?.let { "Now playing $it" } ?: "Adjusts to the connection", selected = tracks.videoAuto) { tracks.selectVideo(null) } }
        tracks.video.forEachIndexed { i, c -> item(key = "v$i") { PanelRow(c.label, selected = !tracks.videoAuto && c.selected) { tracks.selectVideo(c) } } }
    }
    if (!tracks.hasChoices) note("single", "This stream has one audio track and one video quality.")
}

@Composable
private fun InfoContent(stats: () -> StreamStats, wide: Boolean) {
    var sections by remember { mutableStateOf<List<InfoSection>>(emptyList()) }
    val clockLifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(clockLifecycle) {
        clockLifecycle.foregroundTicks(1_000) { sections = StreamInfo.sections(stats()) }
    }
    if (sections.isEmpty()) return Text("Waiting for the stream…", fontSize = 14.sp, color = Tv.Muted, modifier = Modifier.padding(start = 8.dp))
    val col: @Composable (List<InfoSection>, Modifier) -> Unit = { list, m ->
        Column(m) { list.forEach { InfoBlock(it) } }
    }
    val scroll = rememberScrollState()
    if (wide) {
        Row(Modifier.verticalScroll(scroll).padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            col(sections.take(1), Modifier.weight(1f))
            col(sections.drop(1), Modifier.weight(1f))
        }
    } else {
        col(sections, Modifier.verticalScroll(scroll).padding(bottom = 24.dp))
    }
}

@Composable
private fun InfoBlock(s: InfoSection) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(s.title.uppercase(Locale.getDefault()), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Tv.Accent, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
        s.rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(k, fontSize = 14.sp, color = Tv.Muted, modifier = Modifier.width(130.dp))
                Text(v, fontSize = 14.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Captions, audio/video and info buttons shown with the player controls. On TV, D-pad up from the
 * controls lands here and down goes back ([onLeave]); on touch they are 56dp round buttons.
 */
@Composable
fun PlayerButtons(
    tracks: PlayerTracks,
    first: FocusRequester,
    onCaptions: () -> Unit,
    onTracks: () -> Unit,
    onInfo: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { onLeave(); true } else false
        },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CaptionButton(tracks.captionsOn, Modifier.focusRequester(first), onCaptions)
        RoundIcon(Icons.Filled.Settings, "Picture and sound", onClick = onTracks)
        RoundIcon(Icons.Filled.Info, "Stream info", onClick = onInfo)
    }
}

/** "CC" in a round button; struck through and dimmed while captions are off. */
@Composable
fun CaptionButton(on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, color = Color(0x99000000), shape = CircleShape, modifier = modifier.size(56.dp), contentAlignment = Alignment.Center) { focused ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "CC", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                color = if (on || focused) Tv.Text else Tv.Muted,
                textDecoration = if (on) null else TextDecoration.LineThrough,
            )
            Box(Modifier.padding(top = 2.dp).width(18.dp).heightIn(min = 3.dp, max = 3.dp).background(if (on) (if (focused) Tv.Text else Tv.Accent) else Color.Transparent))
        }
    }
}
