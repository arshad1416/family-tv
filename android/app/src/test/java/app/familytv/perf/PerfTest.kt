package app.familytv.perf

import androidx.test.core.app.ApplicationProvider
import app.familytv.sync.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PerfTest {
    private val minute = 60_000L
    private val t0 = 1_000_000 * minute

    @Test fun slowFrameShareIsOverTheLastTenMinutesThatHadBrowsing() {
        val w = FrameWindow()
        assertNull("nothing browsed yet", w.pct())
        repeat(90) { w.add(t0, isSlow = it < 9) }
        assertEquals(10.0, w.pct()!!, 0.0)
        // An hour of watching later, the old minute still counts: the window is browsing minutes, not the clock.
        repeat(30) { w.add(t0 + 60 * minute, isSlow = true) }
        assertEquals("39 of 120", 32.5, w.pct()!!, 0.0)
        for (i in 1..30) w.add(t0 + (70 + i) * minute, isSlow = false)
        assertNull("the last ten browsing minutes hold too few frames", w.pct())
    }

    @Test fun aSlowFrameMissedARefresh() {
        // A frame still on time can take over one refresh while the one before it is shown: only over two is it late.
        assertEquals(33_333_333L, Perf.slowFrameNs(60f))
        assertEquals(40_000_000L, Perf.slowFrameNs(50f))
    }

    // Round 19, the onn on Android 14: frames took 42 ms at the median with their queue, so the two-refresh rule read 83%
    // against gfxinfo's 23.5% deadline missed. From Android 12 each frame's own deadline decides.
    @Test fun fromAndroid12AFrameIsSlowOnlyPastItsDeadline() {
        val vsync = 5_000_000_000_000L
        assertFalse(Perf.missedDeadline(total = 42_000_000, deadline = 50_000_000, intendedVsync = vsync, fallbackNs = 33_333_333))
        assertTrue(Perf.missedDeadline(total = 51_000_000, deadline = 50_000_000, intendedVsync = vsync, fallbackNs = 33_333_333))
        // A deadline given as the moment itself.
        assertFalse(Perf.missedDeadline(total = 42_000_000, deadline = vsync + 50_000_000, intendedVsync = vsync, fallbackNs = 33_333_333))
        // No sensible deadline: the two-refresh rule.
        assertTrue(Perf.missedDeadline(total = 42_000_000, deadline = 0, intendedVsync = vsync, fallbackNs = 33_333_333))
    }

    @Test fun tooFewFramesSaysNothing() {
        val w = FrameWindow()
        repeat(FrameWindow.MIN_FRAMES - 1) { w.add(t0, isSlow = true) }
        assertNull(w.pct())
        w.add(t0, isSlow = false)
        assertEquals(98.3, w.pct()!!, 0.0)
    }

    @Test fun theHeartbeatCarriesNumbersOnly() {
        Perf.init(ApplicationProvider.getApplicationContext())
        Perf.listLoadMs = 4_200
        @Suppress("UNCHECKED_CAST")
        val perf = PlaybackStatus.snapshot()["perf"] as Map<String, Any?>
        // Unknown values are left out: no cold start or browsing yet.
        assertEquals(setOf("pssMb", "heapMb", "pssPeakMb", "listLoadMs", "deviceRamMb", "model", "sdk"), perf.keys)
        assertEquals(4_200L, perf["listLoadMs"])
        assertEquals(34, perf["sdk"])
        assertTrue(perf.filterKeys { it != "model" }.values.all { it is Number })
        assertTrue((perf["pssPeakMb"] as Long) >= (perf["pssMb"] as Long))
    }
}
