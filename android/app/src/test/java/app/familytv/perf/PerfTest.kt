package app.familytv.perf

import androidx.test.core.app.ApplicationProvider
import app.familytv.sync.PlaybackStatus
import org.junit.Assert.assertEquals
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
