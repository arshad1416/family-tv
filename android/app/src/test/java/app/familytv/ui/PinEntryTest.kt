package app.familytv.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.familytv.portal.Profile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w960dp-h540dp-land-television")
class PinEntryTest {
    @get:Rule val rule = createComposeRule()

    private val profile = Profile(parentalPin = "1234")
    private var opened = 0

    private fun show(form: FormFactor = FormFactor.Tv) = rule.setContent {
        CompositionLocalProvider(LocalFormFactor provides form) { PinPrompt(profile) { opened++ } }
    }

    private val row get() = rule.onNode(hasContentDescription("PIN", substring = true))

    private fun press(keys: List<Key>) {
        row.performKeyInput { keys.forEach { pressKey(it) } }
        rule.waitForIdle()
    }

    @Test
    fun upAndDownRollTheSlotAndOnlyItShowsItsDigit() {
        show()
        row.requestFocus()
        rule.waitForIdle()
        // No keypad: nothing to click, and the focused slot starts at 0.
        assertEquals(0, rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        rule.onNodeWithText("0").assertExists()
        press(listOf(Key.DirectionDown))
        rule.onNodeWithText("9").assertExists()
        press(listOf(Key.DirectionUp, Key.DirectionUp))
        rule.onNodeWithText("1").assertExists()
        // Right makes it a dot; the next slot starts again at 0.
        press(listOf(Key.DirectionRight))
        rule.onNodeWithText("1").assertDoesNotExist()
        rule.onNodeWithText("•").assertExists()
        rule.onNodeWithText("0").assertExists()
        // Left steps back and clears it.
        press(listOf(Key.DirectionLeft))
        assertEquals(0, rule.onAllNodesWithText("•").fetchSemanticsNodes().size)
        // 1, 2, 3, 4 by rolling; OK on the last slot checks it.
        press(listOf(Key.DirectionUp, Key.DirectionRight, Key.DirectionUp, Key.DirectionUp, Key.DirectionCenter))
        press(listOf(Key.DirectionUp, Key.DirectionUp, Key.DirectionUp, Key.DirectionRight))
        assertEquals(3, rule.onAllNodesWithText("•").fetchSemanticsNodes().size)
        press(listOf(Key.DirectionUp, Key.DirectionUp, Key.DirectionUp, Key.DirectionUp, Key.DirectionCenter))
        assertEquals(1, opened)
    }

    @Test
    fun numberKeysShowOnlyDotsAndAWrongPinStartsOver() {
        show()
        row.requestFocus()
        press(listOf(Key.Nine, Key.Eight))
        rule.onNodeWithText("9").assertDoesNotExist()
        rule.onNodeWithText("8").assertDoesNotExist()
        assertEquals(2, rule.onAllNodesWithText("•").fetchSemanticsNodes().size)
        press(listOf(Key.Seven, Key.Six))
        rule.onNodeWithText("Wrong PIN").assertExists()
        assertEquals(0, rule.onAllNodesWithText("•").fetchSemanticsNodes().size)
        assertEquals(0, opened)
        press(listOf(Key.One, Key.Two, Key.Three, Key.Four))
        assertEquals(1, opened)
    }

    @Test
    fun aTouchScreenTypesIntoAHiddenField() {
        show(FormFactor.Phone)
        rule.onNode(hasContentDescription("PIN")).performTextInput("1111")
        rule.waitForIdle()
        rule.onNodeWithText("Wrong PIN").assertExists()
        rule.onNode(hasContentDescription("PIN")).performTextInput("1234")
        rule.waitForIdle()
        assertEquals(1, opened)
    }
}
