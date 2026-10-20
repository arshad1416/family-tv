package app.familytv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.data.Kind
import app.familytv.portal.DeviceConfig
import app.familytv.portal.Profile
import app.familytv.usage.TrackScreen
import app.familytv.usage.Usage
import app.familytv.user.Appearance
import kotlinx.coroutines.delay

/** The TV's tabs, left to right. Each has its own glow and its own search. */
enum class GlassTab(val label: String, val glow: Color, val usage: Usage.Screen, val searchHint: String) {
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
 * The TV home: a top bar of tabs (Live TV, Movies, TV Shows, Sports) with the tab's own search,
 * the clock, Appearance and Settings, over the tab's page, and the weather panel below it. Back inside a page goes up to the tabs; Back on the
 * tabs leaves the app as before. Each tab keeps its place (and its search) while another is open.
 */
@Composable
internal fun GlassHome(vm: MainViewModel, config: DeviceConfig) {
    val profile = config.profile ?: Profile()
    var tab by rememberSaveable { mutableStateOf(GlassTab.Movies) }
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
    LaunchedEffect(Unit) { delay(50); focusBar() }
    LaunchedEffect(settings) { if (!settings && barFocused) { delay(50); focusBar() } }

    val shell = remember { GlassShell(queries, glow, barHidden, darkPages) }
    val shown by animateColorAsState(glow.value, tween(500), label = "glow")
    val lightness = rememberLightness(appearance, weather.forecast)
    val t = if (darkPages.intValue > 0) 0f else lightness
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
    CompositionLocalProvider(
        LocalGlass provides true,
        LocalGlassScale provides rememberGlassScale(),
        LocalGlassShell provides shell,
        LocalPageEntry provides entry,
        LocalGlassPalette provides palette,
        LocalContentColor provides palette.text,
    ) {
        GlassBackground(shown) {
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
                    )
                    Spacer(Modifier.height(16.gx))
                }
                Box(Modifier.weight(1f).fillMaxWidth().focusRequester(pageFocus).restoresFocus(entry)) {
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
        }
    }
}

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
) {
    val apps = !settings && (tab == GlassTab.Movies || tab == GlassTab.Series)
    Column(Modifier.fillMaxWidth().onFocusChanged { onFocused(it.hasFocus) }, verticalArrangement = Arrangement.spacedBy(12.gx)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (settings) "Settings" else tab.label, fontSize = 40.gsp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.gx), verticalAlignment = Alignment.CenterVertically) {
                if (apps) AppButtons()
                Box(Modifier.padding(horizontal = 6.gx)) { GlassClock() }
                AppearanceSwitch(appearance, onAppearance)
                SettingsButton(settings, settingsFocus, onSettings)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.gx), verticalAlignment = Alignment.CenterVertically) {
                GlassTab.entries.forEach { t ->
                    GlassPill(t.label, { onTab(t) }, Modifier.focusRequester(tabFocus.getValue(t)), selected = t == tab && !settings)
                }
            }
            if (query != null) {
                Spacer(Modifier.width(18.gx))
                GlassSearchBox(query.value, { query.value = it }, tab.searchHint)
            }
        }
    }
}
