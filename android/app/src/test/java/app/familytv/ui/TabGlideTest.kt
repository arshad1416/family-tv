package app.familytv.ui

import android.app.Application
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
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
        val ink = IconInk(chosen = false, light = false)
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
        val ink = IconInk(chosen = true, light = false)
        ink.update(this, chosen = false, clear = true)
        runCurrent()
        assertEquals(1f, ink.cover)
        advanceUntilIdle()
        assertFalse(ink.shown)
        assertUntinted(ink, onPill = false)
    }

    @Test
    fun anIconNeverChosenIsNeverTinted() = inkTest {
        val ink = IconInk(chosen = false, light = false)
        repeat(5) { ink.update(this, chosen = false, clear = it % 2 == 0) }
        advanceUntilIdle()
        assertUntinted(ink, onPill = false)
    }

    @Test
    fun aNewChangeMidFadeEndsUntintedToo() = inkTest {
        val ink = IconInk(chosen = false, light = false)
        ink.update(this, chosen = true, clear = true)
        advanceTimeBy(300)
        ink.update(this, chosen = false, clear = true)
        assertEquals(1f, ink.cover)
        advanceUntilIdle()
        assertUntinted(ink, onPill = false)
    }

    @Test
    fun aChosenIconAtRestIsClearSoItIsToldItIsSelected() {
        // The onn's chosen Home tab at rest: a 40-high pill from x 0, the icon's 36 view overhanging its 22 box from
        // x 11 and y 2, so the glow's corners poke past the pill's round end. Round 13: judged by the view, it was never
        // clear, so Rive never got "selected" (no glow or dot) and the tint stayed.
        val pill = RoundRect(0f, 0f, 120f, 40f, CornerRadius(20f))
        val view = Rect(Offset(11f, 2f), Size(36f, 36f))
        assertFalse(pill.contains(view.topLeft))
        val core = iconCore(Size(36f, 36f)).translate(view.topLeft)
        assertTrue(pillClear(pill, core, chosen = true))
        assertFalse(pillClear(pill, core, chosen = false))
        // Mid-glide, the pill's edge on the icon: not clear either way.
        val crossing = RoundRect(25f, 0f, 145f, 40f, CornerRadius(20f))
        assertFalse(pillClear(crossing, core, chosen = true))
        assertFalse(pillClear(crossing, core, chosen = false))
        // The next tab's icon, 8 past the pill, and no pill at all (Settings): clear when not chosen.
        val next = iconCore(Size(36f, 36f)).translate(Offset(139f, 2f))
        assertTrue(pillClear(pill, next, chosen = false))
        assertTrue(pillClear(null, core, chosen = false))
        assertFalse(pillClear(null, core, chosen = true))
    }

    @Test
    fun aCrossedIconIsWholeInTheInkOfItsCentresSide() {
        // Round 14: split through a clip, the onn lost the Home icon for a frame as the pill left it. Now it is drawn
        // whole: the pill's ink while its centre is on the pill, the bar's once it isn't.
        val core = iconCore(Size(36f, 36f)).translate(Offset(11f, 2f))
        val atRest = RoundRect(0f, 0f, 120f, 40f, CornerRadius(20f))
        assertTrue(iconOnPill(atRest, core))
        assertTrue(iconOnPill(RoundRect(25f, 0f, 145f, 40f, CornerRadius(20f)), core))
        assertFalse(iconOnPill(RoundRect(31f, 0f, 151f, 40f, CornerRadius(20f)), core))
        assertFalse(iconOnPill(RoundRect(147f, 0f, 267f, 40f, CornerRadius(20f)), core))
        assertFalse(iconOnPill(null, core))
    }

    @Test
    fun aLookChangeTintsTheIconAndEndsUntinted() = inkTest {
        // Into or out of Home by day, or into Settings: the bar's look flips; the icon's own ink takes 180 ms.
        val ink = IconInk(chosen = true, light = false)
        ink.look(this, light = true)
        assertEquals(1f, ink.cover)
        ink.look(this, light = true)
        advanceUntilIdle()
        assertTrue(ink.shown)
        assertUntinted(ink, onPill = true)
    }

    @Test
    fun theBarTakesTheDarkLookWithHomesArtNotTheTab() {
        // Both looks, whatever the hour: over the art the bar is dark, off it the page's own look.
        for (page in listOf(GlassPalette.Light, GlassPalette.Dark)) {
            assertEquals(page, barPalette(0f, page))
            assertEquals(GlassPalette.Dark, barPalette(1f, page))
        }
    }

    @Test
    fun noPillOnSettings() {
        val glide = TabGlide()
        runBlocking { glide.pill.glide(200f to 120f, jump = true) }
        assertNull(glide.pillNow())
    }
}
