package app.familytv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.PathParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The still tab icons stand in for Rive on TVs: every tab has its art, and the ink matches the scene's. */
class TabArtTest {
    @Test
    fun everyTabHasArtInsideTheArtboard() {
        for (tab in GlassTab.entries) {
            val marks = TabArt.marks[tab.label]
            assertTrue("${tab.label} has no art", !marks.isNullOrEmpty())
            for (m in marks!!) {
                val nodes = PathParser().parsePathString(m.d).toNodes()
                assertTrue(nodes.isNotEmpty())
                // Centred on an 80-unit artboard, so every point stays within 40 of the middle.
                Regex("-?\\d+(\\.\\d+)?").findAll(m.d).forEach { assertTrue(m.d, kotlin.math.abs(it.value.toFloat()) <= TabArt.SIZE / 2) }
            }
        }
    }

    @Test
    fun inkIsDarkOnlyWhereTheGroundIsLight() {
        val dark = Color(0xFF0B0F18)
        assertEquals(Color.White, TabArt.ink(selected = false, light = false))
        assertEquals(dark, TabArt.ink(selected = true, light = false))
        assertEquals(dark, TabArt.ink(selected = false, light = true))
        assertEquals(Color.White, TabArt.ink(selected = true, light = true))
    }
}
