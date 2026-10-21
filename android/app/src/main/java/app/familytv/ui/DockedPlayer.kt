package app.familytv.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.tv.material3.Icon
import app.familytv.sync.TvLock
import app.familytv.usage.Usage

/**
 * Phones and tablets: the player inside the home screen, YouTube style. Upright, the video sits
 * across the top and the app stays usable below it. Turning a phone sideways fills the screen
 * with the system bars hidden, where it works like the TV player; a sideways tablet shows the
 * video on the left with the app beside it, and goes full screen with the button or a double
 * tap. It is one view throughout, only resized, so playback carries on without a restart.
 */
class DockedPlayer(private val activity: ComponentActivity) : PlayerHost {
    private var controller by mutableStateOf<PlayerController?>(null)
    private var full by mutableStateOf(false)
    /** Left full screen while sideways: stay docked until the screen is upright again. */
    private var holdDocked = false
    private var started = false
    private var modeSince = 0L
    private var watch: OrientationEventListener? = null

    override val docked get() = controller != null && !full
    override val canDock get() = true
    private val fullScreen get() = controller != null && full

    // For the instrumented tests (main thread only).
    internal val current: PlayerController? get() = controller
    internal val mode: String get() = when {
        controller == null -> "closed"
        full -> "full"
        tablet() && landscape() -> "side"
        else -> "docked"
    }

    fun play(intent: Intent) {
        if (TvLock.locked) return
        // Lines allow one connection: the old stream stops before the new one starts.
        controller?.let(::release)
        holdDocked = false
        full = landscape() && !tablet()
        controller = PlayerController(activity, intent, this).also { if (started) it.onStart() }
        modeSince = SystemClock.elapsedRealtime()
        Usage.opened(screen())
        applyBars()
    }

    override fun close() {
        val c = controller ?: return
        release(c)
        controller = null
        full = false
        holdDocked = false
        stopWatch()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        applyBars()
    }

    /** The full-screen button: turns the screen sideways even with rotation locked. */
    override fun enterFullScreen() {
        if (controller == null || full) return
        switchMode(true)
        if (!landscape()) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            // A tablet stays sideways until it leaves full screen, then follows how it's held.
            if (!tablet()) watchFor(landscape = true)
        }
    }

    /** Back's last step and the leave button: upright again, docked above the app. */
    override fun leaveFullScreen() {
        if (controller == null) return
        if (!full) return close()
        switchMode(false)
        if (landscape() && !tablet()) {
            holdDocked = true
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            watchFor(landscape = false)
        } else {
            stopWatch()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /**
     * Turning a phone switches between docked and full screen, unless a button just chose one. A
     * tablet only leaves full screen when turned upright; sideways it is side by side.
     */
    fun onConfigurationChanged() {
        if (controller == null) return
        val land = landscape()
        if (tablet()) {
            if (!land && full && activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) switchMode(false)
            return
        }
        if (!land) holdDocked = false
        val forced = activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (land && !full && !holdDocked) switchMode(true)
        else if (!land && full && !forced) switchMode(false)
    }

    /** From dispatchKeyEvent. Back belongs to the player while one is open; full screen also gets the rest. */
    fun onKey(e: KeyEvent): Boolean {
        val c = controller ?: return false
        if (e.keyCode != KeyEvent.KEYCODE_BACK) {
            if (!fullScreen) return false
            return when (e.action) {
                KeyEvent.ACTION_DOWN -> c.onKeyDown(e.keyCode, e)
                KeyEvent.ACTION_UP -> c.onKeyUp(e.keyCode, e)
                else -> false
            }
        }
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) if (full) c.touchBack() else close()
        return true
    }

    fun onUserInteraction() = controller?.onUserInteraction()

    fun onStart() {
        started = true
        controller?.onStart()
    }

    fun onStop() {
        started = false
        controller?.onStop()
    }

    /** The home screen, with the video docked above it, beside it (sideways tablet) or covering it. */
    @Composable
    fun Host(content: @Composable () -> Unit) {
        val c = controller
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = LocalFormFactor.current == FormFactor.Tablet && maxWidth > maxHeight
            val videoWidth = if (side) maxWidth * 2f / 3f else maxWidth
            val videoHeight = if (side) videoWidth * 9f / 16f else min(maxWidth * 9f / 16f, maxHeight * 0.45f)
            val docked = c != null && !full
            Box(
                Modifier.fillMaxSize().safeDrawingPadding()
                    .padding(start = if (docked && side) videoWidth else 0.dp, top = if (docked && !side) videoHeight else 0.dp),
            ) { content() }
            if (c != null) {
                // Full screen: nothing underneath takes a touch the video doesn't.
                if (full) Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } })
                key(c) {
                    AndroidView(
                        factory = { c.root },
                        modifier = if (full) Modifier.fillMaxSize() else Modifier.statusBarsPadding().width(videoWidth).height(videoHeight),
                    )
                }
            }
        }
    }

    private fun release(c: PlayerController) {
        Usage.left(screen(), (SystemClock.elapsedRealtime() - modeSince) / 1000)
        c.release()
    }

    private fun switchMode(toFull: Boolean) {
        if (full == toFull) return
        Usage.left(screen(), (SystemClock.elapsedRealtime() - modeSince) / 1000)
        full = toFull
        modeSince = SystemClock.elapsedRealtime()
        Usage.opened(screen())
        if (!toFull) controller?.onDocked()
        applyBars()
    }

    private fun screen() = if (full) Usage.Screen.PLAYER_FULLSCREEN else Usage.Screen.PLAYER_DOCKED

    private fun tablet() = activity.formFactor() == FormFactor.Tablet

    private fun landscape() = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun applyBars() {
        val window = activity.window
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (fullScreen) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * After a button forced the screen sideways or upright, hands rotation back to the person once
     * the device is actually held that way, so turning it afterwards works. With auto-rotate off
     * the forced side stays until the player changes mode or closes.
     */
    private fun watchFor(landscape: Boolean) {
        stopWatch()
        val rotation = ContextCompat.getDisplayOrDefault(activity).rotation
        val naturalPortrait = (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) != landscape()
        watch = object : OrientationEventListener(activity) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN || !autoRotate()) return
                val side = degrees in 60..120 || degrees in 240..300
                val upright = degrees <= 30 || degrees >= 330 || degrees in 150..210
                val heldLandscape = if (naturalPortrait) side else upright
                val heldPortrait = if (naturalPortrait) upright else side
                if (if (landscape) heldLandscape else heldPortrait) {
                    stopWatch()
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    private fun stopWatch() {
        watch?.disable()
        watch = null
    }

    private fun autoRotate() = Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
}

/**
 * Over the docked video. Live TV has no PlayerView controls, so a tap shows ours (close, play or
 * pause, full screen) and a double tap goes full screen; movies and episodes keep PlayerView's
 * play and seek controls, with close and full screen added while they show.
 */
@Composable
fun DockedControls(
    live: Boolean,
    visible: Boolean,
    paused: Boolean,
    onTap: () -> Unit,
    onPlayPause: () -> Unit,
    onClose: () -> Unit,
    onFullScreen: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (live) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onDoubleTap = { onFullScreen() }, onTap = { onTap() }) })
        if (visible) {
            SmallRound(Icons.Filled.Close, "Close player", Modifier.align(Alignment.TopStart).padding(8.dp), onClose)
            SmallRound(FullScreenIcon, "Full screen", Modifier.align(Alignment.TopEnd).padding(8.dp), onFullScreen)
            if (live) SmallRound(if (paused) Icons.Filled.PlayArrow else PauseIcon, if (paused) "Play" else "Pause", Modifier.align(Alignment.Center), onPlayPause)
        }
    }
}

@Composable
private fun SmallRound(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, color = Color(0x99000000), shape = CircleShape, modifier = modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = Tv.Text, modifier = Modifier.size(24.dp))
    }
}

// material-icons-core has no pause or full-screen icons; these are the Material paths.
private fun pathIcon(name: String, path: String) =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()

private val PauseIcon = pathIcon("Pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z")
private val FullScreenIcon = pathIcon("FullScreen", "M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z")
val ExitFullScreenIcon = pathIcon("ExitFullScreen", "M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z")
