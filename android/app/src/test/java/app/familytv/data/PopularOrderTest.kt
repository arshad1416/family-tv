package app.familytv.data

import app.familytv.trailer.Tmdb
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Search results ordered by TMDB's own search for the same words (round 5: copies alone got "up" and "break" wrong). */
class PopularOrderTest {
    private data class Title(val name: String, val year: Int? = null, val tmdb: String? = null)

    private lateinit var server: MockWebServer
    private lateinit var tmdb: Tmdb

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
        tmdb = Tmdb("key", OkHttpClient(), server.url("/3").toString())
    }

    @After
    fun stop() = server.shutdown()

    private fun answer(vararg results: String) =
        server.enqueue(MockResponse().setBody("""{"page":1,"results":[${results.joinToString(",")}]}"""))

    private fun film(id: Int, title: String, date: String) = """{"id":$id,"title":"$title","release_date":"$date","adult":false}"""
    private fun show(id: Int, name: String, date: String) = """{"id":$id,"name":"$name","first_air_date":"$date","adult":false}"""

    private fun order(q: String, local: List<Title>, tv: Boolean): List<String> {
        val hits = runBlocking { tmdb.search(q, tv) }
        return PopularOrder.apply(local, q, hits, { it.name }, { it.year }, { listOfNotNull(it.tmdb) }).map { it.name }
    }

    @Test
    fun upPutsTheWellKnownFilmsFirst() {
        answer(
            film(14160, "Up", "2009-05-28"), film(22947, "Up in the Air", "2009-09-05"), film(440472, "The Upside", "2017-09-08"),
            film(500664, "Upgrade", "2018-03-15"), film(11963, "Up in Smoke", "1978-09-15"), film(1, "Adult film", "2020-01-01").replace("false", "true"),
        )
        // The line's order (copies first) as the Kitchen showed it.
        val local = listOf(
            Title("Up", 2009), Title("The Upside", 2017), Title("Upgrade", 2018), Title("Upgraded", 2024), Title("Up in Smoke", 1978),
            Title("Uprising", 2001), Title("Up in the Air", 2009), Title("What's Up, Doc?", 1972), Title("Superman", 1978),
        )
        assertEquals(
            listOf("Up", "Up in the Air", "The Upside", "Upgrade", "Up in Smoke", "Upgraded", "Uprising", "What's Up, Doc?", "Superman"),
            order("up", local, tv = false),
        )
        val sent = server.takeRequest()
        assertEquals("/3/search/movie", sent.requestUrl?.encodedPath)
        assertEquals("up", sent.requestUrl?.queryParameter("query"))
    }

    @Test
    fun breakPutsBreakingBadFirstAndAWholeWordLaterInTheTitleCounts() {
        answer(show(1396, "Breaking Bad", "2008-01-20"), show(2288, "Prison Break", "2005-08-29"), show(5, "Break Point", "2023-01-13"))
        val local = listOf(Title("Break Point"), Title("BREAK IT ALL"), Title("Break Clause"), Title("Breaking Bad", tmdb = "1396"), Title("Breakout Kings"), Title("Prison Break"))
        assertEquals(
            listOf("Breaking Bad", "Prison Break", "Break Point", "BREAK IT ALL", "Break Clause", "Breakout Kings"),
            order("break", local, tv = true),
        )
        assertEquals("/3/search/tv", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun yearsTellRemakesApartAndNoAnswerKeepsTheLinesOrder() {
        answer(film(438631, "Dune", "2021-09-15"), film(841, "Dune", "1984-12-14"))
        val hits = runBlocking { tmdb.search("dune", false) }
        val dunes = listOf(Title("Dune", 1984), Title("Dune", 2021))
        assertEquals(listOf(2021, 1984), PopularOrder.apply(dunes, "dune", hits, { it.name }, { it.year }, { emptyList() }).map { it.year })
        // Offline or without a TMDB key: the line's order as it was.
        val local = listOf(Title("Upgrade"), Title("Up in the Air"))
        assertEquals(listOf("Upgrade", "Up in the Air"), PopularOrder.apply(local, "up", emptyList(), { it.name }, { it.year }, { emptyList() }).map { it.name })
    }
}
