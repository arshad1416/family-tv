package app.familytv.start

import app.familytv.start.OpenOnStart.State
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenOnStartTest {
    @Test fun offUnlessTheProfileTurnsItOn() {
        assertEquals(State.OFF, OpenOnStart.state(enabled = false, canOverlay = true))
        assertEquals(State.OFF, OpenOnStart.state(enabled = false, canOverlay = false))
    }

    @Test fun needsDisplayOverOtherAppsBeforeItCanOpen() {
        assertEquals(State.NEEDS_PERMISSION, OpenOnStart.state(enabled = true, canOverlay = false))
        assertEquals(State.READY, OpenOnStart.state(enabled = true, canOverlay = true))
    }

    @Test fun reportsWhatThePortalReads() {
        assertEquals(listOf("off", "needs-permission", "ready"), State.entries.map { it.report })
    }
}
