package app.familytv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import app.familytv.data.CategoryEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w960dp-h540dp-land-television")
class TvFocusTest {
    @get:Rule val rule = createComposeRule()

    private val cats = (1..6).map { CategoryEntity("s", "movie", "$it", "Cat $it", it, 0) }

    /** The TV layout in miniature: rail, then a page of category chips over a poster grid. */
    private fun page() = rule.setContent {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.size(80.dp, 500.dp).focusGroup()) { repeat(4) { FocusBox({}, Modifier.size(48.dp).testTag("rail$it")) {} } }
            Box(Modifier.fillMaxSize().restoresFocus(PageEntry())) {
                Column(Modifier.fillMaxSize()) {
                    PageTitle("Movies")
                    CategoryChips(cats, cats[0]) {}
                    LazyVerticalGrid(GridCells.Adaptive(150.dp)) {
                        items(40) { i ->
                            PosterCard("Movie $i", null, Modifier.testTag("card$i"), width = null, onMenu = {}) {}
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun upFromGridReachesChips() {
        page()
        rule.onNodeWithTag("card0").requestFocus()
        rule.onNodeWithTag("card0").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        rule.onNode(isFocused()).assert(hasText("Cat", substring = true))
    }

    /** Left to the rail from the second row and back again lands on the same card, not the nearest one. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun railAndBackReturnsToTheSameCard() {
        page()
        rule.onNodeWithTag("card0").requestFocus()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        val left = rule.onNode(isFocused()).fetchSemanticsNode().config[SemanticsProperties.TestTag]
        rule.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        rule.onNode(isFocused()).assert(hasTestTag("rail0") or hasTestTag("rail1") or hasTestTag("rail2") or hasTestTag("rail3"))
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        rule.onNodeWithTag(left).assertIsFocused()
    }
}
