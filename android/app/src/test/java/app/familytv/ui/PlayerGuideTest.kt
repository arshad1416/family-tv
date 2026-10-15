package app.familytv.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.familytv.data.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w960dp-h540dp-land-television")
class PlayerGuideTest {
    @get:Rule val rule = createComposeRule()

    private val channels = (1..12).map { ChannelEntity("s", "$it", "Channel $it", 100 + it, null, "c", null, null, 0, 0) }

    /** The guide opens on the playing channel, and Down then OK picks the next one. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun opensOnPlayingChannelAndPicks() {
        var picked = -1
        var window: Rect? = null
        rule.setContent { PlayerGuide(GuideMode.Picture, channels, playing = 5, favKeys = emptySet(), onPick = { picked = it }, onWindow = { window = it }) }
        rule.waitForIdle()
        // The header follows focus: channel 6 (number 106) is the playing one.
        rule.onNodeWithText("106  Channel 6", substring = true).assertExists()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithText("107  Channel 7", substring = true).assertExists()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(6, picked)
        assertNotNull(window)
        assertEquals(16f / 9f, window!!.width / window!!.height, 0.02f)
    }
}
