package app.familytv.ui

import android.app.Application
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The tab labels' ink ([TabGlide.pillNow]) as a label's draw reads it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TabGlideTest {
    /** Reads [TabGlide.pillNow] as a draw would, and counts the changes that would redraw it. */
    private fun watch(glide: TabGlide, change: suspend () -> Unit): Pair<Pair<Float, Float>?, Int> = runBlocking {
        val reads = mutableSetOf<Any>()
        var before: Pair<Float, Float>? = null
        Snapshot.observe(readObserver = { reads += it }) { before = glide.pillNow() }
        assertNull(before)
        var redraws = 0
        val handle = Snapshot.registerApplyObserver { changed, _ -> if (changed.any { it in reads }) redraws++ }
        try {
            change()
            Snapshot.sendApplyNotifications()
        } finally {
            handle.dispose()
        }
        glide.pillNow() to redraws
    }

    @Test
    fun coldStartLabelDrawnBeforeThePillIsPlacedRedrawsWhenItIs() {
        // At start Home, the chosen tab, is at x 0, where the pill's edges already were: the label drawn
        // before the pill was placed still has to redraw dark on it (round 11: it stayed white on white).
        val glide = TabGlide().apply { chosen = true }
        val (now, redraws) = watch(glide) { glide.pill.glide(0f to 120f, jump = true) }
        assertEquals(0f to 120f, now)
        assertTrue("the label's draw is told the pill is placed", redraws > 0)
    }

    @Test
    fun aLabelDrawnBeforeATabIsChosenRedrawsWhenOneIs() {
        val glide = TabGlide()
        runBlocking { glide.pill.glide(0f to 120f, jump = true) }
        val (now, redraws) = watch(glide) { glide.chosen = true }
        assertEquals(0f to 120f, now)
        assertTrue(redraws > 0)
    }

    @Test
    fun noPillOnSettings() {
        val glide = TabGlide()
        runBlocking { glide.pill.glide(200f to 120f, jump = true) }
        assertNull(glide.pillNow())
    }
}
