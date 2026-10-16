package app.familytv

import app.familytv.discovery.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RdSourceResolverTest {
    private val hash = "0123456789abcdef0123456789abcdef01234567"
    private val source = SourceCandidate(hash, 1, "Northern Lights S02E03 1080p x264", true)
    private val target = DiscoveryTarget(DiscoveryTitle("tv", "42", "Northern Lights", imdb = "tt1234567"), 2, 3)

    private fun info(status: String, selected: Int = 0, returnedHash: String = hash, ep: Int = 3, extraSelected: Int = 0) = """{
      "id":"ABC","hash":"$returnedHash","filename":"Northern Lights S02","status":"$status","progress":50,
      "files":[{"id":1,"path":"/Northern.Lights.S02E02.1080p.mkv","bytes":100,"selected":$extraSelected},
               {"id":2,"path":"/Northern.Lights.S02E0$ep.1080p.mkv","bytes":200,"selected":$selected}],
      "links":["https://real-debrid.com/d/synthetic"]} """

    @Test fun progressIsReportedOnlyForValidNumericPercentagesAndTimeoutRemainsHonest() = runTest {
        for (raw in listOf("25.5", "-1", "101", "null", "\"50\"", "{}")) {
            val reports = mutableListOf<PreparationProgress>()
            val resolver = RdSourceResolver({ path, _ ->
                if (path == "/torrents/addMagnet") """{"id":"ABC"}"""
                else """{"hash":"$hash","status":"downloading","progress":$raw}"""
            }, {}, {})
            try { resolver.prepare(source, target, progress = reports::add) {}; fail("Wait must be bounded") }
            catch (e: SourceUnavailable) { assertTrue(e.message!!.contains("still preparing")) }
            val downloading = reports.filter { it.stage.contains("Downloading") }
            assertEquals(120, downloading.size)
            assertTrue(downloading.all { it.percent == if (raw == "25.5") 25.5 else null })
        }
    }

    @Test fun stageChangesClearPreviousDownloadPercentageBeforeSelectionAndLinkResolution() = runTest {
        val states = ArrayDeque(listOf(info("downloading"), info("queued"), info("waiting_files_selection"), info("downloaded", 1)))
        val reports = mutableListOf<PreparationProgress>()
        val resolver = RdSourceResolver({ path, _ ->
            when (path) {
                "/torrents/addMagnet" -> """{"id":"ABC"}"""
                "/torrents/info/ABC" -> states.removeFirst()
                "/torrents/selectFiles/ABC" -> ""
                "/unrestrict/link" -> """{"download":"https://cdn.example.test/video.mkv"}"""
                else -> error("Unexpected path")
            }
        }, {}, {})
        resolver.prepare(source, target, progress = reports::add) {}
        assertEquals(50.0, reports.single { it.stage.startsWith("Downloading") }.percent)
        assertTrue(reports.filterNot { it.stage.startsWith("Downloading") }.all { it.percent == null })
        assertTrue(reports.any { it.stage.startsWith("Selecting") })
        assertTrue(reports.last().stage.startsWith("Resolving"))
    }

    @Test fun selectionUsesRdFileIdThenOnlyItsUniqueLink() = runTest {
        val mutations = mutableListOf<Pair<String, Map<String, String>?>>()
        val states = ArrayDeque(listOf(info("waiting_files_selection"), info("downloading", 1), info("downloaded", 1)))
        val status = mutableListOf<String>()
        val resolver = RdSourceResolver({ path, form ->
            if (form != null) mutations += path to form
            when (path) {
                "/torrents/addMagnet" -> """{"id":"ABC","uri":"https://api.real-debrid.com/rest/1.0/torrents/info/ABC"}"""
                "/torrents/info/ABC" -> states.removeFirst()
                "/torrents/selectFiles/ABC" -> ""
                "/unrestrict/link" -> """{"download":"https://cdn.example.test/video.mkv"}"""
                else -> error("Unexpected path")
            }
        }, {}, { })
        assertEquals("https://cdn.example.test/video.mkv", resolver.prepare(source, target, status::add))
        assertEquals(listOf(
            "/torrents/addMagnet" to mapOf("magnet" to "magnet:?xt=urn:btih:$hash"),
            "/torrents/selectFiles/ABC" to mapOf("files" to "2"),
            "/unrestrict/link" to mapOf("link" to "https://real-debrid.com/d/synthetic")), mutations)
        assertTrue(status.any { it.contains("Downloading") })
    }

    @Test fun mismatchedHashWrongEpisodeAndMultipleSelectedFilesNeverUnrestrict() = runTest {
        for (bad in listOf(info("downloaded", 1, "f".repeat(40)), info("downloaded", 1, ep = 4), info("downloaded", 1, extraSelected = 1))) {
            val mutations = mutableListOf<String>()
            val resolver = RdSourceResolver({ path, form ->
                if (form != null) mutations += path
                if (path == "/torrents/addMagnet") """{"id":"ABC"}""" else bad
            }, {}, {})
            try { resolver.prepare(source, target) {}; fail("Unsafe torrent accepted") } catch (_: SourceUnavailable) {}
            assertEquals(listOf("/torrents/addMagnet"), mutations)
        }
    }

    @Test fun preparationStopsAfterBoundedPollingAndRetainsHonestPendingStatus() = runTest {
        var infoCalls = 0
        val waits = mutableListOf<Long>()
        val resolver = RdSourceResolver({ path, _ ->
            if (path == "/torrents/addMagnet") """{"id":"ABC"}""" else { infoCalls++; info("downloading") }
        }, {}, { waits += it })
        try { resolver.prepare(source, target) {}; fail("Unbounded wait") } catch (e: SourceUnavailable) { assertTrue(e.message!!.contains("still preparing")) }
        assertEquals(120, infoCalls)
        assertEquals(119, waits.size)
        assertTrue(waits.all { it >= 2000 })
    }

    @Test fun cancellationStopsBeforeAnotherAccountMutation() = runTest {
        var addCalls = 0
        var verifyCalls = 0
        val resolver = RdSourceResolver({ _, _ -> addCalls++; """{"id":"ABC"}""" }, {
            if (++verifyCalls == 2) throw CancellationException("Profile/account changed")
        }, {})
        try { resolver.prepare(source, target) {}; fail("Cancellation swallowed") } catch (_: CancellationException) {}
        assertEquals(1, addCalls)
    }

    @Test fun healthySelectedSourceCanFinishOnThirteenthPollWithoutReturningToStatus() = runTest {
        var polls = 0
        var now = 0L
        val resolver = RdSourceResolver({ path, _ ->
            when (path) {
                "/torrents/addMagnet" -> """{"id":"ABC"}"""
                "/torrents/info/ABC" -> { polls++; info(if (polls < 13) "downloading" else "downloaded", 1) }
                "/unrestrict/link" -> """{"download":"https://cdn.example.test/selected.mkv"}"""
                else -> error("Unexpected source request")
            }
        }, {}, { now += it }, { now })
        assertEquals("https://cdn.example.test/selected.mkv", resolver.prepare(source, target) {})
        assertEquals(13, polls)
    }

    @Test fun preparationDeadlineStopsBeforeAnotherRequestAfterEightMinutes() = runTest {
        var calls = 0
        var now = 0L
        val resolver = RdSourceResolver({ path, _ ->
            calls++
            if (path == "/torrents/addMagnet") """{"id":"ABC"}""" else info("downloading")
        }, {}, { now = 8 * 60_000L }, { now })
        try { resolver.prepare(source, target) {}; fail("Expired preparation continued") }
        catch (e: SourceUnavailable) { assertTrue(e.message!!.contains("still preparing")) }
        assertEquals(2, calls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancellingSelectedPreparationDuringWaitMakesNoFurtherProviderRequest() = runTest {
        var calls = 0
        var resolved = false
        val resolver = RdSourceResolver({ path, _ ->
            calls++
            if (path == "/torrents/addMagnet") """{"id":"ABC"}""" else info("downloading")
        }, {}, { awaitCancellation() }, { 0L })
        val job = launch { resolver.prepare(source, target) {}; resolved = true }
        runCurrent()
        assertEquals(2, calls)
        job.cancelAndJoin()
        runCurrent()
        assertEquals(2, calls)
        assertFalse(resolved)
    }

    @Test fun finalUnrestrictResponseCrossingDeadlineNeverReturnsAPlayableAddress() = runTest {
        var now = 0L
        var returned = false
        val resolver = RdSourceResolver({ path, _ ->
            when (path) {
                "/torrents/addMagnet" -> """{"id":"ABC"}"""
                "/torrents/info/ABC" -> info("downloaded", 1)
                "/unrestrict/link" -> { now = 8 * 60_000L; """{"download":"https://cdn.example.test/late.mkv"}""" }
                else -> error("Unexpected source request")
            }
        }, {}, {}, { now })
        try { resolver.prepare(source, target) {}; returned = true }
        catch (e: SourceUnavailable) { assertTrue(e.message!!.contains("still preparing")) }
        assertFalse("A late final response must not reach player dispatch", returned)
    }

    @Test fun finalValidationCrossingDeadlineCannotLaunchEvenAfterResolutionSucceeds() = runTest {
        var now = 0L
        var returned = false
        val resolver = RdSourceResolver({ path, _ ->
            when (path) {
                "/torrents/addMagnet" -> """{"id":"ABC"}"""
                "/torrents/info/ABC" -> info("downloaded", 1)
                "/unrestrict/link" -> """{"download":"https://cdn.example.test/selected.mkv"}"""
                else -> error("Unexpected source request")
            }
        }, {}, {}, { now })
        try {
            resolver.prepare(source, target, {}, {}, beforeReturn = { now = 8 * 60_000L })
            returned = true
        } catch (e: SourceUnavailable) { assertTrue(e.message!!.contains("still preparing")) }
        assertFalse("Final account/catalog validation must remain within the same deadline", returned)
    }

    @Test fun invalidIdentityNeverAddsTorrent() = runTest {
        var calls = 0
        val resolver = RdSourceResolver({ _, _ -> calls++; "{}" }, {}, {})
        try { resolver.prepare(source, target.copy(title = target.title.copy(imdb = null))) {}; fail("Missing IMDb accepted") } catch (_: SourceUnavailable) {}
        assertEquals(0, calls)
    }
}
