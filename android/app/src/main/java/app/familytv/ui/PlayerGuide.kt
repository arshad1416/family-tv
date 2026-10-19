package app.familytv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.data.CategoryEntity
import app.familytv.data.ChannelEntity
import app.familytv.data.Kind
import app.familytv.data.displayName
import app.familytv.portal.Profile
import app.familytv.user.Favourites
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf

/** How much of the screen the live player's guide takes: none, over the video, or with the video in a small window. */
enum class GuideMode { Off, Over, Picture }

/** A channel list: a category's (sourceId, categoryId), or Favourites' ("", its id). */
typealias ListKey = Pair<String, String>

val CategoryEntity.listKey: ListKey get() = sourceId to categoryId

/**
 * The guide's category list beside the grid. [shown] is the category whose channels the grid
 * shows; picking another calls [onPick], and focus leaving the list calls [onLeave].
 */
class GuideCategories(
    val all: List<CategoryEntity>,
    val shown: CategoryEntity?,
    val open: Boolean,
    val onPick: (CategoryEntity) -> Unit,
    val onLeave: () -> Unit,
)

/**
 * [PlayerGuide] with its categories: it opens on the list that's playing ([playingList], whose
 * channels the player already has) and can show any other visible live category or Favourites.
 * Adult categories show as on Live TV: once unlocked, or when the profile has no PIN. OK on a
 * channel calls [onPick] with that channel's list and its index in it.
 */
@Composable
fun LiveGuide(
    mode: GuideMode,
    profile: Profile?,
    playingList: ListKey,
    channels: List<ChannelEntity>,
    playing: Int,
    categoriesOpen: Boolean,
    onCategoriesOpen: (Boolean) -> Unit,
    onPick: (ListKey, List<ChannelEntity>, Int) -> Unit,
    onWindow: (Rect) -> Unit,
) {
    val ctx = LocalContext.current
    val all by remember { ctx.app.db.catalog().categories(Kind.LIVE) }.collectAsState(initial = emptyList())
    val mine by ctx.app.myCategoryOrder.order.collectAsState()
    val unlocked by ctx.app.adultUnlocked.collectAsState()
    val visible = remember(all, profile, mine, unlocked) { visibleCategories(all, profile, mine[Kind.LIVE].orEmpty(), adultOpen(profile, unlocked)) }
    val cats = remember(visible) { listOf(favouritesCategory(Kind.LIVE)) + visible }
    val favs = rememberFavourites()
    val favKeys = rememberFavouriteKeys(favs)
    val favChannels = rememberFavouriteChannels(favs, visible)
    var shownKey by remember { mutableStateOf(playingList) }
    val shown = cats.firstOrNull { it.listKey == shownKey }
    val isPlaying = shownKey == playingList
    val inCategory by remember(shownKey) {
        if (isPlaying || shown?.isFavourites == true) flowOf(emptyList()) else ctx.app.db.catalog().channels(shownKey.first, shownKey.second)
    }.collectAsState(initial = emptyList())
    val list = when {
        isPlaying -> channels
        shown?.isFavourites == true -> favChannels
        else -> inCategory
    }
    PlayerGuide(
        mode, list, if (isPlaying) playing else -1, favKeys,
        onPick = { onPick(shownKey, list, it) },
        onWindow = onWindow,
        categories = GuideCategories(cats, shown, categoriesOpen, onPick = { shownKey = it.listKey; onCategoriesOpen(false) }, onLeave = { onCategoriesOpen(false) }),
    )
}

/**
 * The full guide over live TV. Over: the grid sits on a see-through backdrop with the video
 * still full screen behind it. Picture: the backdrop turns solid and the video moves into the
 * window at the top left, whose position goes to [onWindow] (in root pixels) so the activity
 * can shrink the player view to it. OK on a programme calls [onPick] with its channel's index.
 * [playing] is -1 when these channels aren't the playing list; focus then starts at the top.
 */
@Composable
fun PlayerGuide(
    mode: GuideMode,
    channels: List<ChannelEntity>,
    playing: Int,
    favKeys: Set<String>,
    onPick: (Int) -> Unit,
    onWindow: (Rect) -> Unit,
    categories: GuideCategories? = null,
) {
    val ctx = LocalContext.current
    val now = produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val start by remember { derivedStateOf { now.value / SLOT * SLOT } }
    val progs = rememberProgrammes(channels, from = start, hours = HOURS)
    val focus = remember { mutableStateOf<GuideFocus?>(null) }
    val scroll = rememberScrollState()
    val entryRow = if (playing in channels.indices) playing else 0
    val listKey = categories?.shown?.listKey
    val entry = remember { PageEntry() }
    var window by remember { mutableStateOf<Rect?>(null) }
    val open = categories?.open == true
    // Focus starts on what's playing (or the top of another category) once the categories close;
    // its row is composed on the first layout pass after the channels load, so try for a few frames.
    LaunchedEffect(listKey, open, channels.isNotEmpty()) {
        if (open || channels.isEmpty()) return@LaunchedEffect
        repeat(5) { withFrameNanos { }; if (entry.attached > 0 && runCatching { entry.requester.requestFocus() }.isSuccess) return@LaunchedEffect }
    }
    // Turning solid as the video shrinks reads as one move; the activity animates the video over the same time.
    val backdrop by animateColorAsState(if (mode == GuideMode.Picture) Color(0xFF0B0F18) else Color(0xD9060A14), tween(PlayerController.VIDEO_MOVE_MS.toInt()), label = "backdrop")
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
            Row(Modifier.weight(1f)) {
                if (categories != null) {
                    // The list slides in from the left and pushes the grid over, rather than covering it.
                    AnimatedVisibility(
                        categories.open,
                        enter = expandHorizontally(tween(PlayerController.VIDEO_MOVE_MS.toInt())) + fadeIn(tween(PlayerController.VIDEO_MOVE_MS.toInt())),
                        exit = shrinkHorizontally(tween(PlayerController.VIDEO_MOVE_MS.toInt())) + fadeOut(tween(PlayerController.VIDEO_MOVE_MS.toInt())),
                    ) { CategoryList(categories) }
                }
                Column(Modifier.weight(1f)) {
                    TimeBar(TV_DIMS, start, scroll, now)
                    Spacer(Modifier.height(6.dp))
                    if (channels.isEmpty() && categories?.shown != null) {
                        Text(
                            if (categories.shown.isFavourites) "No favourite channels yet. Hold OK on a channel to add it." else "No channels in this category.",
                            fontSize = 16.sp, color = Tv.Muted, modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                    // A new category starts its own list, scrolled to the top.
                    key(listKey) {
                        CompositionLocalProvider(LocalPageEntry provides entry) {
                            val list = rememberLazyListState(initialFirstVisibleItemIndex = (entryRow - 2).coerceAtLeast(0))
                            LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                                itemsIndexed(channels, key = { _, c -> "${c.sourceId}/${c.streamId}" }) { i, c ->
                                    GuideRow(
                                        TV_DIMS, c, c.epgId?.let { progs[it] }.orEmpty(), start, scroll, now, entry = i == entryRow, compact = false,
                                        favourite = Favourites.key(Kind.LIVE, c.sourceId, c.streamId) in favKeys,
                                        onFocus = { focus.value = GuideFocus(c, it) },
                                        onPlay = { onPick(i) },
                                        onFavourite = { toggleFavourite(ctx, Kind.LIVE, c.sourceId, c.streamId, c.name) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Text(
                when {
                    open -> "OK: show this category   ·   Right: back to the guide   ·   Back: leave live TV"
                    mode == GuideMode.Picture && categories != null -> "OK: watch here, OK again: full screen   ·   Back: categories"
                    mode == GuideMode.Picture -> "OK: watch here, OK again: full screen   ·   Back: leave live TV"
                    else -> "OK: watch in a small picture   ·   Back: categories"
                },
                fontSize = 13.sp, color = Tv.Muted, modifier = Modifier.padding(vertical = 10.dp),
            )
        }
    }
}

/** The categories down the left of the guide; focus starts on the one shown. */
@Composable
private fun CategoryList(c: GuideCategories) {
    val start = c.all.indexOf(c.shown).coerceAtLeast(0)
    val first = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    // Keyed on open, since Back pressed again mid-slide-out reopens the same list.
    LaunchedEffect(c.open) {
        if (c.open) repeat(5) { withFrameNanos { }; if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect }
    }
    LazyColumn(
        Modifier.width(240.dp).padding(end = 16.dp).onFocusChanged {
            if (it.hasFocus) hadFocus = true else if (hadFocus) { hadFocus = false; c.onLeave() }
        },
        state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        itemsIndexed(c.all, key = { _, cat -> "${cat.sourceId}/${cat.categoryId}" }) { i, cat ->
            FocusBox(
                onClick = { c.onPick(cat) }, selected = cat == c.shown,
                modifier = Modifier.fillMaxWidth().then(if (i == start) Modifier.focusRequester(first) else Modifier),
            ) { focused ->
                Text(
                    cat.displayName, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (cat == c.shown && !focused) Tv.Accent else Tv.Text,
                )
            }
        }
    }
}
