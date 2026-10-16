package app.familytv

import android.content.Context
import app.familytv.debrid.RealDebrid
import app.familytv.portal.RealDebridPortal
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RealDebridConfigTest {
    private fun application(): FamilyTvApp {
        val app = FamilyTvApp()
        val attach = android.app.Application::class.java.getDeclaredMethod("attach", Context::class.java)
        attach.isAccessible = true
        attach.invoke(app, RuntimeEnvironment.getApplication())
        return app
    }
    @Test fun configPublishesLinkedAccountWithoutLoadingItsLibrary() {
        val app = application()
        app.getSharedPreferences("realdebrid", Context.MODE_PRIVATE).edit().clear().apply()
        val rd = RealDebrid(app)
        rd.onConfig(RealDebridPortal("Synthetic account"))
        val state = rd.state.value
        assertTrue("Config must publish account status synchronously without a library request", state is RealDebrid.State.SignedIn)
        state as RealDebrid.State.SignedIn
        assertEquals("Synthetic account", state.name)
        assertFalse(state.refreshing)
        assertEquals(0L, state.lastRefresh)
        rd.onConfig(RealDebridPortal("Synthetic account"))
        assertEquals(state, rd.state.value)
    }

    @Test fun signOutConfigImmediatelyClearsLocalIndexAndStatus() {
        val app = application()
        app.getSharedPreferences("realdebrid", Context.MODE_PRIVATE).edit().clear().putBoolean("linked", true).apply()
        val rd = RealDebrid(app)
        rd.onConfig(null)
        assertTrue(rd.state.value is RealDebrid.State.SignedOut)
        assertEquals(0, rd.index.value.fileCount)
    }
}
