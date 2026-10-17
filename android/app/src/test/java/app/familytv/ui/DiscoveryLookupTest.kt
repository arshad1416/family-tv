package app.familytv.ui

import app.familytv.discovery.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Drive the production screen orchestration with real HTTP parsing and delayed response boundaries. */
class DiscoveryLookupTest {
    private val movie = DiscoveryTitle("movie", "42", "Open Film", 2024)
    private val show = DiscoveryTitle("tv", "42", "Open Show", seasons = listOf(1))
    private val initial = DiscoveryIdentity("profile-a", "original-pin-and-hidden-policy", "device")

    @Test fun changedProfileAfterCatalogMetadataCannotContactTorrentio() = runTest {
        for (changed in listOf(initial.copy(profileId = "profile-b"), initial.copy(policy = "changed-pin-or-hidden-policy"))) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"id":42,"title":"Open Film","external_ids":{"imdb_id":"tt1234567"}}"""))
                val client = DiscoveryClient(OkHttpClient(), "fixture", server.url("/tmdb").toString(), server.url("/torrentio").toString())
                var identity = initial
                val session = DiscoverySession(initial) { identity }
                val arrived = CompletableDeferred<Unit>()
                val resume = CompletableDeferred<Unit>()
                val result = async {
                    runCatching {
                        catalogReleaseLookup(metadata = {
                            val parsed = client.details(movie)
                            arrived.complete(Unit); resume.await(); parsed
                        }, allowed = { true }, target = { DiscoveryTarget(it) }, verify = session::verify, sources = client::sources)
                    }
                }
                arrived.await(); identity = changed; resume.complete(Unit)
                assertTrue(result.await().exceptionOrNull() is SourceUnavailable)
                assertEquals("Stale profile must not request Torrentio", 1, server.requestCount)
                assertTrue(server.takeRequest().path!!.startsWith("/tmdb/movie/42"))
            }
        }
    }

    @Test fun changedSessionAfterEpisodeResponseCannotPublishRows() = runTest {
        for (changed in listOf(initial.copy(profileId = "profile-b"), initial.copy(policy = "changed-pin-or-hidden-policy"))) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"episodes":[{"season_number":1,"episode_number":1,"name":"Stale episode"}]}"""))
                val client = DiscoveryClient(OkHttpClient(), "fixture", server.url("/tmdb").toString())
                var identity = initial
                val session = DiscoverySession(initial) { identity }
                val arrived = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
                var published: List<DiscoveryEpisode>? = null
                val result = async {
                    runCatching {
                        loadVerifiedEpisodes(show, 1, fetch = { title, season ->
                            val parsed = client.episodes(title, season)
                            assertEquals(listOf(DiscoveryEpisode(1, 1, "Stale episode")), parsed)
                            arrived.complete(Unit); resume.await(); parsed
                        }, verify = session::verify, allowed = { true }, current = { true }, publish = { published = it })
                    }
                }
                arrived.await(); identity = changed; resume.complete(Unit)
                assertTrue(result.await().exceptionOrNull() is SourceUnavailable)
                assertNull(published)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun validEpisodeRowsPublishWhenSessionAndSeasonRemainCurrent() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"episodes":[{"season_number":1,"episode_number":1,"name":"Open episode"}]}"""))
            val client = DiscoveryClient(OkHttpClient(), "fixture", server.url("/tmdb").toString())
            val session = DiscoverySession(initial) { initial }
            var published: List<DiscoveryEpisode>? = null
            loadVerifiedEpisodes(show, 1, client::episodes, session::verify, { true }, { true }, { published = it })
            assertEquals(listOf(DiscoveryEpisode(1, 1, "Open episode")), published)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun newerSeasonSelectionCannotPublishEarlierEpisodes() = runTest {
        val arrived = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        var generation = 1
        var published: List<DiscoveryEpisode>? = null
        val result = async {
            runCatching {
                loadVerifiedEpisodes(show, 1, fetch = { _, _ ->
                    arrived.complete(Unit); resume.await(); listOf(DiscoveryEpisode(1, 1, "Old season"))
                }, verify = {}, allowed = { true }, current = { generation == 1 }, publish = { published = it })
            }
        }
        arrived.await(); generation++; resume.complete(Unit)
        assertTrue(result.await().exceptionOrNull() is SourceUnavailable)
        assertNull(published)
    }

    @Test fun cancellationAfterNonCooperativeVerificationCannotRequestSourcesOrPublishEpisodes() = runTest {
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        var requests = 0
        val job = launch {
            catalogReleaseLookup({ movie }, { true }, { DiscoveryTarget(it) }, verify = {
                withContext(NonCancellable) { entered.complete(Unit); resume.await() }
            }, sources = { requests++; emptyList() })
        }
        entered.await(); job.cancel(); resume.complete(Unit); job.join()
        assertEquals(0, requests)
        var published = false
        val arrived = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val episodes = launch {
            loadVerifiedEpisodes(show, 1, { _, _ ->
                withContext(NonCancellable) { arrived.complete(Unit); release.await() }
                listOf(DiscoveryEpisode(1, 1, "Late"))
            }, {}, { true }, { true }, { published = true })
        }
        arrived.await(); episodes.cancel(); release.complete(Unit); episodes.join()
        assertFalse(published)
    }
}
