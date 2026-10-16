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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.familytv.BuildConfig
import app.familytv.app
import app.familytv.portal.DeviceConfig
import app.familytv.portal.Profile
import app.familytv.sync.PlaybackStatus
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(vm: MainViewModel, config: DeviceConfig) {
    val ctx = LocalContext.current
    val report by vm.diagnosis.collectAsState()
    val running by vm.diagnosing.collectAsState()
    var localAccount by remember(config.profile) { mutableStateOf(false) }
    if (localAccount) ExternalDiscovery(config.profile ?: Profile(), "", { localAccount = false }, accountOnly = true)
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
                RealDebridPanel()
                Panel {
                    Text("External movie & series sources", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Enter your Real-Debrid API key once for your own authorized devices in this profile. The key is masked and encrypted by the portal; replace or remove it for all profile devices here. External title search is in Search.", fontSize = 14.sp, color = Tv.Muted)
                    TvButton("Manage profile Real-Debrid API key", { localAccount = true })
                }
                PackageSorterPanel(config.profile)
            }
        })
        Spacer(Modifier.height(20.dp))
        Column(if (touch) Modifier.widthIn(max = 640.dp) else Modifier) { ArrangeCategoriesPanel(config.profile ?: Profile()) }
        Spacer(Modifier.height(32.dp))
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
