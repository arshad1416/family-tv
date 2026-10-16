package app.familytv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.familytv.discovery.PreparationProgress
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w960dp-h540dp-land-television")
class SourceChoiceUiTest {
    @get:Rule val rule = createComposeRule()
    @OptIn(ExperimentalTestApi::class)
    @Test fun episodeNameAndManualChoicesRemainUsableWhilePreparing() {
        var iptv = 0
        var releases = 0
        rule.setContent {
            FamilyTvTheme {
                Column {
                    SourceChoiceHeader("S1 E4 Open Water", "Original line", { iptv++ }, { releases++ })
                    PreparationProgressView(PreparationProgress("Downloading on Real-Debrid…", 25.0))
                }
            }
        }
        rule.onNodeWithText("Play S1 E4 Open Water from…").assertExists()
        rule.onNodeWithText("▶  IPTV · Original line").assertIsFocused().assertIsEnabled()
        rule.onNodeWithText("▶  IPTV · Original line").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithText("Real-Debrid / Torrentio · Choose a release").assertIsFocused().performClick()
        assertEquals(1, releases)
        assertEquals(0, iptv)
        rule.onNodeWithText("▶  IPTV · Original line").performClick()
        assertEquals(1, iptv)
    }
    @Test fun missingPercentageShowsOnlyIndeterminateStage() {
        rule.setContent { FamilyTvTheme { PreparationProgressView(PreparationProgress("Queued on Real-Debrid…")) } }
        rule.onNodeWithText("Queued on Real-Debrid…").assertExists()
        rule.onAllNodes(hasText("%", substring = true)).assertCountEquals(0)
    }
}
