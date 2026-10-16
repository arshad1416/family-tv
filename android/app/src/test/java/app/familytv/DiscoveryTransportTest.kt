package app.familytv

import app.familytv.discovery.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class DiscoveryTransportTest {
    private val hash = "0123456789abcdef0123456789abcdef01234567"

    @Test fun discoveryDoesNotMutateRdOrSendCredentialsToTorrentio() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"results":[{"id":42,"media_type":"movie","title":"Open Film","adult":false}]}"""))
            server.enqueue(MockResponse().setBody("""{"id":42,"title":"Open Film","adult":false,"external_ids":{"imdb_id":"tt1234567"}}"""))
            server.enqueue(MockResponse().setBody("""{"streams":[{"infoHash":"$hash","fileIdx":0,"title":"Open.Film.1080p.x264.mkv"}]}"""))
            val c = DiscoveryClient(OkHttpClient(), "synthetic-tmdb-key", server.url("/tmdb").toString(), server.url("/torrentio").toString())
            val title = c.details(c.search("Open Film").single())
            assertEquals(1, c.sources(DiscoveryTarget(title)).size)
            val search = server.takeRequest()
            val details = server.takeRequest()
            val streams = server.takeRequest()
            assertTrue(search.path!!.contains("include_adult=false"))
            assertTrue(details.path!!.startsWith("/tmdb/movie/42"))
            assertEquals("/torrentio/stream/movie/tt1234567.json", streams.path)
            assertNull(streams.getHeader("Authorization"))
            assertFalse(streams.path!!.contains("synthetic-tmdb-key"))
            assertEquals(listOf("GET", "GET", "GET"), listOf(search.method, details.method, streams.method))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun seriesRequestUsesShowImdbAndExplicitSeasonEpisode() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"streams":[]}"""))
            val c = DiscoveryClient(OkHttpClient(), "key", torrentioBase = server.url("/").toString().trimEnd('/'))
            c.sources(DiscoveryTarget(DiscoveryTitle("tv", "42", "Open Series", imdb = "tt1234567"), 3, 7))
            assertEquals("/stream/series/tt1234567:3:7.json", server.takeRequest().path)
        }
    }

    @Test fun redirectsNeverForwardBearerAndOversizeResponsesFailClosed() = runTest {
        MockWebServer().use { origin -> MockWebServer().use { destination ->
            origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/stolen")))
            val http = DiscoveryHttp(OkHttpClient())
            val result = http.request(Request.Builder().url(origin.url("/")).header("Authorization", "Bearer synthetic-secret").build(), "Test")
            assertEquals(302, result.first)
            assertNull(destination.takeRequest(100, TimeUnit.MILLISECONDS))
            origin.enqueue(MockResponse().setBody("x".repeat(1_048_577)))
            try { http.request(Request.Builder().url(origin.url("/large")).build(), "Test"); fail("Oversized body accepted") } catch (e: SourceUnavailable) { assertFalse(e.message!!.contains("synthetic-secret")) }
        } }
    }

    @Test fun torrentio429AppliesCooldownWithoutRepeatedRequests() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429))
            val c = DiscoveryClient(OkHttpClient(), "key", torrentioBase = server.url("/").toString())
            val target = DiscoveryTarget(DiscoveryTitle("movie", "42", "Open Film", imdb = "tt1234567"))
            repeat(2) { try { c.sources(target); fail("Rate limit ignored") } catch (_: SourceUnavailable) {} }
            assertEquals(1, server.requestCount)
        }
    }
}
