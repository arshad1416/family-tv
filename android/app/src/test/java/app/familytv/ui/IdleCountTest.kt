package app.familytv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ticket 2: Back from a long film opened the screensaver because Home kept counting idle time behind the player. */
class IdleCountTest {
    private val fiveMinutes = 5 * 60_000L

    @Test fun idleDoesNotCountWhileThePlayerIsInFront() {
        val idle = IdleCount()
        repeat(40) { assertFalse(idle.step(inFront = false, afterMs = fiveMinutes)) } // 10 minutes in the player
        assertEquals(0L, idle.ms)
    }

    @Test fun comingBackFromThePlayerStartsIdleAgain() {
        val idle = IdleCount()
        repeat(10) { idle.step(inFront = true, afterMs = fiveMinutes) } // 2.5 minutes on Home before playing
        idle.backInFront()
        assertFalse(idle.step(inFront = true, afterMs = fiveMinutes))
        assertEquals(IDLE_STEP_MS, idle.ms)
    }

    @Test fun saverStillStartsAfterFiveIdleMinutesOnHome() {
        val idle = IdleCount()
        val steps = (fiveMinutes / IDLE_STEP_MS).toInt()
        repeat(steps - 1) { assertFalse(idle.step(inFront = true, afterMs = fiveMinutes)) }
        assertTrue(idle.step(inFront = true, afterMs = fiveMinutes))
    }

    @Test fun saverOffNeverStarts() {
        val idle = IdleCount()
        repeat(100) { assertFalse(idle.step(inFront = true, afterMs = 0)) }
    }
}
