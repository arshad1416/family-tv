package app.familytv.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.text.style.TextAlign
import app.familytv.BuildConfig
import app.familytv.app
import app.familytv.perf.Perf
import app.familytv.start.OpenOnStart
import app.familytv.sync.CommandRunner
import app.familytv.sync.TvLock
import app.familytv.transfer.Transfer
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var message by mutableStateOf<String?>(null)
    private val exit = HoldBackToExit(this)
    /** Phones and tablets: the player at the top of this screen (TV uses [PlayerActivity]). */
    val dock = DockedPlayer(this)
    /** The startup splash while it shows (see [SplashAnimation]). */
    private var splash by mutableStateOf<Splash.Choice?>(null)
    /** For the instrumented tests: keys are swallowed while the startup splash plays. */
    internal val splashing get() = splash != null
    private var splashSkipped by mutableStateOf(false)
    /** The splash video is near its end, so the app draws itself under it. */
    private var splashLate by mutableStateOf(false)

    // androidx marks this restricted, but overriding the platform method is fine.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Any key skips the startup splash; none reaches the app until the splash has gone.
        if (splash != null) {
            if (event.action == KeyEvent.ACTION_UP) splashSkipped = true
            return true
        }
        return dock.onKey(event) || exit.onKey(event) || super.dispatchKeyEvent(event)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        dock.onUserInteraction()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        dock.onConfigurationChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Perf.activityCreated()
        Perf.watchFrames(this)
        if (savedInstanceState == null) splash = Splash.choice(app.portal.cachedConfig?.profile?.splash, Calendar.getInstance().get(Calendar.MONTH) + 1)
        if (savedInstanceState == null) takeTransfer(intent)
        lifecycleScope.launch {
            app.commands.events.collect { e ->
                if (e is CommandRunner.UiEvent.Message) message = e.text
            }
        }
        setContent {
            FamilyTvTheme {
              val menus = remember { ItemMenus() }
              CompositionLocalProvider(LocalFormFactor provides rememberFormFactor(), LocalItemMenus provides menus) {
                Surface(modifier = Modifier.fillMaxSize(), colors = SurfaceDefaults.colors(containerColor = Tv.Bg, contentColor = Tv.Text)) {
                    // While the startup splash covers it, the app lays itself out underneath but draws nothing, so
                    // the splash has the GPU to itself until near its end. Android 15 draws edge to edge; the host
                    // keeps clear of a phone's status and navigation bars.
                    Box(Modifier.fillMaxSize().drawWithContent { if (splash == null || splashSkipped || splashLate) drawContent() }) {
                        dock.Host {
                            val screen by vm.screen.collectAsState()
                            val browsing = screen is Screen.Ready && splash == null && dock.mode != "full"
                            SideEffect { Perf.browsing = browsing }
                            val lock = TvLock.message.collectAsState().value
                            // Locked from the portal: nothing plays, and the home screen (with its previews) goes away.
                            if (lock != null) {
                                LockScreen(lock)
                                LaunchedEffect(Unit) { dock.close() }
                            } else when (val s = screen) {
                                Screen.Loading -> Box(Modifier.fillMaxSize()) // The startup splash covers it; no text to flash.
                                is Screen.Pairing -> PairingScreen(s.code, s.unpaired)
                                is Screen.Syncing -> SyncingScreen(s)
                                is Screen.Failed -> Centered {
                                    val retry = remember { FocusRequester() }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(s.message, fontSize = 22.sp)
                                        Spacer(Modifier.height(16.dp))
                                        TvButton("Try again", { vm.stop(); vm.start() }, Modifier.focusRequester(retry))
                                        LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
                                    }
                                }
                                is Screen.Ready -> HomeScreen(vm, s.config)
                            }
                        }
                    }
                    menus.Host()
                    message?.let { text ->
                        Dialog(onDismissRequest = { message = null }) {
                            Column(
                                Modifier.widthIn(max = 560.dp).background(Tv.Raised, Tv.Shape).padding(32.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                Text("Message from your TV admin", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text(text, fontSize = 18.sp)
                                val ok = remember { FocusRequester() }
                                TvButton("OK", { message = null }, Modifier.focusRequester(ok))
                                LaunchedEffect(Unit) { runCatching { ok.requestFocus() } }
                            }
                        }
                    }
                    splash?.let { SplashAnimation(it, splashSkipped, onSkip = { splashSkipped = true }, onDone = { splash = null }, onLate = { splashLate = true }) }
                }
              }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeTransfer(intent)
    }

    /** A move code from the Rust app ([Transfer]), read once. */
    private fun takeTransfer(intent: Intent?) {
        val code = intent?.getStringExtra(Transfer.EXTRA) ?: return
        intent.removeExtra(Transfer.EXTRA)
        vm.transfer(code)
    }

    override fun onStart() {
        super.onStart()
        vm.start()
        dock.onStart()
    }

    override fun onResume() {
        super.onResume()
        // Picks up the Display over other apps permission as soon as the TV's settings page returns here.
        OpenOnStart.sync(this)
    }

    override fun onStop() {
        dock.onStop()
        vm.stop()
        super.onStop()
    }

    override fun onDestroy() {
        dock.close()
        super.onDestroy()
    }
}

/** What a TV locked from the portal shows instead of everything else; see [TvLock]. */
@Composable
private fun LockScreen(message: String) = Centered {
    Column(Modifier.widthIn(max = 720.dp).padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Icon(Icons.Filled.Lock, contentDescription = null, Modifier.size(64.dp), tint = Tv.Muted)
        Text(message, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun SyncingScreen(s: Screen.Syncing) = Centered {
    // A load runs for minutes on a Fire Stick: the screen stays on so the TV's own screensaver doesn't stop the app
    // (and its check-ins) part way; Home takes the screen over from here.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 520.dp).padding(horizontal = 16.dp)) {
        Text("${s.step}…", fontSize = 22.sp)
        Spacer(Modifier.height(20.dp))
        // Plain boxes rather than a Material progress bar: tv-material has none, and this stays light.
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x33FFFFFF))) {
            Box(Modifier.fillMaxWidth(s.fraction).fillMaxHeight().background(Tv.Accent))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            listOfNotNull("${(s.fraction * 100).toInt()}%", s.detail).joinToString("  ·  "),
            fontSize = 16.sp,
            color = Color(0xAAFFFFFF),
        )
        Spacer(Modifier.height(8.dp))
        Text("The first load takes a minute or two. After that the TV opens straight away.", fontSize = 14.sp, color = Color(0x88FFFFFF))
    }
}

@Composable
private fun PairingScreen(code: String, unpaired: Boolean) {
    val qr = remember(code) { qrImage("${BuildConfig.PORTAL_URL.trimEnd('/')}/#pair=$code") }
    var dots by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(800); dots = (dots + 1) % 4 } }
    val words: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (unpaired) {
                Text("This device was unpaired", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("Pair again", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Tv.Accent)
            } else {
                Text("Set up this TV", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
            Text("Give this code to whoever manages your TV,\nor scan the QR code.", fontSize = 20.sp)
            Text(code.chunked(3).joinToString(" "), fontSize = 72.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
            Text("Waiting" + ".".repeat(dots), fontSize = 18.sp, color = Color.Gray)
        }
    }
    Centered {
        if (LocalFormFactor.current == FormFactor.Phone) {
            // Portrait phone: stack the code over the QR so neither runs off the screen.
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                words()
                Image(qr, contentDescription = "Pairing QR code", modifier = Modifier.size(220.dp))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                words()
                Image(qr, contentDescription = "Pairing QR code", modifier = Modifier.size(260.dp))
            }
        }
    }
}
