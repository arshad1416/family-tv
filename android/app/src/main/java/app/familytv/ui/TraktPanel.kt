package app.familytv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.trakt.Trakt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Settings section for Trakt: connect with a code (no typing on the remote), see the account,
 * sync now, disconnect. Hidden when the portal has no Trakt credentials.
 */
@Composable
fun TraktPanel(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val trakt = ctx.app.trakt
    val state by trakt.state.collectAsState()
    val touch = LocalFormFactor.current.touch
    LaunchedEffect(Unit) { trakt.refreshState() }
    if (state is Trakt.State.Off) return

    // The pressed button disappears when the state changes; on a TV, put focus on the next one.
    val primary = remember { FocusRequester() }
    var moveFocus by remember { mutableStateOf(false) }
    LaunchedEffect(state::class) {
        if (moveFocus && !touch) { delay(50); runCatching { primary.requestFocus() } }
        moveFocus = false
    }
    var confirmDisconnect by remember { mutableStateOf(false) }

    Panel(modifier) {
        Text("Trakt", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Keeps favourite movies and series, what you've watched and where you stopped the same on every device " +
                "signed in to one Trakt account. Favourite channels stay on this device, because Trakt has no live TV.",
            fontSize = 14.sp, color = Tv.Muted,
        )
        when (val s = state) {
            is Trakt.State.SignedOut -> {
                s.message?.let { Text(it, fontSize = 15.sp, color = Tv.Warn) }
                TvButton("Connect Trakt", { moveFocus = true; trakt.startLink() }, Modifier.focusRequester(primary))
            }
            is Trakt.State.Linking -> LinkCode(s, touch) { TvButton("Cancel", { moveFocus = true; trakt.cancelLink() }, Modifier.focusRequester(primary)) }
            is Trakt.State.SignedIn -> {
                Info("Account", s.name)
                Info(
                    "Last sync",
                    when {
                        s.syncing -> "Syncing…"
                        s.lastSync > 0 -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.lastSync))
                        else -> "Not yet"
                    },
                )
                s.error?.let { Text(it, fontSize = 15.sp, color = Tv.Warn) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(if (s.syncing) "Syncing…" else "Sync now", { ctx.app.scope.launch { trakt.sync.now() } }, Modifier.focusRequester(primary), enabled = !s.syncing)
                    TvButton(
                        if (confirmDisconnect) "Press again to disconnect" else "Disconnect",
                        {
                            if (confirmDisconnect) { moveFocus = true; confirmDisconnect = false; ctx.app.scope.launch { trakt.disconnect() } }
                            else confirmDisconnect = true
                        },
                    )
                }
            }
            Trakt.State.Off -> Unit
        }
    }
}

/** The sign-in code, where to enter it, and a QR of that address, with a countdown. */
@Composable
private fun LinkCode(s: Trakt.State.Linking, touch: Boolean, actions: @Composable () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val left = ((s.expiresAt - now) / 1000).coerceAtLeast(0)
    val qr = remember(s.url) { qrImage(s.url, 300) }
    val words: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (touch) "Open this address in a browser:" else "On your phone or computer, go to", fontSize = 15.sp)
            Text(s.url.removePrefix("https://"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Tv.Accent)
            Text("and enter this code:", fontSize = 15.sp)
            Text(s.userCode, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Text("Waiting for you to approve it · %d:%02d left".format(left / 60, left % 60), fontSize = 13.sp, color = Tv.Muted)
            actions()
        }
    }
    if (touch) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            words()
            Image(qr, contentDescription = "QR code for ${s.url}", modifier = Modifier.size(160.dp))
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            words()
            Image(qr, contentDescription = "QR code for ${s.url}", modifier = Modifier.size(160.dp))
        }
    }
}
