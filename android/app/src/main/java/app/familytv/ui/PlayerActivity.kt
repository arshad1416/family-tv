package app.familytv.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity

/**
 * The full-screen player on TV (and on a phone or tablet when the home screen can't host it).
 * [PlayerController] does the work; this gives it a window and the remote's keys.
 */
class PlayerActivity : ComponentActivity(), PlayerHost {
    private val exit = HoldBackToExit(this)
    private lateinit var controller: PlayerController

    override fun close() = finish()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller = PlayerController(this, intent, this)
        setContentView(controller.root)
        controller.backCallbacks.forEach { onBackPressedDispatcher.addCallback(this, it) }
    }

    // androidx marks this restricted, but overriding the platform method is fine.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return exit.onKey(event) || super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        controller.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        controller.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)

    override fun onUserInteraction() {
        super.onUserInteraction()
        controller.onUserInteraction()
    }

    override fun onStart() {
        super.onStart()
        controller.onStart()
    }

    override fun onStop() {
        super.onStop()
        controller.onStop()
    }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }
}
