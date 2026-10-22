package app.familytv.ui

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Hold-OK on a poster opens its [ItemMenu], and the held press must not pick from it or close it. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w960dp-h540dp-land-television")
class ItemMenuTest {
    @get:Rule val rule = createComposeRule()

    private val menus = ItemMenus()
    private var played = 0

    private fun poster() {
        rule.setContent {
            CompositionLocalProvider(LocalItemMenus provides menus) {
                FocusBox({}, Modifier.size(120.dp).testTag("poster"), onLongClick = { menus.open = ItemMenu("Star Wars", listOf("Play" to { played++ }, "Mark as watched" to {})) }) {}
                menus.Host()
            }
        }
        rule.onNodeWithTag("poster").requestFocus()
        rule.waitForIdle()
    }

    private fun ok(action: Int, at: Long, repeat: Int = 0) = KeyEvent(android.view.KeyEvent(0, at, action, KEYCODE_DPAD_CENTER, repeat))

    /** Where the remote's keys go: the menu's focused option once the menu is open, else the poster. */
    private fun focused(): SemanticsNodeInteraction =
        if (menus.open != null) rule.onNode(isFocused() and hasText("Play")) else rule.onNodeWithTag("poster")

    @Test
    fun holdingOkForASecondAndAHalfLeavesTheMenuOpen() {
        poster()
        focused().performKeyPress(ok(ACTION_DOWN, 0))
        // The remote repeats a held OK about every 50 ms, then sends its release.
        for (i in 1..30) {
            rule.mainClock.advanceTimeBy(50)
            rule.waitForIdle()
            focused().performKeyPress(ok(ACTION_DOWN, i * 50L, repeat = i))
        }
        rule.waitForIdle()
        focused().performKeyPress(ok(ACTION_UP, 1_550))
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        rule.onNodeWithText("Star Wars").assertExists()
        assertEquals(0, played)
        // A fresh press picks the focused option.
        focused().performKeyPress(ok(ACTION_DOWN, 3_000))
        focused().performKeyPress(ok(ACTION_UP, 3_100))
        rule.waitForIdle()
        assertEquals(1, played)
        rule.onNodeWithText("Star Wars").assertDoesNotExist()
    }

    @Test
    fun leftoverOkEventsAreEatenUntilAFreshPress() {
        val fresh = booleanArrayOf(false)
        fun native(action: Int, repeat: Int = 0) = android.view.KeyEvent(0, 0, action, KEYCODE_DPAD_CENTER, repeat)
        assertEquals(true, heldOk(native(ACTION_DOWN, repeat = 5), fresh))
        assertEquals(true, heldOk(native(ACTION_UP), fresh))
        assertEquals(false, heldOk(android.view.KeyEvent(ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN), fresh))
        assertEquals(false, heldOk(native(ACTION_DOWN), fresh))
        assertEquals(false, heldOk(native(ACTION_UP), fresh))
        assertEquals(false, heldOk(native(ACTION_DOWN, repeat = 3), fresh))
    }
}
