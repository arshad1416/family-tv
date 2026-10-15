package app.familytv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.familytv.data.ChannelEntity
import app.familytv.data.Kind
import app.familytv.user.Favourites
import kotlinx.coroutines.delay

/** How much of the screen the live player's guide takes: none, over the video, or with the video in a small window. */
enum class GuideMode { Off, Over, Picture }

/**
 * The full guide over live TV. Over: the grid sits on a see-through backdrop with the video
 * still full screen behind it. Picture: the backdrop turns solid and the video moves into the
 * window at the top left, whose position goes to [onWindow] (in root pixels) so the activity
 * can shrink the player view to it. OK on a programme calls [onPick] with its channel's index.
 */
@Composable
fun PlayerGuide(
    mode: GuideMode,
    channels: List<ChannelEntity>,
    playing: Int,
    favKeys: Set<String>,
    onPick: (Int) -> Unit,
    onWindow: (Rect) -> Unit,
) {
    val ctx = LocalContext.current
    val now = produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val start by remember { derivedStateOf { now.value / SLOT * SLOT } }
    val progs = rememberProgrammes(channels, from = start, hours = HOURS)
    val focus = remember { mutableStateOf<GuideFocus?>(null) }
    val scroll = rememberScrollState()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (playing - 2).coerceAtLeast(0))
    val entry = remember { PageEntry() }
    var window by remember { mutableStateOf<Rect?>(null) }
    // Focus starts on what's playing; its row is composed on the first layout pass, so try for a few frames.
    LaunchedEffect(Unit) {
        repeat(5) { withFrameNanos { }; if (entry.attached > 0 && runCatching { entry.requester.requestFocus() }.isSuccess) return@LaunchedEffect }
    }
    // Turning solid as the video shrinks reads as one move; the activity animates the video over the same time.
    val backdrop by animateColorAsState(if (mode == GuideMode.Picture) Color(0xFF0B0F18) else Color(0xD9060A14), tween(PlayerActivity.VIDEO_MOVE_MS.toInt()), label = "backdrop")
    Box(
        Modifier.fillMaxSize().drawBehind {
            // The video shows through a hole, since anything drawn over the player's surface hides it.
            val hole = window.takeIf { mode == GuideMode.Picture }
            if (hole == null) drawRect(backdrop)
            else clipRect(hole.left, hole.top, hole.right, hole.bottom, ClipOp.Difference) { drawRect(backdrop) }
        },
    ) {
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 28.dp)) {
            GuideHeader(focus, now) {
                Box(
                    Modifier.fillMaxHeight().aspectRatio(16f / 9f).onGloballyPositioned { c ->
                        c.boundsInRoot().let { r -> if (r != window) { window = r; onWindow(r) } }
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
            TimeBar(TV_DIMS, start, scroll, now)
            Spacer(Modifier.height(6.dp))
            CompositionLocalProvider(LocalPageEntry provides entry) {
                LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                    itemsIndexed(channels, key = { _, c -> "${c.sourceId}/${c.streamId}" }) { i, c ->
                        GuideRow(
                            TV_DIMS, c, c.epgId?.let { progs[it] }.orEmpty(), start, scroll, now, entry = i == playing, compact = false,
                            favourite = Favourites.key(Kind.LIVE, c.sourceId, c.streamId) in favKeys,
                            onFocus = { focus.value = GuideFocus(c, it) },
                            onPlay = { onPick(i) },
                            onFavourite = { toggleFavourite(ctx, Kind.LIVE, c.sourceId, c.streamId, c.name) },
                        )
                    }
                }
            }
            Text(
                if (mode == GuideMode.Picture) "OK: watch here, OK again: full screen   ·   Back: leave live TV" else "OK: watch in a small picture   ·   Back: smaller picture",
                fontSize = 13.sp, color = Tv.Muted, modifier = Modifier.padding(vertical = 10.dp),
            )
        }
    }
}
