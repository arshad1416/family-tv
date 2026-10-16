package app.familytv.ui

import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import app.familytv.BuildConfig
import app.familytv.app
import app.familytv.sync.CommandRunner
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var message by mutableStateOf<String?>(null)
    internal var sourceChoice by mutableStateOf<CatalogSourceRequest?>(null)
    internal fun chooseSource(request: CatalogSourceRequest) { sourceChoice = request }
    internal fun sourceRequestCurrent(request: CatalogSourceRequest) = sourceChoice === request && vm.profile === request.profile
    private val exit = HoldBackToExit(this)

    // androidx marks this restricted, but overriding the platform method is fine.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = exit.onKey(event) || super.dispatchKeyEvent(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    // Android 15 draws edge to edge; keep clear of a phone's status and navigation bars.
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        val screen by vm.screen.collectAsState()
                        when (val s = screen) {
                            Screen.Loading -> Centered { Text("Starting…", fontSize = 22.sp) }
                            is Screen.Pairing -> PairingScreen(s.code)
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
                    menus.Host()
                    sourceChoice?.let { request ->
                        if (vm.profile === request.profile) CatalogSources(request) { sourceChoice = null }
                        else LaunchedEffect(request) { sourceChoice = null }
                    }
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
                }
              }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.start()
    }

    override fun onStop() {
        sourceChoice = null
        vm.stop()
        super.onStop()
    }
}

@Composable
fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun SyncingScreen(s: Screen.Syncing) = Centered {
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
private fun PairingScreen(code: String) {
    val qr = remember(code) { qrImage("${BuildConfig.PORTAL_URL.trimEnd('/')}/#pair=$code") }
    var dots by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(800); dots = (dots + 1) % 4 } }
    val words: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Set up this TV", fontSize = 34.sp, fontWeight = FontWeight.Bold)
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

/** Compose may provide a ContextWrapper; never silently drop a catalog selection. */
internal tailrec fun android.content.Context.sourceActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is android.content.ContextWrapper -> if (baseContext !== this) baseContext.sourceActivity() else null
    else -> null
}
