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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.familytv.BuildConfig
import app.familytv.app
import app.familytv.portal.DeviceConfig
import app.familytv.sync.PlaybackStatus
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(vm: MainViewModel, config: DeviceConfig) {
    val ctx = LocalContext.current
    val report by vm.diagnosis.collectAsState()
    val running by vm.diagnosing.collectAsState()
    val unlocked by vm.adultUnlocked.collectAsState()
    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        PageTitle("Settings", bottom = 18.dp)
        // TV: the two panels side by side. Phones and tablets: stacked, so nothing runs off a narrow screen.
        val touch = LocalFormFactor.current.touch
        val panes: @Composable (@Composable () -> Unit) -> Unit = { content ->
            if (touch) Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { content() }
            else Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) { content() }
        }
        panes {
            Panel(if (touch) Modifier.fillMaxWidth() else Modifier.width(560.dp)) {
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
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Panel {
                    Text("This TV", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Info("Name", config.nickname ?: "-")
                    Info("Device ID", ctx.app.portal.deviceId ?: "-")
                    Info("App version", BuildConfig.VERSION_NAME)
                    Info("Lists updated", if (PlaybackStatus.lastRefresh > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(PlaybackStatus.lastRefresh)) else "this session not yet")
                }
                if (!config.profile?.parentalPin.isNullOrEmpty() && !unlocked) {
                    Panel {
                        Text("Parental lock", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Enter the PIN to show locked categories.", fontSize = 14.sp, color = Tv.Muted)
                        PinPad(pin, onDigit = { if (pin.length < 8) pin += it; pinError = false }, onClear = { pin = "" }, onOk = {
                            pinError = !vm.unlock(pin); pin = ""
                        })
                        if (pinError) Text("Wrong PIN", color = Tv.Bad)
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val fill = if (LocalFormFactor.current.touch) Modifier.fillMaxWidth() else Modifier
    Column(modifier.then(fill).background(Tv.Panel, Tv.Shape).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
private fun Info(label: String, value: String) {
    Row {
        Text(label, Modifier.width(130.dp), fontSize = 15.sp, color = Tv.Muted)
        Text(value, fontSize = 15.sp)
    }
}

@Composable
private fun PinPad(pin: String, onDigit: (String) -> Unit, onClear: () -> Unit, onOk: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (pin.isEmpty()) "Enter PIN" else "•".repeat(pin.length), fontSize = 20.sp)
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("C", "0", "OK")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    FocusBox(
                        onClick = { when (k) { "C" -> onClear(); "OK" -> onOk(); else -> onDigit(k) } },
                        color = Tv.Raised, contentAlignment = Alignment.Center,
                        modifier = Modifier.width(68.dp).height(48.dp),
                    ) { Text(k, fontSize = 18.sp) }
                }
            }
        }
    }
}
