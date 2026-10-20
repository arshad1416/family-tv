package app.familytv.ui

import app.familytv.ui.SleepTimer.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepTimerTest {
    private val now = 1_000_000L

    @Test
    fun lengthsCountFromNow() {
        assertEquals(now + 15 * 60_000L, SleepTimer.at(Choice.M15, now, null))
        assertEquals(now + 90 * 60_000L, SleepTimer.at(Choice.M90, now, now + 5))
    }

    @Test
    fun endOfProgrammeNeedsAnEndAhead() {
        assertEquals(now + 600_000, SleepTimer.at(Choice.END, now, now + 600_000))
        assertNull(SleepTimer.at(Choice.END, now, null))
        assertNull(SleepTimer.at(Choice.END, now, now - 1))
    }

    @Test
    fun aVideoEndsWhatsLeftFromNow() {
        assertEquals(now + 40_000, SleepTimer.videoEnd(now, positionMs = 60_000, durationMs = 100_000))
        assertEquals(now, SleepTimer.videoEnd(now, positionMs = 120_000, durationMs = 100_000))
        assertNull(SleepTimer.videoEnd(now, positionMs = 0, durationMs = 0))
    }

    @Test
    fun seekStepsGrowWhileHeld() {
        assertEquals(10_000L, SeekSteps.stepMs(0))
        assertEquals(10_000L, SeekSteps.stepMs(5))
        assertEquals(30_000L, SeekSteps.stepMs(6))
        assertEquals(60_000L, SeekSteps.stepMs(16))
    }
}
