package app.familytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import app.familytv.user.Viewers
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(vm: MainViewModel, config: DeviceConfig) {
    val ctx = LocalContext.current
    val report by vm.diagnosis.collectAsState()
    val running by vm.diagnosing.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        PageTitle("Settings", bottom = 18.dp)
        // TV: two columns sharing the width, so neither is squeezed. Phones and tablets: stacked.
        val touch = LocalFormFactor.current.touch
        val panes: @Composable (left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit) -> Unit = { left, right ->
            if (touch) {
                Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { left(Modifier); right(Modifier) }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) { left(Modifier.weight(1f)); right(Modifier.weight(1f)) }
            }
        }
        panes({ mod ->
            Panel(mod) {
                Text("Not working?", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Run a check. It tests this TV's internet, the home network and the TV service, and sends the result to your TV admin.", fontSize = 15.sp, color = Tv.Muted)
                TvButton(if (running) "Checking…" else "Run a check", { vm.runDiagnosis() }, entryPoint(), enabled = !running)
                report?.let { r ->
                    Text(r.verdict.tvMessage, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (r.verdict.severity == 0) Tv.Good else Tv.Warn)
                    Text(r.summary, fontSize = 14.sp, color = Tv.Muted)
                }
                Spacer(Modifier.height(8.dp))
                Text("Channel lists", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Downloads channels, movies, series and the guide again.", fontSize = 14.sp, color = Tv.Muted)
                TvButton("Reload channels", { vm.reload() })
            }
        }, { mod ->
            Column(mod, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Panel {
                    Text("This TV", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Info("Name", config.nickname ?: "-")
                    Info("Device ID", ctx.app.portal.deviceId ?: "-")
                    Info("App version", BuildConfig.VERSION_NAME)
                    Info("Lists updated", if (PlaybackStatus.lastRefresh > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(PlaybackStatus.lastRefresh)) else "this session not yet")
                }
                // Only shown when the portal has Trakt set up.
                if (config.trakt?.clientId != null) TraktPanel()
                AddonsPanel(config)
                StreamQualityPanel()
                PackageSorterPanel(config.profile)
                OpenOnStartPanel(config.profile)
                ViewersPanel(config.profile)
                ParentalPinPanel(config.profile)
            }
        })
        Spacer(Modifier.height(20.dp))
        Column(if (touch) Modifier.widthIn(max = 640.dp) else Modifier) { ArrangeCategoriesPanel(config.profile ?: Profile()) }
        Spacer(Modifier.height(32.dp))
    }
}

/** Stream quality: which quality plays first when there's a choice, for Live TV, Movies and Series. Kept on this TV. */
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
            })
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
    val hasPin = !profile.parentalPin.isNullOrEmpty()
    Panel {
        Text("Who's watching", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Add the people who watch on this TV, then pick one from the top bar. Each gets their own Continue watching, " +
                "favourites and For you here. A kids viewer sees kids' categories only, and leaving needs the parental PIN. Kept on this TV only.",
            fontSize = 14.sp, color = Tv.Muted,
        )
        when {
            kidsWatching && !unlocked && asking -> PinPrompt(profile, Modifier.focusRequester(firstKey)) { unlocked = true; asking = false }
            kidsWatching && !unlocked -> {
                Text("A kids viewer is watching. Enter the parental PIN to change who watches here.", fontSize = 14.sp, color = Tv.Muted)
                TvButton("Enter PIN", { asking = true })
            }
            else -> {
                list.forEach { v ->
                    TvButton("Remove ${v.name}" + if (v.kids) " (kids)" else "", {
                        menus.open = ItemMenu("Remove ${v.name}? Their favourites and Continue watching on this TV go too.", listOf("Remove" to { viewers.remove(v) }, "Keep" to {}))
                    })
                }
                if (list.size < Viewers.MAX) {
                    NameField(name) { name = it.take(Viewers.MAX_NAME) }
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

/** A viewer's name: OK types with the TV's keyboard, Done or Down moves on. */
@Composable
private fun NameField(value: String, onValue: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = TextStyle(color = Tv.Text, fontSize = 17.sp),
        cursorBrush = SolidColor(Tv.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.moveFocus(FocusDirection.Down) }),
        modifier = Modifier.width(320.dp).onFocusChanged { focused = it.isFocused },
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
    val entry = pageEntry()
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
                // The pad is gone; send focus back to the top of Settings.
                if (!touch) entry.focus()
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
