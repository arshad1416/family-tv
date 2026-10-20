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

    @Test fun slowFrameShareIsOverTheLastTenMinutes() {
        val w = FrameWindow()
        repeat(90) { w.add(t0, isSlow = it < 9) }
        assertEquals(10.0, w.pct(t0)!!, 0.0)
        repeat(30) { w.add(t0 + 5 * minute, isSlow = true) }
        assertEquals("39 of 120", 32.5, w.pct(t0 + 5 * minute)!!, 0.0)
        assertNull("the first minute has aged out, leaving too few", w.pct(t0 + 10 * minute))
    }

    @Test fun tooFewFramesOrNoneSaysNothing() {
        val w = FrameWindow()
        assertNull(w.pct(t0))
        repeat(FrameWindow.MIN_FRAMES - 1) { w.add(t0, isSlow = true) }
        assertNull(w.pct(t0))
        repeat(FrameWindow.MIN_FRAMES) { w.add(t0, isSlow = false) }
        assertNull("all older than ten minutes", w.pct(t0 + 10 * minute))
    }

    @Test fun theHeartbeatCarriesNumbersOnly() {
        Perf.init(ApplicationProvider.getApplicationContext())
        Perf.listLoadMs = 4_200
        @Suppress("UNCHECKED_CAST")
        val perf = PlaybackStatus.snapshot()["perf"] as Map<String, Any?>
        assertEquals(setOf("coldStartMs", "pssMb", "heapMb", "pssPeakMb", "listLoadMs", "jankPct", "deviceRamMb", "model", "sdk"), perf.keys)
        assertEquals(4_200L, perf["listLoadMs"])
        assertEquals(34, perf["sdk"])
        assertTrue(perf.filterKeys { it != "model" }.values.all { it == null || it is Number })
        assertTrue((perf["pssPeakMb"] as Int) >= (perf["pssMb"] as Int))
    }
}
