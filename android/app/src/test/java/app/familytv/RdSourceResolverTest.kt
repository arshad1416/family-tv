package app.familytv

import app.familytv.discovery.*
import kotlinx.coroutines.CancellationException
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
        try { resolver.prepare(source, target) {}; fail("Unbounded wait") } catch (e: SourceUnavailable) { assertTrue(e.message!!.contains("still downloading")) }
        assertEquals(12, infoCalls)
        assertEquals(11, waits.size)
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

    @Test fun invalidIdentityNeverAddsTorrent() = runTest {
        var calls = 0
        val resolver = RdSourceResolver({ _, _ -> calls++; "{}" }, {}, {})
        try { resolver.prepare(source, target.copy(title = target.title.copy(imdb = null))) {}; fail("Missing IMDb accepted") } catch (_: SourceUnavailable) {}
        assertEquals(0, calls)
    }
}
