package app.familytv.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
    /** The Home carousel's slide, drawn full-bleed behind the top bar while Home's carousel is on screen. */
    val backdrop: MutableState<Slide?>,
    /** True while the screensaver shows. */
    val saver: MutableState<Boolean>,
    /** The Home carousel's height in pixels as last laid out (0 before), so the backdrop's shade lines up with the first row. */
    val slot: MutableFloatState = mutableFloatStateOf(0f),
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

    // Back changes page at once, never through the wipe below.
    var instant by remember { mutableStateOf(false) }
    BackHandler(enabled = anyFocused && !barFocused && !barHidden.value) { Usage.log(Usage.Action.BACK); focusBar() }
    BackHandler(enabled = barFocused && settings) { instant = true; settings = false }
    BackHandler(enabled = barFocused && !settings && queries.getValue(tab).value.isNotEmpty()) { queries.getValue(tab).value = "" }
    // Back on another tab goes to Home; Back on Home asks before leaving (apps never see the Home button, so this is the one place to ask).
    var exitAsk by remember { mutableStateOf(false) }
    val onTabs = (barFocused || !anyFocused) && !settings && !barHidden.value && queries.getValue(tab).value.isEmpty()
    // Home's page keeps its place, so focus goes back to where it was there (else its first row).
    var backHome by remember { mutableIntStateOf(0) }
    BackHandler(enabled = onTabs && tab != GlassTab.Home) { instant = true; tab = GlassTab.Home; backHome++ }
    BackHandler(enabled = onTabs && tab == GlassTab.Home) { exitAsk = true }
    LaunchedEffect(Unit) { delay(50); focusBar() }
    LaunchedEffect(settings) { if (!settings && barFocused) { delay(50); focusBar() } }

    // The screensaver (backlog item 16): after Settings' minutes without a key (5 unless changed), unless a preview is playing.
    val saverState = remember { mutableStateOf(false) }
    var saver by saverState
    val shell = remember { GlassShell(queries, glow, barHidden, darkPages, mutableStateOf(null), saverState) }
    val shown by animateColorAsState(glow.value, tween(500), label = "glow")
    val lightness = rememberLightness(appearance, weather.forecast)
    // Kids get the bright look.
    val t = if (darkPages.intValue > 0) 0f else if (profile.kidsOnly) 1f else lightness
    val palette = remember(t) { GlassPalette.lerp(GlassPalette.Dark, GlassPalette.Light, t) }
    LaunchedEffect(backHome) {
        if (backHome == 0) return@LaunchedEffect
        // Once Home's rows are back on screen.
        repeat(40) {
            delay(50)
            if (entry.attached > 0) {
                if (runCatching { pageFocus.requestFocus() }.isFailure) entry.focus()
                return@LaunchedEffect
            }
        }
    }
    // Closing the panel hands focus back to where it was on the page.
    var panelSeen by remember { mutableStateOf(false) }
    LaunchedEffect(panel) {
        if (panel) { panelSeen = true; return@LaunchedEffect }
        if (!panelSeen) return@LaunchedEffect
        delay(50)
        if (runCatching { pageFocus.requestFocus() }.isFailure) entry.focus()
    }
    val pages = rememberSaveableStateHolder()
    // A new tab or Settings comes in behind Rive's glass wipe: the tab's pill moves at once and the
    // page swaps when the wipe covers it, in dark glass on the dark look and light glass on the light
    // one. Not on the first page, on Back or where Rive can't run: the page fades in over 200 ms
    // there (backlog item 15), rising 10 px as it does. Neither when the system asks for less motion.
    val still = rememberReducedMotion()
    val ctx = LocalContext.current
    val wipe = remember { if (still) null else RiveTransition.create(ctx) }
    var onPage by remember { mutableStateOf(tab to settings) }
    val pageKey = if (settings) "settings" else tab.name
    val fade = remember { Animatable(1f) }
    val rise = with(LocalDensity.current) { 10.dp.toPx() }
    var firstPage by remember { mutableStateOf(true) }
    LaunchedEffect(pageKey) {
        val w = wipe?.takeIf { !instant && !firstPage }
        instant = false
        // While the wipe covers, its swap picks up this page too; once it reveals, a new page swaps at once.
        if (w != null && w.phase != RiveTransition.Phase.REVEALING) {
            if (w.phase == RiveTransition.Phase.COVERING || w.go(if (t < 0.5f) RiveTransition.PAGE else RiveTransition.PAGE_LIGHT) { onPage = tab to settings }) return@LaunchedEffect
        }
        onPage = tab to settings
        if (firstPage || still) { firstPage = false; return@LaunchedEffect }
        fade.snapTo(0f)
        fade.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
    }
    // OK on a big glass card starts the shared press ripple over it (not with less motion).
    val pressHost = with(LocalDensity.current) { remember { if (still) null else PressHost(120.dp.toPx(), 60.dp.toPx()) } }
    // Down from the bar mid-wipe swaps the page now and moves into it, rather than into the page going away.
    val focusManager = LocalFocusManager.current
    var downIntoPage by remember { mutableIntStateOf(0) }
    LaunchedEffect(downIntoPage) {
        if (downIntoPage == 0) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        focusManager.moveFocus(FocusDirection.Down)
    }
    // The screensaver's idle time is counted in delays, not read in composition, so a key press never recomposes the home.
    val idle = remember { IdleCount() }
    // The screen stays on while the home is in front, so the TV's own screensaver (Fire OS starts one
    // at 5 minutes) doesn't beat ours; after SAVER_HOLD_MS of ours, or with ours Off, the TV's own
    // screensaver and sleep take over as usual.
    val view = LocalView.current
    DisposableEffect(view) { onDispose { view.keepScreenOn = false } }
    LaunchedEffect(Unit) {
        while (true) {
            val after = prefs.saverMinutes.value * 60_000L
            view.keepScreenOn = after > 0 && idle.ms < after + SAVER_HOLD_MS
            delay(IDLE_STEP_MS)
            idle.ms += IDLE_STEP_MS
            if (!saver && after > 0 && idle.ms >= after && !LivePreview.anyActive) saver = true
        }
    }
    val backdropCovers = remember { mutableStateOf(false) }
    // Where the page starts under the top bar: the carousel's shade under the first row is measured from it.
    val pageTop = remember { mutableFloatStateOf(0f) }
    CompositionLocalProvider(
        LocalGlass provides true,
        LocalReducedMotion provides still,
        LocalGlassScale provides rememberGlassScale(),
        LocalBigTiles provides profile.kidsOnly,
        LocalGlassShell provides shell,
        LocalPageEntry provides entry,
        LocalGlassPalette provides palette,
        LocalContentColor provides palette.text,
        LocalPress provides pressHost,
    ) {
        GlassBackground(shown, hidden = backdropCovers) {
          // Any key resets the idle count; while the screensaver shows, the key that wakes it (down and up) goes no further.
          Box(
            Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                idle.ms = 0
                when {
                    saver && e.type == KeyEventType.KeyDown -> { saver = false; idle.waking = true; true }
                    idle.waking -> { if (e.type == KeyEventType.KeyUp) idle.waking = false; true }
                    e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown && barFocused && wipe?.phase == RiveTransition.Phase.COVERING -> {
                        onPage = tab to settings
                        downIntoPage++
                        true
                    }
                    else -> false
                }
            },
          ) {
            val (shownTab, shownSettings) = onPage
            val backdropOn = shownTab == GlassTab.Home && !shownSettings && !barHidden.value && darkPages.intValue == 0
            HomeBackdrop(shell.backdrop, show = backdropOn, covers = backdropCovers, pageTop = pageTop, slot = shell.slot)
            // Only whether there is a slide, so the home doesn't recompose on every one.
            val hasSlide by remember { derivedStateOf { shell.backdrop.value != null } }
            // Inside the TV's overscan safe zone (48 dp across, 27 dp at the top and bottom).
            Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 27.dp, bottom = 18.dp).onFocusChanged { anyFocused = it.hasFocus }) {
                // Over the carousel's art the bar and the weather hint keep light text in either look, on the art's dark shades.
                // One provider either way, so the bar (and its focus) stays put when the tab changes.
                val barPalette = if (backdropOn && hasSlide) GlassPalette.Dark else palette
                if (!barHidden.value) {
                    CompositionLocalProvider(LocalGlassPalette provides barPalette, LocalContentColor provides barPalette.text) {
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
                    }
                    Spacer(Modifier.height(16.gx))
                }
                Box(Modifier.weight(1f).fillMaxWidth().onPlaced { pageTop.floatValue = it.positionInRoot().y }) {
                    Box(Modifier.fillMaxSize().focusRequester(pageFocus).restoresFocus(entry).graphicsLayer { alpha = fade.value; translationY = (1f - fade.value) * rise }) {
                        pages.SaveableStateProvider(if (shownSettings) "settings" else shownTab.name) {
                            if (shownSettings) {
                                TrackScreen(Usage.Screen.SETTINGS)
                                GlassGlow(Color(0xFF7A8BA8))
                                GlassDarkPage()
                                SettingsScreen(vm, config)
                            } else {
                                TrackScreen(shownTab.usage)
                                GlassGlow(shownTab.glow)
                                when (shownTab) {
                                    GlassTab.Home -> VodTab(vm, profile, Kind.MOVIE, GlassTab.Home)
                                    GlassTab.Live -> LiveTab(vm, profile)
                                    GlassTab.Movies -> VodTab(vm, profile, Kind.MOVIE)
                                    GlassTab.Series -> VodTab(vm, profile, Kind.SERIES)
                                    GlassTab.Sports -> SportsTab(profile)
                                }
                            }
                        }
                    }
                    // Hidden and unfocusable while idle; TV remotes have no touch for it to take.
                    if (wipe != null) AndroidView({ wipe.view }, Modifier.matchParentSize().clip(RoundedCornerShape(24.gx)))
                }
                // Its own strip under the page, so it never covers the bottom row; the strip stays while the panel is open.
                if (!settings && !barHidden.value && darkPages.intValue == 0) {
                    Box(Modifier.fillMaxWidth().height(30.gx), contentAlignment = Alignment.BottomCenter) {
                        CompositionLocalProvider(LocalGlassPalette provides barPalette, LocalContentColor provides barPalette.text) {
                            if (!panel) WeatherHint({ panel = true })
                        }
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
            pressHost?.let { PressRipple(it, Modifier.fillMaxSize()) }
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

/** How long our screensaver keeps the screen on before the TV may sleep. */
private const val SAVER_HOLD_MS = 2 * 3_600_000L

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
            // The chosen tab's white pill and the focus bubble glide to the next tab (backlog item 15): drawn behind the
            // row, so nothing re-lays out.
            val spots = remember { mutableStateMapOf<GlassTab, Pair<Float, Float>>() }
            var focusedTab by remember { mutableStateOf<GlassTab?>(null) }
            val glide = rememberTabGlide(spots[tab].takeIf { !settings }, focusedTab?.let { spots[it] }, chosenFocused = focusedTab == tab && !settings)
            // Each tab's Rive icon (its artboard is named after the label) plays its move on focus and a squash on OK; text only where Rive
            // can't run or the system asks for less motion.
            val ctx = LocalContext.current
            val icons = remember { RiveArt.ready(ctx) } && !LocalReducedMotion.current
            val light = LocalGlassPalette.current.text.luminance() < 0.5f
            val presses = remember { mutableStateMapOf<GlassTab, Int>() }
            Row(Modifier.drawBehind(glide), horizontalArrangement = Arrangement.spacedBy(8.gx), verticalAlignment = Alignment.CenterVertically) {
                GlassTab.entries.forEach { t ->
                    val chosen = t == tab && !settings
                    GlassPill(
                        t.label, { presses[t] = (presses[t] ?: 0) + 1; onTab(t) },
                        Modifier.focusRequester(tabFocus.getValue(t)).onPlaced { spots[t] = it.positionInParent().x to it.size.width.toFloat() }
                            .onFocusChanged { if (it.isFocused) focusedTab = t else if (focusedTab == t) focusedTab = null },
                        selected = chosen, slid = true,
                        icon = if (!icons) null else { focused ->
                            // The art has room around the icon for its glow, so it overhangs the icon's box.
                            Box(Modifier.size(22.gx), contentAlignment = Alignment.Center) {
                                RiveTabIcon(t.label, focused, chosen, light, presses[t] ?: 0, Modifier.requiredSize(36.gx))
                            }
                        },
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
                GlassPill(viewer, onViewers, fill = Glass.Raised, border = true, icon = { _ -> Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(18.gx)) })
            }
        }
    }
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
