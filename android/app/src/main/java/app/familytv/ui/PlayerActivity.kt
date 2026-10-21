package app.familytv.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.familytv.sync.TvLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The full-screen player on TV (and on a phone or tablet when the home screen can't host it).
 * [PlayerController] does the work; this gives it a window and the remote's keys.
 */
class PlayerActivity : ComponentActivity(), PlayerHost {
    private val exit = HoldBackToExit(this)
    private lateinit var controller: PlayerController
    private val ready get() = ::controller.isInitialized

    override fun close() = finish()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A TV locked from the portal plays nothing; the home screen behind shows the lock instead.
        if (TvLock.locked) return finish()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller = PlayerController(this, intent, this)
        setContentView(controller.root)
        controller.backCallbacks.forEach { onBackPressedDispatcher.addCallback(this, it) }
        lifecycleScope.launch { TvLock.message.first { it != null }; finish() }
    }

    // androidx marks this restricted, but overriding the platform method is fine.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return exit.onKey(event) || (ready && controller.seekKey(event)) || super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        (ready && controller.onKeyDown(keyCode, event)) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        (ready && controller.onKeyUp(keyCode, event)) || super.onKeyUp(keyCode, event)

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (ready) controller.onUserInteraction()
    }

    override fun onStart() {
        super.onStart()
        if (ready) controller.onStart()
    }

    override fun onStop() {
        super.onStop()
        if (ready) controller.onStop()
    }

    override fun onDestroy() {
        if (ready) controller.release()
        super.onDestroy()
    }
}
