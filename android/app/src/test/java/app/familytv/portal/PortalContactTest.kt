package app.familytv.portal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The add-ons' 7-day offline clock runs from the portal's last answer, so a TV that stays on keeps them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PortalContactTest {
    private val server = MockWebServer().apply { start() }
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = ctx.getSharedPreferences("portal", Context.MODE_PRIVATE)
    private val portal = PortalClient(ctx, server.url("/").toString())

    @After fun tearDown() {
        server.shutdown()
        prefs.edit().clear().commit()
    }

    private fun lastContact(at: Long) = prefs.edit().putString("token", "t").putLong("contactAt", at).commit()

    @Test fun aHeartbeatAnswerResetsTheClock() = runBlocking {
        lastContact(1)
        server.enqueue(MockResponse().setBody("""{"paired":true,"commands":[]}"""))
        val before = System.currentTimeMillis()
        portal.heartbeat(emptyMap())
        assertTrue(portal.contactAt >= before)
    }

    @Test fun anUnpairedAnswerOrAFailedOneDoesNot() = runBlocking {
        lastContact(1)
        server.enqueue(MockResponse().setBody("""{"paired":false,"commands":null}"""))
        portal.heartbeat(emptyMap())
        server.enqueue(MockResponse().setResponseCode(500))
        runCatching { portal.heartbeat(emptyMap()) }
        assertEquals(1L, portal.contactAt)
    }

    @Test fun savingAConfigResetsItToo() {
        lastContact(1)
        val before = System.currentTimeMillis()
        portal.cachedConfig = DeviceConfig(paired = true)
        assertTrue(portal.contactAt >= before)
    }
}
