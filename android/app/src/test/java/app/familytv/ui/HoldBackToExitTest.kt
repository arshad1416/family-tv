package app.familytv.ui

import android.app.Activity
import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HoldBackToExitTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val exit = HoldBackToExit(activity)

    private fun back(action: Int, down: Long, at: Long, repeat: Int = 0) =
        KeyEvent(down, at, action, KeyEvent.KEYCODE_BACK, repeat)

    @Test
    fun shortPressIsLeftForTheBackHandlers() {
        assertFalse(exit.onKey(back(KeyEvent.ACTION_DOWN, 1_000, 1_000)))
        assertFalse(exit.onKey(back(KeyEvent.ACTION_UP, 1_000, 1_150)))
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test
    fun holdingAsksAndSwallowsTheRelease() {
        assertFalse(exit.onKey(back(KeyEvent.ACTION_DOWN, 1_000, 1_000)))
        assertTrue(exit.onKey(back(KeyEvent.ACTION_DOWN, 1_000, 1_400, repeat = 1)))
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(exit.onKey(back(KeyEvent.ACTION_DOWN, 1_000, 1_850, repeat = 2)))
        assertNotNull(ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(exit.onKey(back(KeyEvent.ACTION_UP, 1_000, 2_000)))
        // The next press is an ordinary one again.
        assertFalse(exit.onKey(back(KeyEvent.ACTION_DOWN, 5_000, 5_000)))
        assertFalse(exit.onKey(back(KeyEvent.ACTION_UP, 5_000, 5_100)))
    }
}
