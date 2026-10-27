package app.familytv.ui

import android.app.Application
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun rivePartsWithTheWrongInkAreTintedAll() {
        // The icon still shows the old tab's ink while the pill glides: on the pill it needs the dark ink.
        assertEquals(1f, iconTint(inside = true, shown = false, cover = 0f))
        assertEquals(1f, iconTint(inside = false, shown = true, cover = 0f))
        // Its own ink is right there: as it is, unless it is still changing under the tint.
        assertEquals(0f, iconTint(inside = true, shown = true, cover = 0f))
        assertEquals(0.4f, iconTint(inside = false, shown = false, cover = 0.4f))
    }

    /** A 60 Hz frame clock on the test's virtual time, for the tint's fade. */
    private object Frames : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            delay(16)
            return onFrame(System.nanoTime())
        }
    }

    private fun inkTest(block: suspend TestScope.() -> Unit) = runTest(Frames) { block() }

    /** The icon is drawn as it is: no tint on or off the pill. */
    private fun assertUntinted(ink: IconInk, onPill: Boolean) {
        assertEquals(0f, ink.cover)
        assertEquals(0f, iconTint(inside = onPill, shown = ink.shown, cover = ink.cover))
    }

    @Test
    fun aChosenIconIsUntintedOnceSettled() = inkTest {
        val ink = IconInk(chosen = false)
        // Mid-glide the pill crosses its edges: its ink stays put, the tint does the split.
        ink.update(this, chosen = true, clear = false)
        assertFalse(ink.shown)
        ink.update(this, chosen = true, clear = true)
        assertTrue(ink.shown)
        assertEquals(1f, ink.cover)
        // Round 12: the change seen again (the pill settling, a stray emission) must not stop the fade.
        advanceTimeBy(100)
        ink.update(this, chosen = true, clear = false)
        ink.update(this, chosen = true, clear = true)
        advanceUntilIdle()
        assertUntinted(ink, onPill = true)
    }

    @Test
    fun anIconLeftIsUntintedOnceThePillHasGone() = inkTest {
        val ink = IconInk(chosen = true)
        ink.update(this, chosen = false, clear = true)
        runCurrent()
        assertEquals(1f, ink.cover)
        advanceUntilIdle()
        assertFalse(ink.shown)
        assertUntinted(ink, onPill = false)
    }

    @Test
    fun anIconNeverChosenIsNeverTinted() = inkTest {
        val ink = IconInk(chosen = false)
        repeat(5) { ink.update(this, chosen = false, clear = it % 2 == 0) }
        advanceUntilIdle()
        assertUntinted(ink, onPill = false)
    }

    @Test
    fun aNewChangeMidFadeEndsUntintedToo() = inkTest {
        val ink = IconInk(chosen = false)
        ink.update(this, chosen = true, clear = true)
        advanceTimeBy(300)
        ink.update(this, chosen = false, clear = true)
        assertEquals(1f, ink.cover)
        advanceUntilIdle()
        assertUntinted(ink, onPill = false)
    }

    @Test
    fun noPillOnSettings() {
        val glide = TabGlide()
        runBlocking { glide.pill.glide(200f to 120f, jump = true) }
        assertNull(glide.pillNow())
    }
}
