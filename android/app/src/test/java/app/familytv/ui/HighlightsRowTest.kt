package app.familytv.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Round 22: as Movies' first row, the highlights' focused cards had their tops cut off by the list's edge. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w960dp-h540dp-land-television")
class HighlightsRowTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun aFocusedCardsZoomFitsInsideTheList() {
        rule.setContent {
            LazyColumn(Modifier.size(900.dp, 500.dp).testTag("list")) {
                item {
                    HighlightsRow {
                        HeroCard("A film", "Movie", null, HERO_MAIN, 2.2f, Modifier.testTag("main")) {}
                        HeroCard("Recently added", null, null, HERO_MAIN, 1f, Modifier.testTag("recent")) {}
                        HeroCard("Top rated", null, null, HERO_MAIN, 1f, Modifier.testTag("top")) {}
                    }
                }
            }
        }
        val list = rule.onNodeWithTag("list").getBoundsInRoot()
        for (tag in listOf("main", "recent", "top")) {
            val card = rule.onNodeWithTag(tag).getBoundsInRoot()
            // The zoom is about the card's centre.
            val zoomedTop = card.top - card.height * (HERO_SCALE - 1) / 2
            assertTrue("$tag's top $zoomedTop when focused is above the list's ${list.top}", zoomedTop >= list.top)
        }
    }
}
