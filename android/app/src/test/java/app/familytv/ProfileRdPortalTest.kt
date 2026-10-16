package app.familytv

import android.app.Application
import android.content.Context
import app.familytv.portal.PortalClient
import app.familytv.portal.PortalException
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfileRdPortalTest {
    private fun client(server: MockWebServer): PortalClient {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("portal", Context.MODE_PRIVATE).edit().clear().putString("token", "synthetic-device-bearer").apply()
        return PortalClient(context, server.url("/").toString().trimEnd('/'), allowLoopbackKeyHttp = true)
    }

    @Test fun keyIsSentOnlyInExplicitWriteBodyWithProfileAndRevision() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"profileId":"profile-a","revision":0,"configured":false,"apiKey":null}"""))
            server.enqueue(MockResponse().setBody("""{"profileId":"profile-a","revision":1,"configured":true}"""))
            val client = client(server)
            assertFalse(client.rdKeyRead("profile-a").configured)
            client.rdKeyWrite("profile-a", 0, "synthetic-key-0123456789")
            val read = server.takeRequest()
            val write = server.takeRequest()
            assertEquals("GET", read.method)
            assertEquals("/api/device/realdebrid/api-key?profileId=profile-a", read.path)
            assertEquals("Bearer synthetic-device-bearer", read.getHeader("Authorization"))
            assertEquals("no-store", read.getHeader("Cache-Control"))
            assertEquals("PUT", write.method)
            assertFalse(write.path!!.contains("synthetic-key"))
            val body = JsonParser.parseString(write.body.readUtf8()).asJsonObject
            assertEquals("profile-a", body["profileId"].asString)
            assertEquals(0, body["revision"].asInt)
            assertEquals("synthetic-key-0123456789", body["apiKey"].asString)
        }
    }

    @Test fun staleWriteIsNotRetriedAndErrorDoesNotExposeResponseSecret() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(409).setBody("synthetic-secret-must-not-escape"))
            try { client(server).rdKeyWrite("profile-a", 1, "synthetic-key-0123456789"); fail("Conflict accepted") }
            catch (e: PortalException) { assertEquals(409, e.code); assertFalse(e.message!!.contains("must-not-escape")) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun portalRedirectCannotForwardDeviceBearerOrApiKey() = runTest {
        MockWebServer().use { origin -> MockWebServer().use { other ->
            origin.enqueue(MockResponse().setResponseCode(307).addHeader("Location", other.url("/leak")))
            try { client(origin).rdKeyWrite("profile-a", 1, "synthetic-key-0123456789"); fail("Redirect accepted") } catch (_: PortalException) {}
            assertNull(other.takeRequest(100, TimeUnit.MILLISECONDS))
            assertEquals(1, origin.requestCount)
        } }
    }

    @Test fun insecurePortalAndCredentialBearingBasesAreRejectedBeforeSending() = runTest {
        MockWebServer().use { server ->
            val context = RuntimeEnvironment.getApplication()
            for (base in listOf(server.url("/").toString(), "https://name:password@example.test", "https://example.test?token=secret", "https://example.test/#secret")) {
                try { PortalClient(context, base).rdKeyWrite("profile-a", 0, "synthetic-key-0123456789"); fail("Unsafe portal accepted") }
                catch (_: app.familytv.discovery.SourceUnavailable) {}
            }
            assertEquals(0, server.requestCount)
        }
    }
}
