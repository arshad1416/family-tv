package app.familytv.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.data.Kind
import app.familytv.portal.DeviceConfig
import app.familytv.portal.Profile
import app.familytv.user.Viewer
import app.familytv.usage.TrackScreen
import app.familytv.usage.Usage
import app.familytv.user.Appearance
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The TV's tabs, left to right. Each has its own glow and its own search. */
enum class GlassTab(val label: String, val glow: Color, val usage: Usage.Screen, val searchHint: String) {
    /** The main screen the app opens on: the film highlights, rows and providers. */
    Home("Home", Color(0xFF4F7CFF), Usage.Screen.HOME, "Search movies"),
    Live("Live TV", Color(0xFF2FB8A6), Usage.Screen.LIVE, "Search channels"),
    Movies("Movies", Color(0xFF4F7CFF), Usage.Screen.MOVIES, "Search movies"),
    Series("TV Shows", Color(0xFF9B6BFF), Usage.Screen.SERIES, "Search TV shows"),
    Sports("Sports", Color(0xFF3CCF6E), Usage.Screen.SPORTS, "Search sports channels"),
}

/** What a tab's page needs from the TV home around it: its search, the glow, and hiding the top bar. */
internal class GlassShell(
    val queries: Map<GlassTab, MutableState<String>>,
    val glow: MutableState<Color>,
    val barHidden: MutableState<Boolean>,
    /** How many pages that keep the dark look (Settings, the guide, a film or show) are open. */
    val darkPages: MutableIntState,
)

internal val LocalGlassShell = staticCompositionLocalOf<GlassShell?> { null }

/** [tab]'s search text, typed in the top bar. */
@Composable
internal fun glassQuery(tab: GlassTab): String = LocalGlassShell.current?.queries?.get(tab)?.value.orEmpty()

/** Hides the top bar while this is composed, for a page with its own header (a provider, the guide). */
@Composable
internal fun GlassBarHidden() {
    val shell = LocalGlassShell.current ?: return
    DisposableEffect(shell) {
        shell.barHidden.value = true
        onDispose { shell.barHidden.value = false }
    }
}

/**
 * Keeps the dark look while this is composed: Settings, the TV guide and the film and show pages
 * are still drawn for it. Does nothing off the TV's glass home.
 */
@Composable
internal fun GlassDarkPage() {
    val shell = LocalGlassShell.current ?: return
    DisposableEffect(shell) {
        shell.darkPages.intValue++
        onDispose { shell.darkPages.intValue-- }
    }
}

/**
 * The TV home: a top bar of tabs (Home, Live TV, Movies, TV Shows, Sports) with the tab's own search,
 * the clock, Appearance and Settings, over the tab's page, and the weather panel below it. Back inside a page goes up to the tabs;
 * Back on another tab's name goes to Home, and Back on Home asks before leaving. Each tab keeps its place (and its search) while another is open.
 */
@Composable
internal fun GlassHome(vm: MainViewModel, config: DeviceConfig) {
    val app = LocalContext.current.app
    val base = config.profile ?: Profile()
    // Who's watching (backlog items 17 and 18): a kids viewer sees the profile through kids' categories only.
    val viewerTick by app.viewers.changes.collectAsState()
    val viewer = remember(viewerTick) { app.viewers.current() }
    val hasViewers = remember(viewerTick) { app.viewers.all().isNotEmpty() }
    val profile = remember(base, viewerTick) { app.viewers.apply(base) }
    var picking by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(GlassTab.Home) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val queries = GlassTab.entries.associateWith { rememberSaveable(key = "query/${it.name}") { mutableStateOf("") } }
    val tabFocus = remember { GlassTab.entries.associateWith { FocusRequester() } }
    val settingsFocus = remember { FocusRequester() }
    val entry = remember { PageEntry() }
    val glow = remember { mutableStateOf(tab.glow) }
    val barHidden = remember { mutableStateOf(false) }
    val darkPages = remember { mutableIntStateOf(0) }
    val prefs = LocalContext.current.app.glassPrefs
    val appearance by prefs.appearance.collectAsState()
    val weather = rememberGlassWeather()
    var panel by remember { mutableStateOf(false) }
    val pageFocus = remember { FocusRequester() }
    var barFocused by remember { mutableStateOf(false) }
    var anyFocused by remember { mutableStateOf(false) }
    val focusBar = { runCatching { (if (settings) settingsFocus else tabFocus.getValue(tab)).requestFocus() } }

    BackHandler(enabled = anyFocused && !barFocused && !barHidden.value) { Usage.log(Usage.Action.BACK); focusBar() }
    BackHandler(enabled = barFocused && settings) { settings = false }
    BackHandler(enabled = barFocused && !settings && queries.getValue(tab).value.isNotEmpty()) { queries.getValue(tab).value = "" }
    // Back on another tab goes to Home; Back on Home asks before leaving (apps never see the Home button, so this is the one place to ask).
    var exitAsk by remember { mutableStateOf(false) }
    val onTabs = (barFocused || !anyFocused) && !settings && !barHidden.value && queries.getValue(tab).value.isEmpty()
    BackHandler(enabled = onTabs && tab != GlassTab.Home) {
        tab = GlassTab.Home
        runCatching { tabFocus.getValue(GlassTab.Home).requestFocus() }
    }
    BackHandler(enabled = onTabs && tab == GlassTab.Home) { exitAsk = true }
    LaunchedEffect(Unit) { delay(50); focusBar() }
    LaunchedEffect(settings) { if (!settings && barFocused) { delay(50); focusBar() } }

    val shell = remember { GlassShell(queries, glow, barHidden, darkPages) }
    val shown by animateColorAsState(glow.value, tween(500), label = "glow")
    val lightness = rememberLightness(appearance, weather.forecast)
    // Kids get the bright look.
    val t = if (darkPages.intValue > 0) 0f else if (profile.kidsOnly) 1f else lightness
    val palette = remember(t) { GlassPalette.lerp(GlassPalette.Dark, GlassPalette.Light, t) }
    // Closing the panel hands focus back to where it was on the page.
    var panelSeen by remember { mutableStateOf(false) }
    LaunchedEffect(panel) {
        if (panel) { panelSeen = true; return@LaunchedEffect }
        if (!panelSeen) return@LaunchedEffect
        delay(50)
        if (runCatching { pageFocus.requestFocus() }.isFailure) entry.focus()
    }
    val pages = rememberSaveableStateHolder()
    // A new tab or Settings fades in over 200 ms (backlog item 15), opacity only; not when the system asks for less motion.
    val still = rememberReducedMotion()
    val pageKey = if (settings) "settings" else tab.name
    val fade = remember { Animatable(1f) }
    var firstPage by remember { mutableStateOf(true) }
    LaunchedEffect(pageKey) {
        if (firstPage || still) { firstPage = false; return@LaunchedEffect }
        fade.snapTo(0f)
        fade.animateTo(1f, tween(200))
    }
    // The screensaver (backlog item 16): after 5 minutes without a key, unless a preview is playing.
    // Counted in delays, not read in composition, so a key press never recomposes the home.
    val idle = remember { IdleCount() }
    var saver by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(IDLE_STEP_MS)
            idle.ms += IDLE_STEP_MS
            if (!saver && idle.ms >= SCREENSAVER_IDLE_MS && !LivePreview.anyActive) saver = true
        }
    }
    CompositionLocalProvider(
        LocalGlass provides true,
        LocalGlassScale provides rememberGlassScale(),
        LocalBigTiles provides profile.kidsOnly,
        LocalGlassShell provides shell,
        LocalPageEntry provides entry,
        LocalGlassPalette provides palette,
        LocalContentColor provides palette.text,
    ) {
        GlassBackground(shown) {
          // Any key resets the idle count; while the screensaver shows, the key that wakes it (down and up) goes no further.
          Box(
            Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                idle.ms = 0
                when {
                    saver && e.type == KeyEventType.KeyDown -> { saver = false; idle.waking = true; true }
                    idle.waking -> { if (e.type == KeyEventType.KeyUp) idle.waking = false; true }
                    else -> false
                }
            },
          ) {
            // Inside the TV's overscan safe zone (48 dp across, 27 dp at the top and bottom).
            Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 27.dp, bottom = 18.dp).onFocusChanged { anyFocused = it.hasFocus }) {
                if (!barHidden.value) {
                    GlassTopBar(
                        tab, settings, tabFocus, settingsFocus,
                        query = queries.getValue(tab).takeIf { !settings },
                        onTab = { tab = it; settings = false },
                        onSettings = { settings = true },
                        onFocused = { barFocused = it },
                        appearance = appearance,
                        onAppearance = prefs::setAppearance,
                        viewer = viewer?.name ?: "Who's watching?",
                        onViewers = { picking = true }.takeIf { hasViewers },
                    )
                    Spacer(Modifier.height(16.gx))
                }
                Box(Modifier.weight(1f).fillMaxWidth().focusRequester(pageFocus).restoresFocus(entry).graphicsLayer { alpha = fade.value }) {
                    pages.SaveableStateProvider(if (settings) "settings" else tab.name) {
                        if (settings) {
                            TrackScreen(Usage.Screen.SETTINGS)
                            GlassGlow(Color(0xFF7A8BA8))
                            GlassDarkPage()
                            SettingsScreen(vm, config)
                        } else {
                            TrackScreen(tab.usage)
                            GlassGlow(tab.glow)
                            when (tab) {
                                GlassTab.Home -> VodTab(vm, profile, Kind.MOVIE, GlassTab.Home)
                                GlassTab.Live -> LiveTab(vm, profile)
                                GlassTab.Movies -> VodTab(vm, profile, Kind.MOVIE)
                                GlassTab.Series -> VodTab(vm, profile, Kind.SERIES)
                                GlassTab.Sports -> SportsTab(profile)
                            }
                        }
                    }
                }
                // Its own strip under the page, so it never covers the bottom row; the strip stays while the panel is open.
                if (!settings && !barHidden.value && darkPages.intValue == 0) {
                    Box(Modifier.fillMaxWidth().height(30.gx), contentAlignment = Alignment.BottomCenter) {
                        if (!panel) WeatherHint({ panel = true })
                    }
                }
            }
            AnimatedVisibility(
                panel, Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(300)) { it } + fadeIn(tween(300)),
                exit = slideOutVertically(tween(250)) { it } + fadeOut(tween(250)),
            ) {
                WeatherPanel(weather, onClose = { panel = false })
            }
            if (exitAsk) {
                val activity = LocalContext.current.findActivity()
                ExitPrompt(onStay = { exitAsk = false; focusBar() }, onLeave = { activity?.finish() })
            }
            if (picking) ViewerPicker(base, viewer, onDone = { picking = false; focusBar() })
            if (saver) Screensaver(profile, weather)
          }
        }
    }
}

/** The TV home's idle time (ms since the last key) and whether a key is waking the screensaver. */
private class IdleCount {
    var ms = 0L
    var waking = false
}

private const val IDLE_STEP_MS = 15_000L

/**
 * The title with the clock, YouTube and JustWatch (Movies and TV Shows), the Appearance switch and
 * Settings on its right; below it the tabs and this tab's search. Every tab uses this one layout,
 * so the tabs never move and nothing on the right is squeezed. OK opens a tab; focus alone doesn't.
 */
@Composable
private fun GlassTopBar(
    tab: GlassTab,
    settings: Boolean,
    tabFocus: Map<GlassTab, FocusRequester>,
    settingsFocus: FocusRequester,
    query: MutableState<String>?,
    onTab: (GlassTab) -> Unit,
    onSettings: () -> Unit,
    onFocused: (Boolean) -> Unit,
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    viewer: String,
    onViewers: (() -> Unit)?,
) {
    val apps = !settings && (tab == GlassTab.Home || tab == GlassTab.Movies || tab == GlassTab.Series)
    val home = if (settings) settingsFocus else tabFocus.getValue(tab)
    // Up from the page always lands on the chosen tab (or Settings), never on whatever sits nearest above.
    Column(
        Modifier.fillMaxWidth().onFocusChanged { onFocused(it.hasFocus) }
            .focusProperties { onEnter = { if (requestedFocusDirection == FocusDirection.Up) runCatching { home.requestFocus() } } }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(12.gx),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (settings) "Settings" else tab.label, fontSize = 40.gsp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.gx), verticalAlignment = Alignment.CenterVertically) {
                if (apps) AppButtons()
                Box(Modifier.padding(horizontal = 6.gx)) { GlassClock() }
                AppearanceSwitch(appearance, onAppearance)
                // Nothing is right of Settings, so Right steps down to the tabs instead of sticking.
                SettingsButton(settings, settingsFocus, onSettings, Modifier.focusProperties { right = home })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The chosen tab's white pill slides to the next one (backlog item 15): drawn behind the row, so nothing re-lays out.
            val spots = remember { mutableStateMapOf<GlassTab, Pair<Float, Float>>() }
            val slider = rememberTabSlider(spots[tab].takeIf { !settings })
            Row(Modifier.drawBehind(slider), horizontalArrangement = Arrangement.spacedBy(8.gx), verticalAlignment = Alignment.CenterVertically) {
                GlassTab.entries.forEach { t ->
                    GlassPill(
                        t.label, { onTab(t) },
                        Modifier.focusRequester(tabFocus.getValue(t)).onPlaced { spots[t] = it.positionInParent().x to it.size.width.toFloat() },
                        selected = t == tab && !settings, slid = true,
                    )
                }
            }
            if (query != null) {
                Spacer(Modifier.width(18.gx))
                GlassSearchBox(query.value, { query.value = it }, tab.searchHint)
            }
            // Who's watching, when this TV has viewers; OK picks another.
            if (onViewers != null) {
                Spacer(Modifier.weight(1f))
                GlassPill(viewer, onViewers, fill = Glass.Raised, border = true, icon = { Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(18.gx)) })
            }
        }
    }
}

/**
 * Draws the tabs' sliding pill at [spot] (x and width in the row, px), moving there over 200 ms,
 * or at once when the system asks for less motion; none while Settings is open.
 */
@Composable
private fun rememberTabSlider(spot: Pair<Float, Float>?): DrawScope.() -> Unit {
    val color = Glass.Selected
    val still = rememberReducedMotion()
    val x = remember { Animatable(spot?.first ?: 0f) }
    val w = remember { Animatable(spot?.second ?: 0f) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(spot) {
        val (tx, tw) = spot ?: return@LaunchedEffect
        if (still || !placed) { x.snapTo(tx); w.snapTo(tw); placed = true; return@LaunchedEffect }
        launch { x.animateTo(tx, tween(200)) }
        w.animateTo(tw, tween(200))
    }
    return { if (spot != null && placed) drawRoundRect(color, Offset(x.value, 0f), Size(w.value, size.height), CornerRadius(size.height / 2)) }
}

/** "Exit Family TV?" over the dimmed home: Stay (focused) or Exit. Back again stays; focus can't wander off it. */
@Composable
private fun ExitPrompt(onStay: () -> Unit, onLeave: () -> Unit) {
    val stay = remember { FocusRequester() }
    BackHandler(onBack = onStay)
    LaunchedEffect(Unit) { delay(50); runCatching { stay.requestFocus() } }
    val shape = RoundedCornerShape(28.gx)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.gx).background(LocalGlassPalette.current.middle, shape).border(1.dp, Glass.Line, shape)
                .focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
                .padding(horizontal = 36.gx, vertical = 32.gx),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(26.gx),
        ) {
            Text("Exit Family TV?", fontSize = 30.gsp, fontWeight = FontWeight.Bold, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(16.gx)) {
                GlassPill("Stay", onStay, Modifier.focusRequester(stay), fill = Glass.Raised, border = true, fontSize = 18.gsp, padH = 36.gx, padV = 12.gx)
                GlassPill("Exit", onLeave, fill = Glass.Raised, border = true, fontSize = 18.gsp, padH = 36.gx, padV = 12.gx)
            }
        }
    }
}

/**
 * "Who's watching?" over the dimmed home: one pill per viewer. Moving away from a kids viewer asks
 * for the parental PIN first (backlog item 18); the PIN shows as dots and is never logged.
 */
@Composable
private fun ViewerPicker(profile: Profile, current: Viewer?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    var asking by remember { mutableStateOf<Viewer?>(null) }
    BackHandler { if (asking != null) asking = null else onDone() }
    LaunchedEffect(asking) { delay(50); runCatching { first.requestFocus() } }
    val shape = RoundedCornerShape(28.gx)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(min = 520.gx).background(LocalGlassPalette.current.middle, shape).border(1.dp, Glass.Line, shape)
                .focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
                .padding(horizontal = 36.gx, vertical = 32.gx),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(26.gx),
        ) {
            val leaving = asking
            if (leaving == null) {
                Text("Who's watching?", fontSize = 30.gsp, fontWeight = FontWeight.Bold, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(16.gx)) {
                    ctx.app.viewers.all().forEachIndexed { i, v ->
                        GlassPill(
                            if (v.kids) "${v.name} · Kids" else v.name,
                            {
                                when {
                                    v.id == current?.id -> onDone()
                                    current?.kids == true -> asking = v
                                    else -> { ctx.app.viewers.choose(v); onDone() }
                                }
                            },
                            if (i == 0) Modifier.focusRequester(first) else Modifier,
                            selected = v.id == current?.id, fill = Glass.Raised, border = true, fontSize = 18.gsp, padH = 28.gx, padV = 12.gx,
                        )
                    }
                }
            } else {
                Text("Switch to ${leaving.name}?", fontSize = 26.gsp, fontWeight = FontWeight.Bold, maxLines = 1)
                PinPrompt(profile, Modifier.focusRequester(first)) { ctx.app.viewers.choose(leaving); onDone() }
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
