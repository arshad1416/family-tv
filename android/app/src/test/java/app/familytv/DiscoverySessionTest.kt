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

    @Test fun emptyAccount204FallsBackToNewPreparationButEmpty200FailsClosed() = runBlocking {
        for (code in listOf(204, 200)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(code))
            server.enqueue(MockResponse().setBody("""{"id":"NEW"}"""))
            server.enqueue(MockResponse().setBody("""{"hash":"${source.hash}","filename":"Open Film","status":"downloaded","files":[{"id":1,"path":"Open.Film.mkv","bytes":100,"selected":1}],"links":["https://real-debrid.com/d/synthetic"]}"""))
            server.enqueue(MockResponse().setBody("""{"download":"https://cdn.example.test/new.mkv"}"""))
            val rd = ProfileRealDebrid(MemoryGateway(), DiscoveryHttp(OkHttpClient()), server.url("/api").toString())
            val launched = mutableListOf<String>()
            val result = runCatching { rd.prepare(DiscoverySession(identity) { identity }, source, target, {}, open = launched::add) }
            assertEquals("/api/torrents?limit=20", server.takeRequest().path)
            if (code == 204) {
                result.getOrThrow()
                assertEquals(listOf("https://cdn.example.test/new.mkv"), launched)
                assertEquals("/api/torrents/addMagnet", server.takeRequest().path)
                assertEquals(4, server.requestCount)
            } else {
                assertTrue(result.exceptionOrNull() is SourceUnavailable)
                assertTrue(launched.isEmpty())
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun readyAccountTorrentIsReusedWithoutAddSelectionOrDelete() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":"READY","hash":"${source.hash}","status":"downloaded"}]"""))
            server.enqueue(MockResponse().setBody("""{"hash":"${source.hash}","filename":"Open Film","status":"downloaded","files":[{"id":1,"path":"Open.Film.1080p.mkv","bytes":100,"selected":1}],"links":["https://real-debrid.com/d/synthetic"]}"""))
            server.enqueue(MockResponse().setBody("""{"download":"https://cdn.example.test/reused.mkv"}"""))
            val rd = ProfileRealDebrid(MemoryGateway(), DiscoveryHttp(OkHttpClient()), server.url("/api").toString())
            val launched = mutableListOf<String>()
            val result = runCatching { rd.prepare(DiscoverySession(identity) { identity }, source, target, {}, open = launched::add) }
            val requests = List(server.requestCount) { server.takeRequest() }
            assertEquals("Ready account media must be checked before adding a new torrent", "/api/torrents?limit=20", requests.first().path)
            result.getOrThrow()
            assertEquals(listOf("https://cdn.example.test/reused.mkv"), launched)
            assertEquals(listOf("/api/torrents?limit=20", "/api/torrents/info/READY", "/api/unrestrict/link"), requests.map { it.path })
            assertEquals(listOf("GET", "GET", "POST"), requests.map { it.method })
        }
    }

    @Test fun revokedKeyDuringPendingResponseStopsFurtherMutationAndPlayback() = runBlocking {
        MockWebServer().use { server ->
            val gateway = MemoryGateway()
            server.enqueue(MockResponse().setBody("[]").setBodyDelay(300, TimeUnit.MILLISECONDS))
            val rd = ProfileRealDebrid(gateway, DiscoveryHttp(OkHttpClient()), server.url("/api").toString())
            val session = DiscoverySession(identity) { identity }
            var opened = false
            val pending = async(Dispatchers.Default) { runCatching { rd.prepare(session, source, target, {}) { opened = true } } }
            val lookup = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/api/torrents?limit=20", lookup.path)
            assertEquals("Bearer synthetic-key-0123456789", lookup.getHeader("Authorization"))
            assertFalse(lookup.path!!.contains("synthetic-key"))
            gateway.state.set(ProfileRdKey("profile-a", 2, false, null))
            assertTrue(pending.await().exceptionOrNull() is SourceUnavailable)
            assertEquals(1, server.requestCount)
            assertFalse(opened)
            assertFalse(gateway.state.get().configured)
            assertEquals(2, gateway.state.get().revision)
        }
    }

    @Test fun validatedStatusShowsOnlyMaskedPreviewAndReplacementUpdatesIt() = runTest {
        val gateway = MemoryGateway()
        val rd = ProfileRealDebrid(gateway, DiscoveryHttp(OkHttpClient()))
        val session = DiscoverySession(identity) { identity }
        val key = "ABCDsyntheticMIDDLE123WXYZ"
        gateway.state.set(ProfileRdKey("profile-a", 1, true, key))
        fun preview(status: ProfileRdStatus) = status.maskedPreview
        val status = rd.status(session)
        assertEquals("ABCD********WXYZ", preview(status))
        assertFalse(status.toString().contains(key))
        val saved = rd.replace(session, status, "EFGHsyntheticReplacementIJKL")
        assertEquals("EFGH********IJKL", preview(saved))
        assertNull(preview(rd.remove(session, saved)))
        for (invalid in listOf("short", "x".repeat(513), "ABCDsynthetic keyWXYZ", "ABCDsyntheticékeyWXYZ")) {
            assertNull(ProfileRealDebrid.maskedPreview(invalid))
            gateway.state.set(ProfileRdKey("profile-a", 4, true, invalid))
            try { rd.status(session); fail("Invalid key produced a preview") } catch (_: SourceUnavailable) {}
        }
        gateway.state.set(ProfileRdKey("profile-b", 5, true, key))
        try { rd.status(session); fail("Wrong profile produced a preview") } catch (_: SourceUnavailable) {}
    }

    @Test fun mutationAcknowledgementsRequireExactlyNextRevisionProfileAndConfiguredState() = runTest {
        for (remove in listOf(false, true)) {
            for (badAck in listOf(
                ProfileRdKey("profile-a", -1, !remove),
                ProfileRdKey("profile-a", 0, !remove),
                ProfileRdKey("profile-a", 1, !remove),
                ProfileRdKey("profile-a", 3, !remove),
                ProfileRdKey("profile-b", 2, !remove),
                ProfileRdKey("profile-a", 2, remove))) {
                var mutations = 0
                val gateway = object : ProfileRdGateway {
                    override suspend fun rdKeyRead(profileId: String) = ProfileRdKey("profile-a", 1, true, "ABCDsyntheticKey123WXYZ")
                    override suspend fun rdKeyWrite(profileId: String, revision: Long, apiKey: String): ProfileRdKey { mutations++; return badAck }
                    override suspend fun rdKeyRemove(profileId: String, revision: Long): ProfileRdKey { mutations++; return badAck }
                }
                val rd = ProfileRealDebrid(gateway, DiscoveryHttp(OkHttpClient()))
                val session = DiscoverySession(identity) { identity }
                val expected = rd.status(session)
                try {
                    if (remove) rd.remove(session, expected) else rd.replace(session, expected, "EFGHsyntheticNewKey123IJKL")
                    fail("Invalid acknowledgement accepted: remove=$remove, revision=${badAck.revision}")
                } catch (_: SourceUnavailable) {}
                assertEquals(1, mutations)
            }
        }
    }

    @Test fun lastNonOverflowingRevisionAcceptsExactlyOneIncrement() = runTest {
        for (remove in listOf(false, true)) {
            var mutations = 0
            val gateway = object : ProfileRdGateway {
                override suspend fun rdKeyRead(profileId: String) = ProfileRdKey(profileId, Long.MAX_VALUE - 1, true, "ABCDsyntheticKey123WXYZ")
                override suspend fun rdKeyWrite(profileId: String, revision: Long, apiKey: String): ProfileRdKey { mutations++; return ProfileRdKey(profileId, revision + 1, true) }
                override suspend fun rdKeyRemove(profileId: String, revision: Long): ProfileRdKey { mutations++; return ProfileRdKey(profileId, revision + 1, false) }
            }
            val rd = ProfileRealDebrid(gateway, DiscoveryHttp(OkHttpClient()))
            val session = DiscoverySession(identity) { identity }
            val expected = rd.status(session)
            val result = if (remove) rd.remove(session, expected) else rd.replace(session, expected, "EFGHsyntheticNewKey123IJKL")
            assertEquals(Long.MAX_VALUE, result.revision)
            assertEquals(!remove, result.configured)
            assertEquals(if (remove) null else "EFGH********IJKL", result.maskedPreview)
            assertEquals(1, mutations)
        }
    }

    @Test fun invalidExpectedRevisionCannotIssueAnyGatewayCall() = runTest {
        for (revision in listOf(-1L, Long.MIN_VALUE, Long.MAX_VALUE)) for (remove in listOf(false, true)) {
            var calls = 0
            val gateway = object : ProfileRdGateway {
                override suspend fun rdKeyRead(profileId: String): ProfileRdKey { calls++; return ProfileRdKey(profileId, revision, true, "ABCDsyntheticKey123WXYZ") }
                override suspend fun rdKeyWrite(profileId: String, revision: Long, apiKey: String): ProfileRdKey { calls++; error("Invalid revision written") }
                override suspend fun rdKeyRemove(profileId: String, revision: Long): ProfileRdKey { calls++; error("Invalid revision removed") }
            }
            val rd = ProfileRealDebrid(gateway, DiscoveryHttp(OkHttpClient()))
            val expected = ProfileRdStatus("profile-a", revision, true)
            val session = DiscoverySession(identity) { identity }
            try {
                if (remove) rd.remove(session, expected) else rd.replace(session, expected, "EFGHsyntheticNewKey123IJKL")
                fail("Invalid revision accepted")
            } catch (_: SourceUnavailable) {}
            assertEquals("Invalid revisions must fail before gateway access", 0, calls)
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

    @Test fun successfulSelectedEpisodeResolutionDispatchesOnceWithNoStatusLoop() = runBlocking {
        MockWebServer().use { server ->
            val gateway = MemoryGateway()
            val episodeSource = source.copy(fileIdx = 1, label = "Open Series S02E03 1080p x264")
            val episodeTarget = DiscoveryTarget(DiscoveryTitle("tv", "42", "Open Series", imdb = "tt1234567"), 2, 3, "Selected Episode")
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("""{"id":"ABC"}"""))
            server.enqueue(MockResponse().setBody("""{"hash":"${source.hash}","filename":"Open Series S02","status":"downloaded","files":[{"id":1,"path":"Open.Series.S02E02.mkv","bytes":100,"selected":0},{"id":2,"path":"Open.Series.S02E03.mkv","bytes":200,"selected":1}],"links":["https://real-debrid.com/d/synthetic"]}"""))
            server.enqueue(MockResponse().setBody("""{"download":"https://cdn.example.test/selected-episode.mkv"}"""))
            val rd = ProfileRealDebrid(gateway, DiscoveryHttp(OkHttpClient()), server.url("/api").toString())
            val launched = mutableListOf<Pair<DiscoveryTarget, String>>()
            val status = mutableListOf<String>()
            var validations = 0
            rd.prepare(DiscoverySession(identity) { identity }, episodeSource, episodeTarget, status::add,
                beforeOpen = { validations++ }) { url -> launched += episodeTarget to url }
            assertEquals(listOf(episodeTarget to "https://cdn.example.test/selected-episode.mkv"), launched)
            assertEquals(1, validations)
            assertEquals(4, server.requestCount)
            assertEquals("Resolving the selected video link…", status.last())
            assertEquals(2, launched.single().first.season)
            assertEquals(3, launched.single().first.episode)
            assertEquals("Selected Episode", launched.single().first.episodeName)
        }
    }

    @Test fun http451AtEveryPreparationStageStopsWithoutRetryOrSecretEcho() = runBlocking {
        val info = """{"hash":"${source.hash}","filename":"Open Film","status":"downloaded","files":[{"id":1,"path":"Open.Film.1080p.mkv","bytes":100,"selected":1}],"links":["https://real-debrid.com/d/synthetic"]}"""
        val cases = listOf(
            "checking existing account sources" to emptyList(),
            "adding the selected torrent" to listOf("[]"),
            "checking torrent status" to listOf("[]", """{"id":"ABC"}"""),
            "selecting the video file" to listOf("[]", """{"id":"ABC"}""", info.replace("downloaded", "waiting_files_selection")),
            "resolving the video link" to listOf("[]", """{"id":"ABC"}""", info))
        for ((stage, preceding) in cases) MockWebServer().use { server ->
            preceding.forEach { server.enqueue(MockResponse().setBody(it)) }
            server.enqueue(MockResponse().setResponseCode(451).setBody("synthetic-secret-body https://secret.example.test/?key=fixture"))
            val rd = ProfileRealDebrid(MemoryGateway(), DiscoveryHttp(OkHttpClient()), server.url("/api").toString())
            var opens = 0
            try { rd.prepare(DiscoverySession(identity) { identity }, source, target, {}) { opens++ }; fail("451 accepted") }
            catch (e: SourceUnavailable) {
                assertTrue(e.message.orEmpty().contains("HTTP 451: unavailable for legal reasons"))
                assertTrue(e.message.orEmpty().contains(stage))
                assertFalse(e.message.orEmpty().contains("try again", true))
                assertFalse(e.message.orEmpty().contains("synthetic-secret"))
                assertFalse(e.message.orEmpty().contains("https://"))
            }
            assertEquals(preceding.size + 1, server.requestCount)
            assertEquals(0, opens)
        }
    }

    @Test fun allowlistedNumericProviderReasonsAreCanonicalAndNeverReflectBody() = runBlocking {
        for ((providerCode, reason) in listOf(22 to "IP address not allowed", 28 to "File not allowed", 35 to "Infringing file", 37 to "Disabled endpoint")) {
            for (httpCode in listOf(400, 451)) MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(httpCode).setBody("""{"error_code":$providerCode,"error":"synthetic-secret https://private.test/fixture"}"""))
                val rd = ProfileRealDebrid(MemoryGateway(), DiscoveryHttp(OkHttpClient()), server.url("/api").toString())
                try { rd.prepare(DiscoverySession(identity) { identity }, source, target, {}) { fail("Opened") }; fail("400 accepted") }
                catch (e: SourceUnavailable) {
                    assertTrue(e.message.orEmpty().contains("HTTP $httpCode"))
                    if (httpCode == 451) {
                        assertTrue(e.message.orEmpty().contains("unavailable for legal reasons"))
                        assertTrue(e.message.orEmpty().contains("No automatic retry."))
                    }
                    assertTrue(e.message.orEmpty().contains("Provider code $providerCode: $reason."))
                    assertFalse(e.message.orEmpty().contains("synthetic-secret"))
                    assertFalse(e.message.orEmpty().contains("https://"))
                }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun unknownMalformedOrNonNumericProviderReasonsAreIgnored() = runBlocking {
        for (body in listOf("""{"error_code":451,"error":"synthetic-secret"}""", """{"error_code":"22"}""", """{"error_code":22.0}""", """{"error_code":220}""", """{"error_code":[22]}""", "not json synthetic-secret", """{"error_code":22,"error":"${"x".repeat(4096)}"}""")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(400).setBody(body))
                val rd = ProfileRealDebrid(MemoryGateway(), DiscoveryHttp(OkHttpClient()), server.url("/api").toString())
                try { rd.prepare(DiscoverySession(identity) { identity }, source, target, {}) { fail("Opened") }; fail("400 accepted") }
                catch (e: SourceUnavailable) {
                    assertEquals("Real-Debrid could not prepare this source (HTTP 400). Try another source.", e.message)
                }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun providerJson451IsNotHttp451() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error_code":451,"error":"synthetic-secret"}"""))
            val rd = ProfileRealDebrid(MemoryGateway(), DiscoveryHttp(OkHttpClient()), server.url("/api").toString())
            try { rd.prepare(DiscoverySession(identity) { identity }, source, target, {}) { fail("Opened") }; fail("400 accepted") }
            catch (e: SourceUnavailable) { assertTrue(e.message.orEmpty().contains("HTTP 400")); assertFalse(e.message.orEmpty().contains("legal reasons")) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun providerFailureIsExplicitAndNeverDispatchesPlayback() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("synthetic provider body"))
            val rd = ProfileRealDebrid(MemoryGateway(), DiscoveryHttp(OkHttpClient()), server.url("/api").toString())
            var opens = 0
            try {
                rd.prepare(DiscoverySession(identity) { identity }, source, target, {}) { opens++ }
                fail("Provider failure accepted")
            } catch (e: SourceUnavailable) {
                assertEquals("Real-Debrid denied this request (HTTP 403). Check account access or premium status.", e.message)
            }
            assertEquals(0, opens)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun removalDuringFinalCatalogValidationPreventsPlayerLaunch() = runBlocking {
        MockWebServer().use { server ->
            val gateway = MemoryGateway()
            server.enqueue(MockResponse().setBody("[]"))
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
            assertEquals(4, server.requestCount)
        }
    }
}
