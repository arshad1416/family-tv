package app.familytv.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import app.familytv.BuildConfig
import app.familytv.app
import app.familytv.sync.CommandRunner
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var message by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            app.commands.events.collect { e ->
                if (e is CommandRunner.UiEvent.Message) message = e.text
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val screen by vm.screen.collectAsState()
                    when (val s = screen) {
                        Screen.Loading -> Centered { Text("Starting…", fontSize = 22.sp) }
                        is Screen.Pairing -> PairingScreen(s.code)
                        is Screen.Syncing -> Centered { Text("${s.step}…", fontSize = 22.sp) }
                        is Screen.Failed -> Centered {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(s.message, fontSize = 22.sp)
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { vm.stop(); vm.start() }) { Text("Try again") }
                            }
                        }
                        is Screen.Ready -> HomeScreen(vm, s.config)
                    }
                    message?.let { text ->
                        Dialog(onDismissRequest = { message = null }) {
                            Column(
                                Modifier.background(Color(0xFF1F2937)).padding(32.dp).width(560.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                Text("Message from your TV admin", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text(text, fontSize = 18.sp)
                                Button(onClick = { message = null }) { Text("OK") }
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
        vm.stop()
        super.onStop()
    }
}

@Composable
fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun PairingScreen(code: String) {
    val qr = remember(code) {
        val url = "${BuildConfig.PORTAL_URL.trimEnd('/')}/#pair=$code"
        val m = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 360, 360)
        val px = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        android.graphics.Bitmap.createBitmap(px, m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    var dots by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(800); dots = (dots + 1) % 4 } }
    Centered {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(64.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Set up this TV", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("Give this code to whoever manages your TV,\nor scan the QR code.", fontSize = 20.sp)
                Text(code.chunked(3).joinToString(" "), fontSize = 72.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
                Text("Waiting" + ".".repeat(dots), fontSize = 18.sp, color = Color.Gray)
            }
            Image(qr, contentDescription = "Pairing QR code", modifier = Modifier.size(260.dp))
        }
    }
}
