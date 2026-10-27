package app.familytv.ui

import app.familytv.ui.FadeGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** The carousel's fade gate: verdicts from several samples, never one bad moment (round 5, the Kitchen). */
class FadeGateTest {
    @Test
    fun oneBusyFadeBesideCleanOnesIsDropped() {
        val g = FadeGate()
        // The Kitchen 8-24 s after the screensaver: 38/42, 0/42, 42/42.
        g.add(42, 38); g.add(42, 0); g.add(42, 42)
        assertEquals(Verdict.UNKNOWN, g.verdict)
        g.add(42, 0); g.add(42, 1); g.add(42, 0)
        assertEquals(Verdict.OK, g.verdict)
    }

    @Test
    fun aSlowBoxCutsOnceFourAgree() {
        val g = FadeGate()
        // The onn: 5-40% of frames late on every fade.
        listOf(6, 17, 5, 12).forEach { g.add(42, it) }
        assertEquals(Verdict.SLOW, g.verdict)
    }

    @Test
    fun threeFullyLateFadesInARowCount() {
        val g = FadeGate()
        repeat(2) { g.add(42, 42) }
        assertEquals(Verdict.UNKNOWN, g.verdict)
        repeat(4) { g.add(42, 41) }
        assertEquals(Verdict.SLOW, g.verdict)
    }

    @Test
    fun mixedSamplesWaitForAgreement() {
        val g = FadeGate()
        listOf(0, 10, 0, 10, 0).forEach { g.add(42, it) }
        assertEquals(Verdict.UNKNOWN, g.verdict)
        g.add(42, 0)
        assertEquals(Verdict.OK, g.verdict)
        assertEquals("too few frames, dropped", g.add(3, 0))
    }
}
