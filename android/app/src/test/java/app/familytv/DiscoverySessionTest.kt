package app.familytv

import app.familytv.discovery.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DiscoverySessionTest {
    private val identity = DiscoveryIdentity("profile-a", "policy-v1", "device-1")

    @Test fun grantsRejectSameLookingDifferentProfileAndChangedPolicyOrDevice() = runTest {
        val current = AtomicReference<DiscoveryIdentity?>(identity)
        val session = DiscoverySession(identity) { current.get() }
        session.verify()
        for (other in listOf(identity.copy(profileId = "profile-b"), identity.copy(policy = "policy-v2"), identity.copy(deviceId = "device-2"), null)) {
            current.set(other)
            try { session.verify(); fail("Stale grant accepted") } catch (_: SourceUnavailable) {}
        }
    }

    @Test fun cancelledHttpResponseCannotResumePlaybackWork() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(500, TimeUnit.MILLISECONDS))
            var reachedPlayback = false
            val request = async(Dispatchers.Default) {
                DiscoveryHttp(OkHttpClient()).request(Request.Builder().url(server.url("/pending")).build(), "Test")
                reachedPlayback = true
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            request.cancelAndJoin()
            assertFalse(reachedPlayback)
        }
    }

    private class MemoryGateway : ProfileRdGateway {
        val state = AtomicReference(ProfileRdKey("profile-a", 1, true, "synthetic-key-0123456789"))
        var writes = 0
        override suspend fun rdKeyRead(profileId: String): ProfileRdKey {
            val s = state.get()
            if (s.profileId != profileId) throw SourceUnavailable("Wrong profile")
            return s
        }
        override suspend fun rdKeyWrite(profileId: String, revision: Long, apiKey: String): ProfileRdKey {
            writes++
            val s = state.get()
            if (s.profileId != profileId || s.revision != revision) throw app.familytv.portal.PortalException(409, "stale")
            return s.copy(revision = revision + 1, configured = true, apiKey = apiKey).also(state::set)
        }
        override suspend fun rdKeyRemove(profileId: String, revision: Long): ProfileRdKey {
            val s = state.get()
            if (s.profileId != profileId || s.revision != revision) throw app.familytv.portal.PortalException(409, "stale")
            return s.copy(revision = revision + 1, configured = false, apiKey = null).also(state::set)
        }
    }
    private val source = SourceCandidate("0123456789abcdef0123456789abcdef01234567", 0, "Open Film 1080p x264", true)
    private val target = DiscoveryTarget(DiscoveryTitle("movie", "42", "Open Film", imdb = "tt1234567"))

    @Test fun revokedKeyDuringPendingResponseStopsFurtherMutationAndPlayback() = runBlocking {
        MockWebServer().use { server ->
            val gateway = MemoryGateway()
            server.enqueue(MockResponse().setBody("""{"id":"ABC"}""").setBodyDelay(300, TimeUnit.MILLISECONDS))
            val rd = ProfileRealDebrid(gateway, DiscoveryHttp(OkHttpClient()), server.url("/api").toString())
            val session = DiscoverySession(identity) { identity }
            var opened = false
            val pending = async(Dispatchers.Default) { runCatching { rd.prepare(session, source, target, {}) { opened = true } } }
            val add = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/api/torrents/addMagnet", add.path)
            assertEquals("Bearer synthetic-key-0123456789", add.getHeader("Authorization"))
            assertFalse(add.path!!.contains("synthetic-key"))
            gateway.state.set(ProfileRdKey("profile-a", 2, false, null))
            assertTrue(pending.await().exceptionOrNull() is SourceUnavailable)
            assertEquals(1, server.requestCount)
            assertFalse(opened)
            assertFalse(gateway.state.get().configured)
            assertEquals(2, gateway.state.get().revision)
        }
    }

    @Test fun staleReplaceCannotRestoreRevokedProfileKey() = runTest {
        val gateway = MemoryGateway()
        val rd = ProfileRealDebrid(gateway, DiscoveryHttp(OkHttpClient()))
        val session = DiscoverySession(identity) { identity }
        val old = rd.status(session)
        gateway.state.set(ProfileRdKey("profile-a", 2, false))
        try { rd.replace(session, old, "replacement-key-012345"); fail("Stale write accepted") } catch (_: SourceUnavailable) {}
        assertEquals(0, gateway.writes)
        assertFalse(gateway.state.get().configured)
        assertEquals(2, gateway.state.get().revision)
    }

    @Test fun removeLeavesMonotonicTombstoneAndOtherProfileCannotReadIt() = runTest {
        val gateway = MemoryGateway()
        val rd = ProfileRealDebrid(gateway, DiscoveryHttp(OkHttpClient()))
        val session = DiscoverySession(identity) { identity }
        val result = rd.remove(session, rd.status(session))
        assertFalse(result.configured)
        assertEquals(2, result.revision)
        assertNull(gateway.state.get().apiKey)
        val other = identity.copy(profileId = "profile-b")
        try { rd.status(DiscoverySession(other) { other }); fail("Cross-profile read accepted") } catch (_: SourceUnavailable) {}
        assertFalse(gateway.state.get().toString().contains("synthetic-key"))
    }

    @Test fun profileSwitchBeforeKeySavePreventsEveryWrite() = runTest {
        val gateway = MemoryGateway()
        val current = AtomicReference(identity)
        val session = DiscoverySession(identity) { current.get() }
        val rd = ProfileRealDebrid(gateway, DiscoveryHttp(OkHttpClient()))
        val expected = rd.status(session)
        current.set(identity.copy(profileId = "profile-b"))
        try { rd.replace(session, expected, "replacement-key-012345"); fail("Changed profile accepted") } catch (_: SourceUnavailable) {}
        assertEquals(0, gateway.writes)
    }

    @Test fun removalDuringFinalCatalogValidationPreventsPlayerLaunch() = runBlocking {
        MockWebServer().use { server ->
            val gateway = MemoryGateway()
            server.enqueue(MockResponse().setBody("""{"id":"ABC"}"""))
            server.enqueue(MockResponse().setBody("""{"hash":"0123456789abcdef0123456789abcdef01234567","filename":"Open Film","status":"downloaded","files":[{"id":1,"path":"Open.Film.1080p.mkv","bytes":100,"selected":1}],"links":["https://real-debrid.com/d/synthetic"]}"""))
            server.enqueue(MockResponse().setBody("""{"download":"https://cdn.example.test/open-film.mkv"}"""))
            val rd = ProfileRealDebrid(gateway, DiscoveryHttp(OkHttpClient()), server.url("/api").toString())
            val session = DiscoverySession(identity) { identity }
            var opened = false
            try {
                rd.prepare(session, source, target, {}, beforeOpen = {
                    gateway.state.set(ProfileRdKey("profile-a", 2, false))
                }) { opened = true }
                fail("Revoked key launched playback")
            } catch (_: SourceUnavailable) {}
            assertFalse(opened)
            assertEquals(3, server.requestCount)
        }
    }
}
