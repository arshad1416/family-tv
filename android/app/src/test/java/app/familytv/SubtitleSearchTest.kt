package app.familytv

import app.familytv.subs.MediaRef
import app.familytv.subs.OpenSubtitles
import app.familytv.subs.SubtitleException
import app.familytv.subs.SubtitleHit
import app.familytv.subs.SubtitleRequest
import app.familytv.subs.SubtitleSearch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale

class SubtitleSearchTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun cleansProviderTitles() {
        assertEquals(SubtitleSearch.CleanTitle("The Matrix", 1999), SubtitleSearch.cleanTitle("EN - The Matrix (1999) [4K]"))
        assertEquals(SubtitleSearch.CleanTitle("Dune Part Two", 2024), SubtitleSearch.cleanTitle("|EN| Dune Part Two 2024"))
        assertEquals(SubtitleSearch.CleanTitle("Bluey", null), SubtitleSearch.cleanTitle("UK: Bluey FHD"))
        assertEquals(SubtitleSearch.CleanTitle("Inception", 2010), SubtitleSearch.cleanTitle("[MULTI] |4K| Inception (2010)"))
        assertEquals(SubtitleSearch.CleanTitle("The Office", null), SubtitleSearch.cleanTitle("The Office S02E03 The Dundies"))
        // Titles that look like prefixes stay intact.
        assertEquals(SubtitleSearch.CleanTitle("CSI: Miami", null), SubtitleSearch.cleanTitle("CSI: Miami"))
        assertEquals(SubtitleSearch.CleanTitle("1917", null), SubtitleSearch.cleanTitle("1917"))
    }

    @Test
    fun normalisesIdsAndLanguages() {
        assertEquals("133093", SubtitleSearch.imdbNumber("tt0133093"))
        assertNull(SubtitleSearch.imdbNumber("tt"))
        assertNull(SubtitleSearch.imdbNumber("abc"))
        assertEquals("603", SubtitleSearch.tmdbNumber("603"))
        assertNull(SubtitleSearch.tmdbNumber("0"))
        assertEquals("pt-br", SubtitleSearch.languageCode(Locale("pt", "BR")))
        assertEquals("zh-tw", SubtitleSearch.languageCode(Locale("zh", "TW")))
        assertEquals("he", SubtitleSearch.languageCode(Locale("iw")))
        assertEquals("fr", SubtitleSearch.languageCode(Locale.CANADA_FRENCH))
        assertEquals(2019, SubtitleSearch.year("2019-05-31"))
    }

    @Test
    fun movieQueriesTryIdsThenTitle() {
        val q = SubtitleSearch.queries(SubtitleRequest(episode = false, title = "EN - The Matrix (1999)", tmdbId = "603", imdbId = "tt0133093", languages = listOf("fr", "en", "EN")))
        assertEquals(
            listOf(
                listOf("languages" to "en,fr", "tmdb_id" to "603"),
                listOf("imdb_id" to "133093", "languages" to "en,fr"),
                listOf("languages" to "en,fr", "query" to "the matrix", "type" to "movie", "year" to "1999"),
            ),
            q,
        )
    }

    @Test
    fun episodeQueriesUseTheSeriesIdWithSeasonAndEpisode() {
        val q = SubtitleSearch.queries(SubtitleRequest(episode = true, title = "Game of Thrones", tmdbId = "1399", season = 3, episodeNumber = 9))
        assertEquals(listOf("episode_number" to "9", "languages" to "en", "parent_tmdb_id" to "1399", "season_number" to "3"), q[0])
        assertEquals(listOf("episode_number" to "9", "languages" to "en", "query" to "game of thrones", "season_number" to "3", "type" to "episode"), q[1])
        assertEquals(2, q.size)
    }

    @Test
    fun mediaRefBuildsTheRequest() {
        val r = MediaRef(episode = true, title = "Show", tmdbId = "5", season = 1, episodeNumber = 2).request(listOf("en"))
        assertEquals(SubtitleRequest(true, "Show", null, "5", null, 1, 2, listOf("en")), r)
    }

    private fun hit(id: Long, lang: String = "en", downloads: Int = 0, machine: Boolean = false, trusted: Boolean = false, season: Int? = null, episode: Int? = null, year: Int? = null) =
        SubtitleHit(id, lang, "r$id", downloads, false, machine, trusted, season, episode, year, false)

    @Test
    fun rankingPrefersLanguageThenHumanThenDownloads() {
        val r = SubtitleRequest(episode = false, title = "Film", year = 2020, languages = listOf("fr", "en"))
        val ranked = SubtitleSearch.rank(
            listOf(hit(1, "en", 900), hit(2, "fr", 10, machine = true), hit(3, "fr", 5), hit(4, "de", 9999), hit(5, "fr", 50, year = 2005), hit(3, "fr", 5)),
            r,
        )
        assertEquals(listOf(3L, 2L, 1L, 4L), ranked.map { it.fileId }) // 5 is another year; the duplicate 3 is dropped
    }

    @Test
    fun rankingDropsOtherEpisodes() {
        val r = SubtitleRequest(episode = true, title = "Show", season = 2, episodeNumber = 5)
        val ranked = SubtitleSearch.rank(listOf(hit(1, season = 2, episode = 5), hit(2, season = 2, episode = 6), hit(3, season = 1, episode = 5), hit(4)), r)
        assertEquals(listOf(1L, 4L), ranked.map { it.fileId })
    }

    @Test
    fun parsesSearchResults() {
        val hits = SubtitleSearch.parse(SEARCH_JSON)
        assertEquals(2, hits.size)
        with(hits[0]) {
            assertEquals(7001L, fileId)
            assertEquals("en", language)
            assertEquals("The.Matrix.1999.1080p.BluRay", release)
            assertEquals(12345, downloads)
            assertTrue(trusted)
            assertEquals(1999, year)
        }
        assertTrue(hits[1].machineTranslated)
        assertTrue(SubtitleSearch.parse("not json").isEmpty())
        assertTrue(SubtitleSearch.parse("""{"data":[{"attributes":{"files":[]}}]}""").isEmpty())
    }

    @Test
    fun clientSearchesWithKeyAndDownloadsSrt() = runTest {
        val server = MockWebServer()
        val seen = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.path.orEmpty()
                return when {
                    // The id search finds nothing; the title fallback does.
                    path.startsWith("/api/v1/subtitles") && path.contains("tmdb_id") -> MockResponse().setBody("""{"data":[]}""")
                    path.startsWith("/api/v1/subtitles") -> MockResponse().setBody(SEARCH_JSON)
                    path == "/api/v1/download" -> MockResponse().setBody("""{"link":"${server.url("/file/7001.srt")}","remaining":4}""")
                    path == "/file/7001.srt" -> MockResponse().setBody("1\n00:00:01,000 --> 00:00:02,000\nHello\n")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val os = OpenSubtitles("KEY", "FamilyTV v1", OkHttpClient(), server.url("/api/v1").toString())
            val hits = os.search(SubtitleRequest(episode = false, title = "The Matrix (1999)", tmdbId = "603"))
            assertEquals(7001L, hits.first().fileId)
            assertTrue(seen[0].path!!.contains("languages=en&tmdb_id=603"))
            assertTrue(seen[1].path!!.contains("query=the%20matrix"))
            assertEquals("KEY", seen[0].getHeader("Api-Key"))
            assertEquals("FamilyTV v1", seen[0].getHeader("User-Agent"))

            val file = os.download(hits.first(), tmp.newFolder("subs"))
            assertTrue(file.readText().contains("Hello"))
            val dl = seen.first { it.path == "/api/v1/download" }
            assertEquals("POST", dl.method)
            assertEquals("""{"file_id":7001,"sub_format":"srt"}""", dl.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun clientExplainsQuotaErrors() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(406).setBody("""{"message":"You have downloaded your allowed 5 subtitles for 24h"}"""))
        server.start()
        try {
            val os = OpenSubtitles("KEY", "FamilyTV v1", OkHttpClient(), server.url("/api/v1").toString())
            os.download(hit(1), tmp.newFolder("q"))
            fail("expected a quota error")
        } catch (e: SubtitleException) {
            assertTrue(e.message!!.contains("allowed 5"))
        } finally {
            server.shutdown()
        }
    }

    private companion object {
        val SEARCH_JSON = """{"total_count":2,"data":[
            {"id":"1","type":"subtitle","attributes":{"language":"en","download_count":12345,"hearing_impaired":false,"from_trusted":true,
              "machine_translated":false,"ai_translated":false,"release":"The.Matrix.1999.1080p.BluRay","moviehash_match":false,
              "feature_details":{"year":1999,"title":"The Matrix","imdb_id":133093,"tmdb_id":603},
              "files":[{"file_id":7001,"file_name":"The.Matrix.srt"}]}},
            {"id":"2","type":"subtitle","attributes":{"language":"en","download_count":3,"ai_translated":true,
              "feature_details":{"year":1999},"files":[{"file_id":7002,"file_name":"x.srt"}]}}
        ]}"""
    }
}
