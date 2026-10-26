package app.familytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.familytv.BuildConfig
import app.familytv.addons.Addons
import app.familytv.app
import app.familytv.data.Kind
import app.familytv.iptv.QualityPref
import app.familytv.portal.DeviceConfig
import app.familytv.portal.Profile
import app.familytv.start.OpenOnStart
import app.familytv.sync.PlaybackStatus
import app.familytv.usage.Usage
import app.familytv.user.GlassPrefs
import app.familytv.user.SaverSource
import app.familytv.user.Viewers
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(vm: MainViewModel, config: DeviceConfig) {
    val profile = config.profile
    val memory = remember { FocusMemory() }
    // A menu (stream quality, removing a viewer) drops focus as it closes; put it back on the option
    // it was opened from rather than letting the next press start again at the top of Settings.
    val menuOpen = LocalItemMenus.current.open != null
    LaunchedEffect(menuOpen) { if (!menuOpen) { delay(50); memory.restore() } }
    CompositionLocalProvider(LocalFocusMemory provides memory) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            PageTitle("Settings", bottom = 18.dp)
            // Short sections; on a TV in two columns of about the same length, on phones and tablets in one.
            val left: @Composable () -> Unit = {
                Section("Viewing") {
                    StreamQualityPanel()
                    // Only shown when the portal has Trakt set up.
                    if (config.trakt?.clientId != null) TraktPanel()
                }
                Section("Channels and categories") {
                    ChannelListsPanel(vm)
                    PackageSorterPanel(profile)
                    ArrangeCategoriesPanel(profile ?: Profile())
                }
            }
            val right: @Composable () -> Unit = {
                Section("Viewers and PIN") {
                    ViewersPanel(profile)
                    ParentalPinPanel(profile)
                }
                Section("This TV") {
                    CheckPanel(vm)
                    AboutPanel(config)
                    OpenOnStartPanel(profile)
                    ScreensaverPanel()
                    AddonsPanel(config)
                }
            }
            if (LocalFormFactor.current.touch) {
                Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { left(); right() }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) { left() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) { right() }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** A short heading over a few related panels. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Tv.Muted)
        content()
    }
}

/** Not working? A check of this TV's internet, the home network and the TV service, sent to the TV admin. */
@Composable
private fun CheckPanel(vm: MainViewModel) {
    val report by vm.diagnosis.collectAsState()
    val running by vm.diagnosing.collectAsState()
    Panel {
        Text("Not working?", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Run a check. It tests this TV's internet, the home network and the TV service, and sends the result to your TV admin.", fontSize = 14.sp, color = Tv.Muted)
        TvButton(if (running) "Checking…" else "Run a check", { vm.runDiagnosis() }, enabled = !running)
        report?.let { r ->
            Text(r.verdict.tvMessage, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (r.verdict.severity == 0) Tv.Good else Tv.Warn)
            Text(r.summary, fontSize = 14.sp, color = Tv.Muted)
        }
    }
}

@Composable
private fun ChannelListsPanel(vm: MainViewModel) {
    Panel {
        Text("Channel lists", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Downloads channels, movies, series and the guide again.", fontSize = 14.sp, color = Tv.Muted)
        TvButton("Reload channels", { vm.reload() })
    }
}

/** This TV's name, ID and app version, and when its lists last updated. */
@Composable
private fun AboutPanel(config: DeviceConfig) {
    val ctx = LocalContext.current
    Panel {
        Text("About this TV", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Info("Name", config.nickname ?: "-")
        Info("Device ID", ctx.app.portal.deviceId ?: "-")
        Info("App version", BuildConfig.VERSION_NAME)
        Info("Lists updated", if (PlaybackStatus.lastRefresh > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(PlaybackStatus.lastRefresh)) else "this session not yet")
    }
}

/**
 * Stream quality: which quality plays first when there's a choice, for Live TV, Movies and Series.
 * Kept on this TV. Its first button is where focus lands on opening Settings.
 */
@Composable
private fun StreamQualityPanel() {
    val ctx = LocalContext.current
    val menus = LocalItemMenus.current
    Panel {
        Text("Stream quality", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Which copy of a movie or show, or version of a channel, plays first when there's more than one. " +
                "Auto plays what the app picks today. Picture & sound in the player still changes it for one video.",
            fontSize = 14.sp, color = Tv.Muted,
        )
        for ((kind, label) in listOf(Kind.LIVE to "Live TV", Kind.MOVIE to "Movies", Kind.SERIES to "Series")) {
            val pref by ctx.app.quality.flow(kind).collectAsState()
            TvButton("$label: ${pref.label}", {
                menus.open = ItemMenu(
                    "Stream quality · $label",
                    QualityPref.entries.map { q -> (if (q == pref) "✓  ${q.label}" else q.label) to { ctx.app.quality.set(kind, q); Usage.log(Usage.Action.SETTING_CHANGE, qualityWord(kind)) } },
                )
            }, entryPoint(kind == Kind.LIVE))
        }
    }
}

private fun qualityWord(kind: String) = when (kind) {
    Kind.LIVE -> Usage.Word.STREAM_QUALITY_LIVE
    Kind.MOVIE -> Usage.Word.STREAM_QUALITY_MOVIES
    else -> Usage.Word.STREAM_QUALITY_SERIES
}

/** Paid add-ons for this TV's profile and where each stands; they're bought on the portal. Hidden when there are none. */
@Composable
private fun AddonsPanel(config: DeviceConfig) {
    val ctx = LocalContext.current
    val addons = remember(config) { Addons.list(config, ctx.app.portal.contactAt, System.currentTimeMillis()) }
    if (addons.isEmpty()) return
    LaunchedEffect(Unit) { Usage.log(Usage.Screen.ADDONS, Usage.Action.OPEN) }
    Panel {
        Text("Add-ons", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        for (a in addons) {
            Column {
                Text(a.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(a.line, fontSize = 14.sp, color = if (a.state == Addons.State.ACTIVE && a.known) Tv.Good else Tv.Muted)
            }
        }
    }
}

/**
 * Shown when the profile has Open when the TV turns on: whether this TV can do it yet, and how to
 * give it the one permission it needs.
 */
@Composable
private fun OpenOnStartPanel(profile: Profile?) {
    if (profile?.openOnStart != true) return
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(OpenOnStart.current(ctx)) }
    var noPage by remember { mutableStateOf(false) }
    // The permission is granted outside the app (its settings page or adb), so look again while this shows.
    LaunchedEffect(Unit) { while (true) { state = OpenOnStart.current(ctx); delay(2_000) } }
    Panel {
        Text("Open when the TV turns on", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (state == OpenOnStart.State.READY) {
            Text("On. Family TV opens by itself whenever this TV starts or wakes.", fontSize = 15.sp, color = Tv.Good)
        } else {
            Text("Your TV admin turned this on. This TV needs the Display over other apps permission for Family TV first.", fontSize = 14.sp, color = Tv.Muted)
            TvButton("Allow", { Usage.log(Usage.Action.SETTING_CHANGE, Usage.Word.OPEN_ON_START); noPage = !OpenOnStart.askPermission(ctx) })
            if (noPage) {
                Text("This TV has no page for it. From a computer, with network debugging on in the TV's developer options, run:", fontSize = 14.sp, color = Tv.Muted)
                Text(OpenOnStart.ADB_GRANT, fontSize = 14.sp)
            }
        }
    }
}

/**
 * The TV home's screensaver: when it starts (or Off) and what it shows. Google Photos is listed but
 * waits on the portal's sign-in, so for now it only says so. TV home only, and kept on this TV.
 */
@Composable
private fun ScreensaverPanel() {
    if (!LocalGlass.current) return
    val prefs = LocalContext.current.app.glassPrefs
    val menus = LocalItemMenus.current
    val minutes by prefs.saverMinutes.collectAsState()
    val source by prefs.saverSource.collectAsState()
    Panel {
        Text("Screensaver", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Shows on the home screen when nobody has pressed a button for a while. Never during a video.", fontSize = 14.sp, color = Tv.Muted)
        TvButton("Starts after: ${saverMinutesLabel(minutes)}", {
            menus.open = ItemMenu(
                "Screensaver starts after",
                GlassPrefs.SAVER_CHOICES.map { m ->
                    (if (m == minutes) "✓  ${saverMinutesLabel(m)}" else saverMinutesLabel(m)) to { prefs.setSaverMinutes(m); Usage.log(Usage.Action.SETTING_CHANGE, Usage.Word.SCREENSAVER) }
                },
            )
        })
        TvButton("Pictures: ${source.label}", {
            menus.open = ItemMenu(
                "Screensaver pictures",
                SaverSource.entries.map { s ->
                    val label = if (s == SaverSource.PHOTOS) "${s.label} (not set up yet)" else s.label
                    (if (s == source) "✓  $label" else label) to {
                        if (s == SaverSource.PHOTOS) {
                            menus.open = ItemMenu(
                                "Google Photos needs a setup on the portal first. It isn't ready yet.",
                                listOf("OK" to {}),
                            )
                        } else {
                            prefs.setSaverSource(s)
                            Usage.log(Usage.Action.SETTING_CHANGE, Usage.Word.SCREENSAVER)
                        }
                    }
                },
            )
        })
    }
}

private fun saverMinutesLabel(m: Int) = if (m == 0) "Off" else "$m minutes"

/**
 * Who watches on this TV (backlog items 17 and 18): add someone by name, as a kids viewer when the
 * profile has a parental PIN, or remove them. While a kids viewer watches, changes need the PIN.
 * TV only, and kept on this TV.
 */
@Composable
private fun ViewersPanel(profile: Profile?) {
    if (profile == null || LocalFormFactor.current.touch) return
    val viewers = LocalContext.current.app.viewers
    val menus = LocalItemMenus.current
    val tick by viewers.changes.collectAsState()
    val list = remember(tick) { viewers.all() }
    val kidsWatching = remember(tick) { viewers.current()?.kids == true }
    var unlocked by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(asking) { if (asking) { delay(50); runCatching { firstKey.requestFocus() } } }
    // Once the PIN is in, the pad is gone; focus goes to the first of the viewers it opened.
    val afterPin = remember { FocusRequester() }
    var unlockedAt by remember { mutableIntStateOf(0) }
    LaunchedEffect(unlockedAt) { if (unlockedAt > 0) { delay(50); runCatching { afterPin.requestFocus() } } }
    val hasPin = !profile.parentalPin.isNullOrEmpty()
    Panel {
        Text("Who's watching", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Add the people who watch on this TV, then pick one from the top bar. Each gets their own Continue watching, " +
                "favourites and For you here. A kids viewer sees kids' categories only, and leaving needs the parental PIN. Kept on this TV only.",
            fontSize = 14.sp, color = Tv.Muted,
        )
        when {
            kidsWatching && !unlocked && asking -> PinPrompt(profile, Modifier.focusRequester(firstKey)) { unlocked = true; asking = false; unlockedAt++ }
            kidsWatching && !unlocked -> {
                Text("A kids viewer is watching. Enter the parental PIN to change who watches here.", fontSize = 14.sp, color = Tv.Muted)
                TvButton("Enter PIN", { asking = true })
            }
            else -> {
                list.forEachIndexed { i, v ->
                    TvButton("Remove ${v.name}" + if (v.kids) " (kids)" else "", {
                        menus.open = ItemMenu("Remove ${v.name}? Their favourites and Continue watching on this TV go too.", listOf("Remove" to { viewers.remove(v) }, "Keep" to {}))
                    }, if (i == 0) Modifier.focusRequester(afterPin) else Modifier)
                }
                if (list.size < Viewers.MAX) {
                    NameField(name, if (list.isEmpty()) Modifier.focusRequester(afterPin) else Modifier) { name = it.take(Viewers.MAX_NAME) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton("Add", { if (viewers.add(profile, name, kids = false) != null) name = "" }, enabled = name.isNotBlank())
                        if (hasPin) TvButton("Add as kids", { if (viewers.add(profile, name, kids = true) != null) name = "" }, enabled = name.isNotBlank())
                    }
                    if (!hasPin) Text("A kids viewer needs a parental PIN: set one for this profile in the portal.", fontSize = 14.sp, color = Tv.Muted)
                }
            }
        }
    }
}

/** A viewer's name: OK types with the TV's keyboard; Done, Up or Down moves on, as do Left and Right past either end of the text. */
@Composable
private fun NameField(value: String, modifier: Modifier, onValue: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    // Kept as a TextFieldValue so the arrows know where the cursor is.
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    BasicTextField(
        value = field,
        onValueChange = { field = it; if (it.text != value) onValue(it.text) },
        singleLine = true,
        textStyle = TextStyle(color = Tv.Text, fontSize = 17.sp),
        cursorBrush = SolidColor(Tv.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.moveFocus(FocusDirection.Down) }),
        modifier = modifier.width(320.dp).onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { e ->
            val cursor = field.selection.takeIf { it.collapsed }?.start
            val dir = when (e.key) {
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                Key.DirectionLeft -> FocusDirection.Left.takeIf { cursor == 0 }
                Key.DirectionRight -> FocusDirection.Right.takeIf { cursor == field.text.length }
                else -> null
            }
            if (dir != null && e.type == KeyEventType.KeyDown) focusManager.moveFocus(dir)
            dir != null
        },
        decorationBox = { inner ->
            val shape = RoundedCornerShape(24.dp)
            Box(Modifier.clip(shape).background(Tv.Raised).border(if (focused) 3.dp else 1.dp, if (focused) Tv.Accent else Tv.Line, shape).padding(horizontal = 18.dp, vertical = 10.dp)) {
                if (value.isEmpty()) Text("Name", fontSize = 17.sp, color = Tv.Muted)
                inner()
            }
        },
    )
}

/**
 * With a parental PIN, entering it here opens Categories' Adult Content (and adult live categories
 * in Live TV) without asking until the app restarts. Without one, adult content is hidden and this
 * says how to allow it; the PIN is set in the portal, not on the TV.
 */
@Composable
private fun ParentalPinPanel(profile: Profile?) {
    if (profile == null) return
    if (!adultAllowed(profile)) {
        Panel {
            Text("Parental lock", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Adult channels are hidden. Set a parental PIN for this profile in the portal to allow them.", fontSize = 14.sp, color = Tv.Muted)
        }
        return
    }
    val ctx = LocalContext.current
    val unlocked by ctx.app.adultUnlocked.collectAsState()
    val touch = LocalFormFactor.current.touch
    val memory = LocalFocusMemory.current
    var open by remember { mutableStateOf(false) }
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(open) { if (open && !touch) { delay(50); runCatching { firstKey.requestFocus() } } }
    Panel {
        Text("Parental lock", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Adult Content in Categories asks for this PIN. Enter it here to stop asking until the app restarts.", fontSize = 14.sp, color = Tv.Muted)
        when {
            unlocked -> Text("Unlocked until the app restarts.", fontSize = 15.sp, color = Tv.Good)
            open -> PinPrompt(profile, Modifier.focusRequester(firstKey)) {
                ctx.app.adultUnlocked.value = true
                Usage.log(Usage.Action.SETTING_CHANGE, Usage.Word.PARENTAL_UNLOCK)
                open = false
                // The pad and its button are gone; focus goes to where it was before, close by.
                if (!touch) memory?.restore()
            }
            else -> TvButton("Enter PIN", { open = true })
        }
    }
}

@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().background(Tv.Panel, Tv.Shape).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
internal fun Info(label: String, value: String) {
    // The value takes the rest of the row and wraps, so a narrow panel can't squeeze it to nothing.
    Row {
        Text(label, Modifier.width(130.dp), fontSize = 15.sp, color = Tv.Muted)
        Text(value, Modifier.weight(1f), fontSize = 15.sp)
    }
}
