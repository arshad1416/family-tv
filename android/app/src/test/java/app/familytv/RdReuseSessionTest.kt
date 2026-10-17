package app.familytv

import app.familytv.discovery.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RdReuseSessionTest {
    private val identity = DiscoveryIdentity("profile-a", "policy-v1", "device-1")
    private val source = SourceCandidate("0123456789abcdef0123456789abcdef01234567", 0, "Open Film 1080p x264", true)
    private val target = DiscoveryTarget(DiscoveryTitle("movie", "42", "Open Film", imdb = "tt1234567"))
    private val paths = listOf("/api/torrents?limit=20", "/api/torrents/info/READY", "/api/unrestrict/link")
    private val bodies = listOf(
        """[{"id":"READY","hash":"${source.hash}","status":"downloaded"}]""",
        """{"hash":"${source.hash}","filename":"Open Film","status":"downloaded","files":[{"id":1,"path":"Open.Film.mkv","bytes":100,"selected":1}],"links":["https://real-debrid.com/d/synthetic"]}""",
        """{"download":"https://cdn.example.test/open-film.mkv"}""")
    private class Gateway : ProfileRdGateway {
        val state = AtomicReference(ProfileRdKey("profile-a", 1, true, "synthetic-key-0123456789"))
        var onRead: () -> Unit = {}
        override suspend fun rdKeyRead(profileId: String): ProfileRdKey { onRead(); return state.get() }
        override suspend fun rdKeyWrite(profileId: String, revision: Long, apiKey: String) = state.get().copy(revision = revision + 1, configured = true, apiKey = apiKey).also(state::set)
        override suspend fun rdKeyRemove(profileId: String, revision: Long) = state.get().copy(revision = revision + 1, configured = false, apiKey = null).also(state::set)
    }

    @Test fun acceptedReplacementRemovalPolicyOrProfileChangesBeforeAndAfterEachReuseRequestStopPlayback() = runBlocking {
        for (change in listOf("replacement", "removal", "policy", "profile")) for (requestIndex in 0..2) for (after in listOf(false, true)) {
            MockWebServer().use { server ->
                val gateway = Gateway()
                val current = AtomicReference(identity)
                val completed = AtomicInteger(0)
                var bucket = -1
                var bucketReads = 0
                fun mutate() {
                    when (change) {
                        "replacement" -> gateway.state.set(ProfileRdKey("profile-a", 2, true, "replacement-key-0123456789"))
                        "removal" -> gateway.state.set(ProfileRdKey("profile-a", 2, false))
                        "policy" -> current.set(identity.copy(policy = "policy-v2"))
                        else -> current.set(identity.copy(profileId = "profile-b"))
                    }
                }
                gateway.onRead = {
                    val n = completed.get()
                    if (bucket != n) { bucket = n; bucketReads = 0 }
                    bucketReads++
                    // The second read in each bucket is the next request's preflight;
                    // bucket zero also contains the initial account read.
                    if (!after && n == requestIndex && bucketReads == 2) mutate()
                }
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val index = paths.indexOf(request.path)
                        if (index < 0) return MockResponse().setResponseCode(500)
                        assertEquals("Bearer synthetic-key-0123456789", request.getHeader("Authorization"))
                        completed.incrementAndGet()
                        if (after && index == requestIndex) mutate()
                        return MockResponse().setBody(bodies[index])
                    }
                }
                val rd = ProfileRealDebrid(gateway, DiscoveryHttp(OkHttpClient()), server.url("/api").toString())
                var opened = false
                try { rd.prepare(DiscoverySession(identity) { current.get() }, source, target, {}) { opened = true }; fail("Stale state accepted") }
                catch (_: SourceUnavailable) {}
                assertFalse(opened)
                assertEquals(requestIndex + if (after) 1 else 0, server.requestCount)
            }
        }
    }

    @Test fun cancellationDuringLookupInfoOrUnrestrictCannotOpenOrIssueAnyFollowup() = runBlocking {
        for (requestIndex in 0..2) MockWebServer().use { server ->
            for (index in 0..requestIndex) server.enqueue(MockResponse().setBody(bodies[index]).apply {
                if (index == requestIndex) setBodyDelay(300, TimeUnit.MILLISECONDS)
            })
            val rd = ProfileRealDebrid(Gateway(), DiscoveryHttp(OkHttpClient()), server.url("/api").toString())
            var opened = false
            val job = launch(Dispatchers.Default) { rd.prepare(DiscoverySession(identity) { identity }, source, target, {}) { opened = true } }
            for (index in 0..requestIndex) assertEquals(paths[index], server.takeRequest(2, TimeUnit.SECONDS)?.path)
            job.cancelAndJoin()
            assertFalse(opened)
            assertEquals(requestIndex + 1, server.requestCount)
        }
    }

    @Test fun policyChangeDuringLastKeyReadCannotOpen() = runBlocking {
        MockWebServer().use { server ->
            bodies.forEach { server.enqueue(MockResponse().setBody(it)) }
            val gateway = Gateway()
            val current = AtomicReference(identity)
            var finalValidation = false
            var reads = 0
            gateway.onRead = { if (finalValidation && ++reads == 2) current.set(identity.copy(policy = "policy-v2")) }
            val rd = ProfileRealDebrid(gateway, DiscoveryHttp(OkHttpClient()), server.url("/api").toString())
            var opened = false
            try {
                rd.prepare(DiscoverySession(identity) { current.get() }, source, target, {}, beforeOpen = { finalValidation = true }) { opened = true }
                fail("Policy changed during final key verification")
            } catch (_: SourceUnavailable) {}
            assertFalse(opened)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun finalCallerValidationBlocksReuseAfterAnAcceptedKeyRemovalOrPolicyChange() = runBlocking {
        for (remove in listOf(true, false)) MockWebServer().use { server ->
            bodies.forEach { server.enqueue(MockResponse().setBody(it)) }
            val gateway = Gateway()
            val current = AtomicReference(identity)
            val rd = ProfileRealDebrid(gateway, DiscoveryHttp(OkHttpClient()), server.url("/api").toString())
            val session = DiscoverySession(identity) { current.get() }
            var opened = false
            try {
                rd.prepare(session, source, target, {}, beforeOpen = {
                    if (remove) rd.remove(session, rd.status(session)) else current.set(identity.copy(policy = "policy-v2"))
                }) { opened = true }
                fail("Final validation accepted stale state")
            } catch (_: SourceUnavailable) {}
            assertFalse(opened)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun malformedSuccessfulResponsesNeverReflectContentsOrFallBackToMutation() = runBlocking {
        for (requestIndex in 0..2) for (body in listOf("null", "{", "\"Bearer synthetic-key-0123456789 https://private.test/source/READY\"", "{\"download\":{\"secret\":\"synthetic-key-0123456789\"}}")) MockWebServer().use { server ->
            for (index in 0 until requestIndex) server.enqueue(MockResponse().setBody(bodies[index]))
            server.enqueue(MockResponse().setBody(body))
            val rd = ProfileRealDebrid(Gateway(), DiscoveryHttp(OkHttpClient()), server.url("/api").toString())
            var opened = false
            try { rd.prepare(DiscoverySession(identity) { identity }, source, target, {}) { opened = true }; fail("Malformed response accepted") }
            catch (e: SourceUnavailable) {
                assertFalse(e.message.orEmpty().contains("synthetic-key"))
                assertFalse(e.message.orEmpty().contains("READY"))
                assertFalse(e.message.orEmpty().contains("https://"))
            }
            assertFalse(opened)
            assertEquals(requestIndex + 1, server.requestCount)
        }
    }

    @Test fun deniedReusedInfoAndLinkStopAtThatRequestAndNeverReflectSecrets() = runBlocking {
        for (code in listOf(401, 403, 451)) for (requestIndex in 0..2) MockWebServer().use { server ->
            for (index in 0 until requestIndex) server.enqueue(MockResponse().setBody(bodies[index]))
            server.enqueue(MockResponse().setResponseCode(code).setBody("Bearer synthetic-key-0123456789 https://private.test/source/READY"))
            val rd = ProfileRealDebrid(Gateway(), DiscoveryHttp(OkHttpClient()), server.url("/api").toString())
            var opened = false
            try { rd.prepare(DiscoverySession(identity) { identity }, source, target, {}) { opened = true }; fail("Denial accepted") }
            catch (e: SourceUnavailable) {
                assertTrue(e.message.orEmpty().contains(if (code == 401) "invalid" else "HTTP $code"))
                assertFalse(e.message.orEmpty().contains("synthetic-key"))
                assertFalse(e.message.orEmpty().contains("READY"))
                assertFalse(e.message.orEmpty().contains("https://"))
            }
            assertFalse(opened)
            assertEquals(requestIndex + 1, server.requestCount)
        }
    }
}
