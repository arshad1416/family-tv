package app.familytv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.familytv.data.CategoryEntity
import app.familytv.data.ChannelEntity
import app.familytv.data.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Back's category list: focus starts on the shown category, and OK on another shows its channels from the top. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun categoriesSwitchTheGrid() {
        fun cat(id: String) = CategoryEntity("s", Kind.LIVE, id, id, 0, 0)
        val cats = listOf(favouritesCategory(Kind.LIVE), cat("News"), cat("Sports"))
        val sports = (1..4).map { ChannelEntity("s", "sp$it", "Sports $it", 200 + it, null, "Sports", null, null, 0, 0) }
        var shown by mutableStateOf(cats[1])
        var open by mutableStateOf(true)
        var picked = -1
        rule.setContent {
            val news = shown == cats[1]
            PlayerGuide(
                GuideMode.Picture, if (news) channels else sports, if (news) 5 else -1, emptySet(), onPick = { picked = it }, onWindow = {},
                categories = GuideCategories(cats, shown, open, onPick = { shown = it; open = false }, onLeave = { open = false }),
            )
        }
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(cats[2], shown)
        assertFalse(open)
        rule.onNodeWithText("201  Sports 1", substring = true).assertExists()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(0, picked)
    }
}
