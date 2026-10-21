package app.familytv.portal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.familytv.sync.TvLock
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A TV removed or unpaired in the portal drops its lock, so the lock never covers the pairing code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class UnpairUnlockTest {
    private val server = MockWebServer().apply { start() }
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val portal = PortalClient(ctx, server.url("/").toString())

    @Before fun locked() {
        ctx.getSharedPreferences("portal", Context.MODE_PRIVATE).edit().putString("token", "t").commit()
        TvLock.init(ctx)
        TvLock.apply(DeviceLock("Paused"))
    }

    @After fun tearDown() {
        server.shutdown()
        TvLock.apply(null)
        ctx.getSharedPreferences("portal", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun aHeartbeatThatSaysUnpairedUnlocks() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"paired":false,"commands":[],"lock":null}"""))
        portal.heartbeat(emptyMap())
        assertNull(TvLock.message.value)
    }

    @Test fun aRemovedTvsRejectedHeartbeatUnlocks() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unknown device"}"""))
        runCatching { portal.heartbeat(emptyMap()) }
        assertNull(TvLock.message.value)
    }

    @Test fun aConfigThatSaysUnpairedUnlocks() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"paired":false,"pairCode":"ABC123","pairExpires":1}"""))
        portal.config()
        assertNull(TvLock.message.value)
    }

    @Test fun aRemovedTvsRejectedConfigUnlocksAndShowsANewCode() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unknown device"}"""))
        server.enqueue(MockResponse().setBody("""{"deviceId":"d2","deviceToken":"t2","pairCode":"XYZ789","pairExpires":1}"""))
        server.enqueue(MockResponse().setBody("""{"paired":false,"pairCode":"XYZ789","pairExpires":1}"""))
        assertEquals("XYZ789", portal.config().pairCode)
        assertNull(TvLock.message.value)
    }

    @Test fun aPairedHeartbeatKeepsTheLock() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"paired":true,"commands":[],"lock":{"message":"Paused"}}"""))
        portal.heartbeat(emptyMap())
        assertEquals("Paused", TvLock.message.value)
    }
}
