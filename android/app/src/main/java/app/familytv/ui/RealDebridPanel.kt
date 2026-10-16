package app.familytv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import app.familytv.app
import app.familytv.debrid.RealDebrid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Settings section for Real-Debrid: connect with a code, which signs in every TV in this profile,
 * see the account and how many files it has, load the list again, disconnect.
 */
@Composable
fun RealDebridPanel(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val rd = ctx.app.realDebrid
    val state by rd.state.collectAsState()
    val touch = LocalFormFactor.current.touch
    LaunchedEffect(Unit) { rd.refreshState(); rd.refreshSoon() }

    val primary = remember { FocusRequester() }
    var moveFocus by remember { mutableStateOf(false) }
    LaunchedEffect(state::class) {
        if (moveFocus && !touch) { delay(50); runCatching { primary.requestFocus() } }
        moveFocus = false
    }
    var confirmDisconnect by remember { mutableStateOf(false) }

    Panel(modifier) {
        Text("Real-Debrid", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Manage the separate device-code connection to your Real-Debrid account. " +
                "Catalog movies and episodes use the manual IPTV / Torrentio source chooser. Connecting here connects every TV in this profile.",
            fontSize = 14.sp, color = Tv.Muted,
        )
        when (val s = state) {
            is RealDebrid.State.SignedOut -> {
                s.message?.let { Text(it, fontSize = 15.sp, color = Tv.Warn) }
                TvButton("Connect Real-Debrid", { moveFocus = true; rd.startLink() }, Modifier.focusRequester(primary))
            }
            is RealDebrid.State.Linking -> LinkCode(s.userCode, s.url, s.qrUrl, s.expiresAt, touch) {
                TvButton("Cancel", { moveFocus = true; rd.cancelLink() }, Modifier.focusRequester(primary))
            }
            is RealDebrid.State.SignedIn -> {
                Info("Account", s.name)
                Info("Premium", s.premiumUntil?.let { "until $it" } ?: "No (needed to play)")
                Info(
                    "Files",
                    when {
                        s.refreshing -> "Loading…"
                        s.lastRefresh > 0 -> "${s.files} videos · checked ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.lastRefresh))}"
                        else -> "Not loaded yet"
                    },
                )
                s.error?.let { Text(it, fontSize = 15.sp, color = Tv.Warn) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(if (s.refreshing) "Loading…" else "Load files again", { ctx.app.scope.launch { rd.refresh(force = true) } }, Modifier.focusRequester(primary), enabled = !s.refreshing)
                    TvButton(
                        if (confirmDisconnect) "Press again to disconnect all TVs" else "Disconnect",
                        {
                            if (confirmDisconnect) { moveFocus = true; confirmDisconnect = false; ctx.app.scope.launch { rd.disconnect() } }
                            else confirmDisconnect = true
                        },
                    )
                }
                Text("Choose IPTV or Torrentio manually from a catalog movie or episode. The profile API key for Torrentio is configured separately in Settings.", fontSize = 14.sp, color = Tv.Muted)
            }
        }
    }
}
