package app.familytv

import android.app.Application
import android.content.Context
import app.familytv.discovery.*
import app.familytv.portal.PortalClient
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real Worker contract -> PortalClient -> real session factory -> account state, without providers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PortalDiscoveryFlowTest {
    @Test fun workerContractSupportsKeyLifecycleAndTitleSearch() = runTest {
        val exchanges = JsonParser.parseString(javaClass.getResource("/portal-rd-contract.json")!!.readText())
            .asJsonObject.getAsJsonArray("exchanges").map { it.asJsonObject }
        fun body(index: Int) = exchanges[index]["body"].asString
        var state = 3
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val response = when {
                        request.path == "/api/device/config" -> body(0)
                        request.path == "/api/device/progress?since=0" -> body(1)
                        request.method == "PUT" && request.path == "/api/device/realdebrid/api-key" -> body(4).also { state = 5 }
                        request.method == "DELETE" && request.path == "/api/device/realdebrid/api-key" -> body(6).also { state = 7 }
                        request.method == "GET" && request.path == "/api/device/realdebrid/api-key?profileId=ab1234cd5678ef90" -> body(state)
                        request.path!!.startsWith("/search/multi?") -> """{"results":[{"id":42,"media_type":"movie","title":"Synthetic Movie","adult":false}]}"""
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody(response)
                }
            }
            val context = RuntimeEnvironment.getApplication()
            context.getSharedPreferences("portal", Context.MODE_PRIVATE).edit().clear()
                .putString("token", "synthetic-device-token").putString("deviceId", "synthetic-device").commit()
            val portal = PortalClient(context, server.url("/").toString().trimEnd('/'), allowLoopbackKeyHttp = true)
            val profile = portal.config().profile!!
            val session = DiscoverySession.open(portal, profile)
            assertEquals("ab1234cd5678ef90", session.profileId)
            val rd = ProfileRealDebrid(portal)
            val absent = rd.status(session)
            assertFalse(absent.configured)
            val saved = rd.replace(session, absent, "fixture-only-never-a-real-account-key")
            assertTrue(saved.configured)
            assertEquals(1L, rd.status(session).revision)
            val metadata = DiscoveryClient(OkHttpClient(), portal.cachedConfig!!.tmdbApiKey!!, server.url("/").toString().trimEnd('/'))
            assertEquals("Synthetic Movie", metadata.search("Synthetic").single().title)
            val removed = rd.remove(session, saved)
            assertFalse(removed.configured)
            assertEquals(2L, rd.status(session).revision)
            val requests = List(server.requestCount) { server.takeRequest() }
            val accountRequests = requests.filter { it.requestUrl!!.encodedPath == "/api/device/realdebrid/api-key" }
            assertEquals(2, accountRequests.count { it.method != "GET" })
            accountRequests.forEach { request ->
                assertEquals("Bearer synthetic-device-token", request.getHeader("Authorization"))
                if (request.method == "GET") {
                    assertEquals("/api/device/realdebrid/api-key?profileId=ab1234cd5678ef90", request.path)
                } else {
                    assertEquals("/api/device/realdebrid/api-key", request.path)
                    val payload = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                    assertEquals("ab1234cd5678ef90", payload["profileId"].asString)
                    when (request.method) {
                        "PUT" -> {
                            assertEquals(0L, payload["revision"].asLong)
                            assertEquals("fixture-only-never-a-real-account-key", payload["apiKey"].asString)
                        }
                        "DELETE" -> {
                            assertEquals(1L, payload["revision"].asLong)
                            assertTrue(!payload.has("apiKey") || payload["apiKey"].isJsonNull)
                        }
                        else -> fail("Unexpected account method")
                    }
                }
            }
            val search = requests.single { it.requestUrl!!.encodedPath == "/search/multi" }
            assertEquals("GET", search.method)
            assertEquals("Synthetic", search.requestUrl!!.queryParameter("query"))
            assertEquals("false", search.requestUrl!!.queryParameter("include_adult"))
            assertEquals("synthetic-tmdb-metadata-key", search.requestUrl!!.queryParameter("api_key"))
            assertNull(search.getHeader("Authorization"))
            assertFalse(search.path!!.contains("fixture-only"))
            assertEquals(0L, search.bodySize)
        }
    }
}
