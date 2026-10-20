package app.familytv.ui

import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
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
import org.junit.After
import org.junit.Before
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

    private val profile = Profile(parentalPin = "9134")
    private var opened = 0

    private fun show(form: FormFactor = FormFactor.Tv) = rule.setContent {
        CompositionLocalProvider(LocalFormFactor provides form) { PinPrompt(profile) { opened++ } }
    }

    private val row get() = rule.onNode(hasContentDescription("PIN", substring = true))

    private fun press(keys: List<Key>) {
        row.performKeyInput { keys.forEach { pressKey(it) } }
        rule.waitForIdle()
    }

    private fun entered(n: Int) = row.assertContentDescriptionEquals("PIN, $n of 4 digits entered")

    @Before fun unlock() = PinLock.reset()

    @After fun clock() { PinLock.now = SystemClock::elapsedRealtime }

    @Test
    fun theWheelEntersARowsNumbersWithoutShowingWhichOne() {
        show()
        row.requestFocus()
        rule.waitForIdle()
        // No keypad to click, and no digit in the accessibility text.
        assertEquals(0, rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        for (d in 0..9) assertEquals(0, rule.onAllNodesWithText("$d").fetchSemanticsNodes().size)
        // Up from [1 2 3] wraps to [0], where Left and Right do nothing and OK enters 0.
        press(listOf(Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight))
        entered(0)
        press(listOf(Key.DirectionCenter))
        entered(1)
        press(listOf(Key.Back))
        entered(0)
        // The PIN is 9134 here: [7 8 9] Right, then (the row back at [1 2 3]) Left, Right, and [4 5 6] Left.
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionRight))
        press(listOf(Key.DirectionLeft, Key.DirectionRight))
        entered(3)
        assertEquals(0, opened)
        press(listOf(Key.DirectionDown, Key.DirectionLeft))
        assertEquals(1, opened)
    }

    @Test
    fun okEntersTheMiddleNumberOfTheRowAndAWrongPinShakesAndClears() {
        show()
        row.requestFocus()
        // 2, 5, 8 and 0: OK on each row in turn.
        press(listOf(Key.DirectionCenter, Key.DirectionDown, Key.DirectionCenter, Key.DirectionDown, Key.DirectionDown, Key.DirectionCenter, Key.DirectionUp, Key.DirectionCenter))
        rule.onNodeWithText("Wrong PIN").assertExists()
        entered(0)
        assertEquals(0, opened)
    }

    @Test
    fun numberKeysFillSlotsAndAWrongPinStartsOver() {
        show()
        row.requestFocus()
        press(listOf(Key.Nine, Key.Eight))
        entered(2)
        // Back takes the last digit away.
        press(listOf(Key.Back))
        entered(1)
        press(listOf(Key.Eight, Key.Seven, Key.Six))
        rule.onNodeWithText("Wrong PIN").assertExists()
        entered(0)
        assertEquals(0, opened)
        press(listOf(Key.Nine, Key.One, Key.Three, Key.Four))
        assertEquals(1, opened)
    }

    @Test
    fun fiveWrongTriesLockEntryForThirtySeconds() {
        var t = 1_000_000L
        PinLock.now = { t }
        show()
        row.requestFocus()
        repeat(PinLock.TRIES) { press(listOf(Key.One, Key.One, Key.One, Key.One)) }
        rule.onNodeWithText("Too many tries", substring = true).assertExists()
        press(listOf(Key.Nine, Key.One, Key.Three, Key.Four))
        assertEquals(0, opened)
        entered(0)
        t += PinLock.LOCK_MS
        press(listOf(Key.Nine, Key.One, Key.Three, Key.Four))
        assertEquals(1, opened)
    }

    @Test
    fun aTouchScreenTypesIntoAHiddenField() {
        show(FormFactor.Phone)
        rule.onNode(hasContentDescription("PIN")).performTextInput("1111")
        rule.waitForIdle()
        rule.onNodeWithText("Wrong PIN").assertExists()
        rule.onNode(hasContentDescription("PIN")).performTextInput("9134")
        rule.waitForIdle()
        assertEquals(1, opened)
    }
}
