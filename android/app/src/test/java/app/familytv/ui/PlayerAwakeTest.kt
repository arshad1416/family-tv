package app.familytv.ui

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class PlayerAwakeTest {
    @Test fun requestedBufferingAndReadyPlaybackKeepScreenAwakeOnlyWhileForeground() {
        for (state in listOf(Player.STATE_BUFFERING, Player.STATE_READY)) {
            assertTrue(shouldKeepPlayerScreenOn(true, true, state, false))
            assertFalse(shouldKeepPlayerScreenOn(false, true, state, false))
            assertFalse(shouldKeepPlayerScreenOn(true, false, state, false))
            assertFalse(shouldKeepPlayerScreenOn(true, true, state, true))
        }
    }
    @Test fun idleEndedAndFailedPlaybackAllowScreenToSleep() {
        for (state in listOf(Player.STATE_IDLE, Player.STATE_ENDED)) {
            assertFalse(shouldKeepPlayerScreenOn(true, true, state, false))
        }
    }
}
