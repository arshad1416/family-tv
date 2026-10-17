package app.familytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.familytv.discovery.PreparationProgress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic responsive layout tests. Native PixelCopy capture is unavailable in this headless runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w960dp-h540dp-land-television")
class SourceChoiceLayoutTest {
    @get:Rule val rule = createComposeRule()
    @Test fun tvSourceCardsKeepIptvInitiallyFocusedAndReleaseReachable() {
        rule.setContent {
            FamilyTvTheme {
                Box(Modifier.fillMaxSize().background(Tv.Bg).padding(16.dp)) {
                    SourceChoiceLayout("Open Film", "Open Film", 2024, null, "Synthetic visual fixture.", Modifier.fillMaxSize()) {
                        SourceChoiceHeader("Open Film", "Fixture IPTV line", {}, {})
                        SourceChoiceCard("Fixture release · 1080p · H.264 · 2.1 GB", {}, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        rule.onNodeWithText("▶  IPTV · Fixture IPTV line").assertIsFocused()
        rule.onNodeWithText("Fixture release · 1080p · H.264 · 2.1 GB").performScrollTo().assertIsDisplayed()

    }
    @Test
    @Config(qualifiers = "w360dp-h740dp-port")
    fun phoneSourceCardsAndCancelStayReachableInCompactStack() {
        rule.setContent {
            FamilyTvTheme {
                CompositionLocalProvider(LocalFormFactor provides FormFactor.Phone) {
                    SourceChoiceLayout("S2 E4 Open Water", "Open Series", 2024, null, null, Modifier.fillMaxSize()) {
                        SourceChoiceHeader("S2 E4 Open Water", "Fixture IPTV line", {}, {})
                        SourceChoiceCard("Fixture release · 1080p · H.264 · 2.1 GB", {}, Modifier.fillMaxWidth())
                        TvButton("Close", {})
                    }
                }
            }
        }
        rule.onNodeWithText("Close").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("▶  IPTV · Fixture IPTV line").performScrollTo().assertIsDisplayed().performClick()

    }
    @Test fun tvPreparationShowsSelectedReleaseAndActualProgressWithBothActions() {
        rule.setContent {
            FamilyTvTheme {
                SourceChoiceLayout("S2 E4 Open Water", "Open Series", 2024, null, null, Modifier.fillMaxSize()) {
                    SourcePreparationCard("S2 E4 Open Water", "Fixture release · 1080p · H.264", PreparationProgress("Downloading on Real-Debrid…", 42.0), {}) {}
                }
            }
        }
        rule.onNodeWithText("42%").assertExists()
        rule.onNodeWithText("Use IPTV instead").assertIsFocused().assertIsEnabled()
        rule.onNodeWithText("Cancel preparation").performScrollTo().assertIsDisplayed()

    }
}
