package app.familytv.ui

import androidx.compose.foundation.background
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
import app.familytv.app
import app.familytv.data.Kind
import app.familytv.iptv.QualityPref
import app.familytv.portal.DeviceConfig
import app.familytv.portal.Profile
import app.familytv.start.OpenOnStart
import app.familytv.sync.PlaybackStatus
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
                StreamQualityPanel()
                PackageSorterPanel(config.profile)
                OpenOnStartPanel(config.profile)
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
                    QualityPref.entries.map { q -> (if (q == pref) "✓  ${q.label}" else q.label) to { ctx.app.quality.set(kind, q) } },
                )
            })
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
            TvButton("Allow", { noPage = !OpenOnStart.askPermission(ctx) })
            if (noPage) {
                Text("This TV has no page for it. From a computer, with network debugging on in the TV's developer options, run:", fontSize = 14.sp, color = Tv.Muted)
                Text(OpenOnStart.ADB_GRANT, fontSize = 14.sp)
            }
        }
    }
}

/**
 * Shown when the profile has a parental PIN: entering it here opens Categories' Adult Content
 * (and adult live categories in Live TV) without asking until the app restarts.
 */
@Composable
private fun ParentalPinPanel(profile: Profile?) {
    if (profile == null || profile.parentalPin.isNullOrEmpty()) return
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
