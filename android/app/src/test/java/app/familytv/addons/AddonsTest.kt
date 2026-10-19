package app.familytv.addons

import app.familytv.addons.Addons.State
import app.familytv.portal.AddonInfo
import app.familytv.portal.DeviceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonsTest {
    private val now = 1_000_000_000_000L
    private val known = setOf("multiview", "recorder")
    private fun config(vararg a: AddonInfo) = DeviceConfig(paired = true, addons = a.toList())
    private fun active(id: String, c: DeviceConfig?, at: Long = now) = Addons.isActive(id, c, at, now, known)

    @Test fun onlyAnActiveAddOnThisAppKnowsTurnsOn() {
        val c = config(
            AddonInfo("multiview", "Multiview", "active"),
            AddonInfo("recorder", "Recorder", "paused"),
            AddonInfo("karaoke", "Karaoke", "active"),
        )
        assertTrue(active("multiview", c))
        assertFalse("paused", active("recorder", c))
        assertFalse("unknown to this app", active("karaoke", c))
        assertFalse("not sent", active("trivia", c))
    }

    @Test fun pausedAvailableRevokedOrOddStatesNeverTurnOn() {
        for (state in listOf("paused", "available", "not_offered", "revoked", "refunded", "owned", "ACTIVE", null)) {
            assertFalse(state ?: "null", active("multiview", config(AddonInfo("multiview", "Multiview", state))))
        }
    }

    @Test fun aConfigWithoutAddOnsHasNoneActive() {
        assertFalse(active("multiview", DeviceConfig(paired = true)))
        assertFalse(active("multiview", null))
        assertTrue(Addons.list(DeviceConfig(paired = true), now, now, known).isEmpty())
    }

    @Test fun ownedAddOnsPauseAfterSevenDaysWithoutThePortal() {
        val c = config(AddonInfo("multiview", "Multiview", "active"), AddonInfo("recorder", "Recorder", "available"))
        assertTrue(active("multiview", c, at = now - Addons.OFFLINE_MS))
        assertFalse(active("multiview", c, at = now - Addons.OFFLINE_MS - 1))
        assertFalse("never fetched", active("multiview", c, at = 0))
        val list = Addons.list(c, now - Addons.OFFLINE_MS - 1, now, known)
        assertEquals(listOf(State.PAUSED, State.AVAILABLE), list.map { it.state })
        assertEquals("Paused: can't reach the portal", list[0].line)
    }

    @Test fun settingsSaysPlainlyWhereEachAddOnStands() {
        val c = config(
            AddonInfo("multiview", "Multiview", "active"),
            AddonInfo("recorder", " ", "paused"),
            AddonInfo("trivia", "Trivia", "available"),
            AddonInfo("karaoke", "Karaoke", "active"),
            AddonInfo(" ", "No id", "active"),
            AddonInfo("multiview", "Duplicate", "paused"),
        )
        val list = Addons.list(c, now, now, known + "trivia")
        assertEquals(listOf("Multiview", "recorder", "Trivia", "Karaoke"), list.map { it.name })
        assertEquals(listOf("On", "Paused: membership ended", "Buy on the portal", "Needs a newer version of the app"), list.map { it.line })
        assertEquals("No longer offered", Addons.list(config(AddonInfo("trivia", "Trivia", "not_offered")), now, now, known + "trivia").single().line)
    }
}
