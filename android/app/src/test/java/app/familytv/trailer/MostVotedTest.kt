package app.familytv.trailer

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** TMDB's most-voted lists: fetched page by page, kept on disk, fetched again only after a week. */
class MostVotedTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var tmdb: Tmdb
    @Volatile private var failing = false

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (failing) return MockResponse().setResponseCode(500)
                val url = request.requestUrl!!
                assertEquals("vote_count.desc", url.queryParameter("sort_by"))
                assertEquals("false", url.queryParameter("include_adult"))
                val results = when (url.encodedPath to url.queryParameter("page")) {
                    "/3/discover/movie" to "1" -> """{"id":14160,"title":"Up","release_date":"2009-05-28","vote_count":20000},{"id":1,"title":"X","adult":true,"vote_count":9}"""
                    "/3/discover/movie" to "2" -> """{"id":22947,"title":"Up in the Air","release_date":"2009-09-05","vote_count":4800}"""
                    "/3/discover/tv" to "1" -> """{"id":1396,"name":"Breaking Bad","first_air_date":"2008-01-20","vote_count":15000}"""
                    else -> ""
                }
                return MockResponse().setBody("""{"results":[$results]}""")
            }
        }
        server.start()
        tmdb = Tmdb("key", OkHttpClient(), server.url("/3").toString())
    }

    @After
    fun stop() = server.shutdown()

    @Test
    fun fetchesKeepsAndWaitsAWeek() = runBlocking {
        val file = tmp.root.resolve("most_voted.json")
        val day = 24 * 3_600_000L
        val first = MostVoted(file)
        assertNull(first.ready())
        first.refreshIfDue(tmdb, now = 10 * day)
        // Movies: pages 1 to 3 (3 is empty, so it stops); shows: pages 1 and 2.
        assertEquals(5, server.requestCount)
        val lists = first.lists.value!!
        assertEquals(20_000, lists.of(tv = false).votes(emptyList(), "Up", 2009))
        assertEquals(4_800, lists.of(tv = false).votes(emptyList(), "Up in the Air", null))
        assertNull(lists.of(tv = false).votes(emptyList(), "X", null))
        assertEquals(15_000, lists.of(tv = true).votes(listOf("1396"), "Breaking Bad (US)", null))

        // The next start reads them from disk and asks TMDB for nothing within the week.
        val next = MostVoted(file)
        next.refreshIfDue(tmdb, now = 16 * day)
        assertEquals(5, server.requestCount)
        assertEquals(15_000, next.lists.value!!.of(tv = true).votes(emptyList(), "Breaking Bad", 2008))

        // After a week it fetches again; a failed fetch keeps the lists it had.
        failing = true
        next.refreshIfDue(tmdb, now = 18 * day)
        assertEquals(6, server.requestCount)
        assertEquals(20_000, next.lists.value!!.of(tv = false).votes(emptyList(), "Up", null))
        assertEquals(10 * day, MostVoted(file).ready()!!.at)
    }

    @Test
    fun listsFromAShorterFetchAreFetchedAgainAtOnce() = runBlocking {
        val file = tmp.root.resolve("most_voted.json")
        // As 0.2.x before Oct 27 kept them: 50 pages, no page count in the file.
        file.writeText("""{"at":1000,"movies":[["22947","Up in the Air",2009,4800]],"shows":[]}""")
        val lists = MostVoted(file)
        assertEquals(0, lists.ready()!!.pages)
        lists.refreshIfDue(tmdb, now = 2000)
        assertEquals(5, server.requestCount)
        assertEquals(MostVoted.PAGES, MostVoted(file).ready()!!.pages)
    }
}
