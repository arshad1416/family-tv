package app.familytv

import app.familytv.discovery.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RdAccountReuseTest {
    private val hash = "0123456789abcdef0123456789abcdef01234567"
    private val source = SourceCandidate(hash, 1, "Northern Lights S02E03 1080p x264", true)
    private val target = DiscoveryTarget(DiscoveryTitle("tv", "42", "Northern Lights", imdb = "tt1234567"), 2, 3)
    private val listPath = "/torrents?limit=20"
    private val ready = """[{"id":"READY","hash":"$hash","status":"downloaded"}]"""
    private val download = "https://cdn.example.test/selected.mkv"
    private val link = "https://real-debrid.com/d/selected"
    private val filename = "Northern.Lights.S02E03.mkv"
    private fun info(selected: Int = 1, extra: Int = 0, links: String = "\"$link\"", returnedHash: String = hash, episode: Int = 3, state: String = "downloaded") = """{
        "hash":"$returnedHash","filename":"Northern Lights S02","status":"$state",
        "files":[{"id":1,"path":"Northern.Lights.S02E02.mkv","bytes":100,"selected":$extra},
                 {"id":2,"path":"Northern.Lights.S02E0$episode.mkv","bytes":200,"selected":$selected}],"links":[$links]}"""
    private fun resolved(metadata: String = "") = """{"download":"$download"$metadata}"""

    @Test fun readyReuseMakesOnlyLookupInfoAndUnrestrictRequests() = runTest {
        val requests = mutableListOf<Pair<String, Map<String, String>?>>()
        val stages = mutableListOf<String>()
        val resolver = RdSourceResolver({ path, form ->
            requests += path to form
            when (path) { listPath -> ready; "/torrents/info/READY" -> info(); "/unrestrict/link" -> resolved(); else -> error("Unexpected request") }
        }, {}, {})
        assertEquals(download, resolver.prepare(source, target, stages::add))
        assertEquals(listOf(listPath, "/torrents/info/READY", "/unrestrict/link"), requests.map { it.first })
        assertEquals(listOf(null, null, mapOf("link" to link)), requests.map { it.second })
        assertTrue(stages.any { it.contains("existing account") })
        assertTrue(stages.any { it.contains("Reusing") })
    }

    @Test fun pendingAndTargetUnselectedCandidatesFallBackWithoutChangingExistingSelection() = runTest {
        for ((summary, existingInfo) in listOf(
            ready.replace("downloaded", "downloading") to null,
            ready to info(selected = 0),
            ready to info(state = "downloading"),
            ready.replace(hash, "f".repeat(40)) to null)) {
            val requests = mutableListOf<String>()
            val resolver = RdSourceResolver({ path, _ ->
                requests += path
                when (path) {
                    listPath -> summary
                    "/torrents/info/READY" -> existingInfo ?: error("Pending/unmatched summary must not be inspected")
                    "/torrents/addMagnet" -> """{"id":"NEW"}"""
                    "/torrents/info/NEW" -> info()
                    "/unrestrict/link" -> resolved()
                    else -> error("Existing torrent was mutated")
                }
            }, {}, {})
            assertEquals(download, resolver.prepare(source, target) {})
            assertTrue(requests.contains("/torrents/addMagnet"))
            assertFalse(requests.any { it.contains("selectFiles") || it.contains("delete") })
        }
    }

    @Test fun terminalStatusOnFreshExistingInfoStopsWithoutReplacementAdd() = runTest {
        for (state in listOf("error", "virus", "magnet_error", "dead")) {
            val calls = mutableListOf<String>()
            val resolver = RdSourceResolver({ path, _ ->
                calls += path
                when (path) {
                    listPath -> ready
                    "/torrents/info/READY" -> info(state = state)
                    "/torrents/addMagnet" -> "{}"
                    else -> error("Unexpected request")
                }
            }, {}, {})
            try { resolver.prepare(source, target) {}; fail("Terminal source accepted") } catch (_: SourceUnavailable) {}
            assertEquals(listOf(listPath, "/torrents/info/READY"), calls)
        }
    }

    @Test fun boundedTwentySummaryLookupOnlyInspectsTheExactReadyHash() = runTest {
        val others = (1..19).joinToString(",") { """{"id":"OTHER$it","hash":"${"f".repeat(40)}","status":"downloaded"}""" }
        val calls = mutableListOf<String>()
        val resolver = RdSourceResolver({ path, _ -> calls += path; when (path) {
            listPath -> "[" + others + "," + ready.drop(1).dropLast(1) + "]"
            "/torrents/info/READY" -> info()
            "/unrestrict/link" -> resolved()
            else -> error("Unselected account source was inspected")
        } }, {}, {})
        assertEquals(download, resolver.prepare(source, target) {})
        assertEquals(listOf(listPath, "/torrents/info/READY", "/unrestrict/link"), calls)
    }

    @Test fun reusedMoviesStillRequireTheExactTitleAndYear() = runTest {
        val movie = DiscoveryTarget(DiscoveryTitle("movie", "42", "Open Film", 1999, imdb = "tt1234567"))
        val movieSource = source.copy(fileIdx = 0)
        for (name in listOf("Open.Film.2024.mkv", "Wrong.Film.1999.mkv", "Open.Film.S01.1999.mkv")) {
            val calls = mutableListOf<String>()
            val resolver = RdSourceResolver({ path, _ -> calls += path; when (path) {
                listPath -> ready
                "/torrents/info/READY" -> """{"hash":"$hash","filename":"Open Film 1999","status":"downloaded","files":[{"id":1,"path":"$name","bytes":100,"selected":1}],"links":["$link"]}"""
                else -> error("Unsafe movie reuse followup")
            } }, {}, {})
            try { resolver.prepare(movieSource, movie) {}; fail("Wrong movie accepted") } catch (_: SourceUnavailable) {}
            assertEquals(listOf(listPath, "/torrents/info/READY"), calls)
        }
    }

    @Test fun fallbackAddCannotSelectFilesOnAnIdAlreadyPresentInTheAccountList() = runTest {
        val calls = mutableListOf<String>()
        val resolver = RdSourceResolver({ path, _ ->
            calls += path
            when (path) {
                listPath -> ready.replace("downloaded", "downloading")
                "/torrents/addMagnet" -> """{"id":"READY"}"""
                "/torrents/info/READY" -> info(selected = 0, state = "waiting_files_selection")
                "/torrents/selectFiles/READY" -> ""
                else -> error("Unexpected request")
            }
        }, {}, {})
        try { resolver.prepare(source, target) {}; fail("Existing account ID accepted as new") } catch (_: SourceUnavailable) {}
        assertEquals(listOf(listPath, "/torrents/addMagnet"), calls)
    }

    @Test fun wrongHashWrongEpisodeCombinedEpisodeAndAmbiguousIdentityStopWithoutFallback() = runTest {
        for (bad in listOf(info(returnedHash = "f".repeat(40)), info(episode = 4), info().replace(filename, "Northern.Lights.S02E03E04.mkv"), info().replace("Northern.Lights.S02E03.mkv", "Other.Show.S02E03.mkv"))) {
            val requests = mutableListOf<String>()
            val resolver = RdSourceResolver({ path, _ -> requests += path; when (path) { listPath -> ready; "/torrents/info/READY" -> bad; else -> error("Unsafe fallback") } }, {}, {})
            try { resolver.prepare(source, target) {}; fail("Unsafe reuse accepted") } catch (_: SourceUnavailable) {}
            assertEquals(listOf(listPath, "/torrents/info/READY"), requests)
        }
    }

    @Test fun malformedOrOversizedRecentListsStopBeforeInfoOrMutationWithoutReflectingContents() = runTest {
        val secret = "Bearer synthetic-secret https://secret.test/key"
        val entries = (1..21).joinToString(",") { """{"id":"A$it","hash":"$hash","status":"downloaded"}""" }
        for (bad in listOf("{}", "null", "not json $secret", "[null]", ready.replace('"', '\''), ready.replace("\"id\"", "id"), "[\"$secret\"]", "[{\"id\":{\"key\":\"$secret\"}}]", ready.replace("READY", "bad/id"), ready.replace(hash, "wrong"), ready.replace("\"downloaded\"", "{}"), "[$entries]", "[" + ready.drop(1).dropLast(1) + "," + ready.drop(1).dropLast(1) + "]", " ".repeat(1_048_577) + "[]")) {
            var calls = 0
            val resolver = RdSourceResolver({ _, _ -> calls++; bad }, {}, {})
            try { resolver.prepare(source, target) {}; fail("Malformed list accepted") }
            catch (e: SourceUnavailable) { assertFalse(e.message.orEmpty().contains("synthetic-secret")); assertFalse(e.message.orEmpty().contains("https://")) }
            assertEquals(1, calls)
        }
    }

    @Test fun malformedReadyInfoStopsWithoutMutationOrSecretReflection() = runTest {
        for (bad in listOf("[]", "null", "{", info().replace("\"$hash\"", "{}"), info().replace("\"status\":\"downloaded\"", "status:'downloaded'"), info().replace("\"downloaded\"", "{}"), info().replace("\"files\":[", "\"files\":{},\"unused\":["), info().replace("\"bytes\":200", "\"bytes\":\"Bearer synthetic-secret https://private.test/key\""), info().replace("\"selected\":1", "\"selected\":true"), info().replace("\"links\":[", "\"links\":{},\"unused\":["))) {
            val calls = mutableListOf<String>()
            val resolver = RdSourceResolver({ path, _ -> calls += path; if (path == listPath) ready else bad }, {}, {})
            try { resolver.prepare(source, target) {}; fail("Malformed info accepted") }
            catch (e: SourceUnavailable) { assertFalse(e.message.orEmpty().contains("synthetic-secret")); assertFalse(e.message.orEmpty().contains("https://")) }
            assertEquals(listOf(listPath, "/torrents/info/READY"), calls)
        }
    }

    @Test fun mixedPathSeparatorsStillRequireTheExactSelectedBasename() = runTest {
        val resolver = RdSourceResolver({ path, _ -> when (path) {
            listPath -> ready
            "/torrents/info/READY" -> info(extra = 1, links = "\"https://real-debrid.com/d/other\",\"$link\"").replace(filename, "pack\\\\$filename")
            "/unrestrict/link" -> resolved(",\"filename\":\"$filename\",\"filesize\":200")
            else -> error("Unexpected request")
        } }, {}, {})
        assertEquals(download, resolver.prepare(source, target) {})
    }

    @Test fun multipleSelectedFilesUseOnlyVerifiedTargetPositionAndRequireExactNameAndSize() = runTest {
        val calls = mutableListOf<Pair<String, Map<String, String>?>>()
        val resolver = RdSourceResolver({ path, form ->
            calls += path to form
            when (path) {
                listPath -> ready
                "/torrents/info/READY" -> info(extra = 1, links = "\"https://real-debrid.com/d/other\",\"$link\"")
                "/unrestrict/link" -> resolved(",\"filename\":\"$filename\",\"filesize\":200")
                else -> error("Unexpected request")
            }
        }, {}, {})
        assertEquals(download, resolver.prepare(source, target) {})
        assertEquals(mapOf("link" to link), calls.last().second)
    }

    @Test fun ambiguousLinkCountsAndMissingOrMismatchedUnrestrictProofNeverTryAnotherLinkOrAdd() = runTest {
        for ((links, metadata) in listOf(
            "\"$link\"" to "",
            "\"https://real-debrid.com/d/other\",\"$link\",\"https://real-debrid.com/d/extra\"" to "",
            "\"https://real-debrid.com/d/other\",\"$link\"" to "",
            "\"https://real-debrid.com/d/other\",\"$link\"" to ",\"filename\":\"$filename\"",
            "\"https://real-debrid.com/d/other\",\"$link\"" to ",\"filename\":\"wrong.mkv\",\"filesize\":200",
            "\"https://real-debrid.com/d/other\",\"$link\"" to ",\"filename\":\"$filename\",\"filesize\":201",
            "\"https://real-debrid.com/d/other\",\"$link\"" to ",\"filename\":\"$filename\",\"filesize\":0",
            "\"https://real-debrid.com/d/other\",\"$link\"" to ",\"filename\":\"$filename\",\"filesize\":\"200\"",
            "\"https://real-debrid.com/d/other\",\"$link\"" to ",\"filename\":{},\"filesize\":200")) {
            val calls = mutableListOf<String>()
            val resolver = RdSourceResolver({ path, _ -> calls += path; when (path) { listPath -> ready; "/torrents/info/READY" -> info(extra = 1, links = links); "/unrestrict/link" -> resolved(metadata); else -> error("Unsafe followup") } }, {}, {})
            try { resolver.prepare(source, target) {}; fail("Ambiguous link proof accepted") } catch (_: SourceUnavailable) {}
            assertTrue(calls.size in 2..3)
            assertEquals(calls.size, calls.distinct().size)
            assertFalse(calls.any { it.contains("addMagnet") || it.contains("selectFiles") })
        }
    }

    @Test fun cancellationAndVerificationFailureAtEveryReuseBoundaryStopAllFollowingRequests() = runTest {
        for (cancel in listOf(false, true)) for (boundary in 1..6) {
            val calls = mutableListOf<String>()
            var verifies = 0
            val resolver = RdSourceResolver({ path, _ -> calls += path; when (path) { listPath -> ready; "/torrents/info/READY" -> info(); "/unrestrict/link" -> resolved(); else -> error("Unexpected request") } }, {
                if (++verifies == boundary) {
                    if (cancel) throw CancellationException("Cancelled") else throw SourceUnavailable("Profile changed")
                }
            }, {})
            try { resolver.prepare(source, target) {}; fail("Stale/cancelled attempt resolved") } catch (_: SourceUnavailable) {} catch (_: CancellationException) {}
            assertEquals(boundary / 2, calls.size)
        }
    }

    @Test fun everyAttemptRechecksAccountAndFinalVerificationRunsAfterCallerValidation() = runTest {
        val calls = mutableListOf<String>()
        var invalid = false
        val resolver = RdSourceResolver({ path, _ -> calls += path; when (path) { listPath -> ready; "/torrents/info/READY" -> info(); "/unrestrict/link" -> resolved(); else -> error("Unexpected request") } }, { if (invalid) throw SourceUnavailable("Changed") }, {})
        repeat(2) { assertEquals(download, resolver.prepare(source, target) {}) }
        assertEquals(2, calls.count { it == listPath })
        try { resolver.prepare(source, target, {}, {}, beforeReturn = { invalid = true }); fail("Final change accepted") } catch (_: SourceUnavailable) {}
    }
}
