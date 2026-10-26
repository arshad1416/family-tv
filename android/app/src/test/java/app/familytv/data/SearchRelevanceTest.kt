package app.familytv.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.familytv.iptv.SeriesTitles
import app.familytv.ui.groupMovies
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real-world queries against a catalogue with provider-style names: what comes first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SearchRelevanceTest {
    private lateinit var db: AppDb
    private val dao get() = db.catalog()

    private fun movie(id: String, name: String, rating: String?, year: Int? = null, tmdb: String? = null) =
        MovieEntity("s", id, name, null, "c", rating, "mp4", null, 0, 0, tmdb, year, titleKey = SeriesTitles.parse(name).key)

    private fun show(id: String, name: String, rating: String?) =
        SeriesEntity("s", id, name, null, "c", null, rating, 0, titleKey = SeriesTitles.parse(name).key)

    private fun channel(id: String, name: String, num: Int) = ChannelEntity("s", id, name, num, null, "c", null, null, 0, 0)

    @Before
    fun fill() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDb::class.java).allowMainThreadQueries().build()
        dao.upsertMovies(
            listOf(
                movie("1", "EN - Superman (1978)", "7.4"),
                movie("2", "EN - Up in the Air (2009)", "7.4"),
                movie("3", "EN - What's Up, Doc? (1972)", "7.7"),
                movie("4", "EN - Up (2009)", "8.3"),
                movie("5", "EN - Upgrade (2018)", "7.5"),
                movie("6", "Dune (1984)", "6.3", year = 1984, tmdb = "841"),
                movie("7", "EN - Dune (2021) 4K", "8.0", tmdb = "438631"),
                movie("8", "FR - Dune (2021)", "8.0", tmdb = "438631"),
                movie("9", "EN - Dune: Part Two (2024)", "8.5", tmdb = "693134"),
                movie("10", "Pokémon: The First Movie (1998)", "6.3"),
                movie("11", "EN - Pokémon Detective Pikachu (2019)", "6.6"),
                movie("12", "EN - Fast &amp; Furious (2009)", "6.5"),
                movie("13", "EN - Office Space (1999)", "7.6"),
                movie("14", "|EN| Enola Holmes (2020)", "6.6"),
                movie("15", "EN - The Up in Smoke Tour (2000)", "6.0"),
                movie("16", "EN - Uppity (2020)", "6.1"),
                movie("17", "EN - Uproar (2023)", "6.4"),
            ),
        )
        dao.upsertSeries(
            listOf(
                show("1", "Prison Break", "8.3"),
                show("2", "Break Point", "6.5"),
                show("3", "EN - Breaking Bad", "9.5"),
                show("4", "Breakout Kings", "7.0"),
                show("5", "EN - The Office (US)", "9.0"),
                show("6", "The Office (UK)", "8.5"),
                show("7", "Pokémon (1997)", "7.0"),
            ),
        )
        dao.upsertChannels(
            listOf(
                channel("1", "US| CNN", 1),
                channel("2", "CA| Global TV", 2),
                channel("3", "CA| Global News Toronto", 3),
                channel("4", "CA| CTV News", 4),
                channel("5", "CA| CP24 HD", 5),
                channel("6", "UK| BBC News", 6),
                channel("7", "CA| Global News BC", 7),
                channel("8", "CA| CBC News Network", 8),
                channel("9", "EG| CBC Drama", 9),
                channel("10", "CBC 22:", 10),
                channel("11", "CA| CBC Toronto", 11),
                channel("12", "AR| CBC", 12),
                channel("13", "CA| FOX NEWS", 13),
            ),
        )
    }

    @After
    fun close() = db.close()

    private fun movies(q: String) = runBlocking { dao.searchMovies(q, 50).map { SeriesTitles.parse(it.name).title } }
    private fun shows(q: String) = runBlocking { dao.searchSeries(q, 50).map { SeriesTitles.parse(it.name).title } }
    private fun channels(q: String) = runBlocking { dao.searchChannels(q, 50).map { Names.channel(it.name) } }

    @Test
    fun aShortTitleComesFirstAndNotInsideOtherWords() {
        // Whole words before word starts: Up in the Air before Upgrade and Uppity.
        // Titles that start with "up" by how well known they are, whole words a point ahead.
        assertEquals(listOf("Up", "Up in the Air", "Upgrade", "The Up in Smoke Tour", "Uproar", "Uppity", "What's Up, Doc?"), movies("up"))
        assertEquals(movies("up"), movies("UP"))
        assertEquals("Upgrade", movies("upg").first())
    }

    @Test
    fun aYearPicksTheFilm() {
        assertEquals(listOf("Dune", "Dune"), movies("dune 2021"))
        assertEquals(setOf("7", "8"), runBlocking { dao.searchMovies("dune 2021", 50).map { it.streamId }.toSet() })
        // Both films called Dune before Dune: Part Two.
        assertEquals(listOf("Dune", "Dune", "Dune", "Dune: Part Two"), movies("dune"))
        // Every word has to match.
        assertEquals(listOf("Dune: Part Two"), movies("dune two"))
    }

    @Test
    fun languageCopiesAreOneResult() = runBlocking {
        val groups = groupMovies(dao.searchMovies("dune", 50))
        assertEquals(3, groups.size)
        assertEquals(2, groups.first().copies.size)
    }

    @Test
    fun accentsAndAmpersandsDontMatter() {
        assertEquals(listOf("Pokémon Detective Pikachu", "Pokémon: The First Movie"), movies("pokemon"))
        assertEquals(movies("pokemon"), movies("Pokémon"))
        assertEquals(listOf("Pokémon Detective Pikachu"), movies("pikachu"))
        // The provider wrote "Fast &amp; Furious".
        assertEquals(listOf("12"), runBlocking { dao.searchMovies("fast and furious", 50).map { it.streamId } })
        assertEquals(listOf("12"), runBlocking { dao.searchMovies("fast & furious", 50).map { it.streamId } })
    }

    @Test
    fun providerTagsAreNotWords() {
        // "EN -" and "|EN|" are tags, not words: "en" finds only Enola Holmes.
        assertEquals(listOf("Enola Holmes"), movies("en"))
        assertTrue(movies("4k").isEmpty())
    }

    @Test
    fun aWordTypedPartWayFindsTheBestKnownTitleFirst() {
        // Breaking Bad's rating keeps it first, whole word or not (round 3).
        assertEquals(listOf("Breaking Bad", "Break Point", "Breakout Kings", "Prison Break"), shows("break"))
        assertEquals(listOf("Breaking Bad"), shows("bad breaking"))
        assertEquals("Up in the Air", movies("air").single())
        assertEquals("What's Up, Doc?", movies("doc").single())
    }

    @Test
    fun aLeadingTheDoesntCount() {
        assertEquals(listOf("The Office (US)", "The Office (UK)"), shows("the office"))
        assertEquals(listOf("The Office (US)", "The Office (UK)"), shows("office"))
        assertEquals(listOf("Office Space"), movies("office"))
    }

    @Test
    fun containsFillsInOnlyForLongerText() {
        assertEquals(listOf("Superman"), movies("erman"))
        assertFalse("Superman" in movies("up"))
    }

    @Test
    fun channelsMatchTheirListedName() {
        assertEquals(listOf("CP24 HD"), channels("cp24"))
        assertEquals(listOf("Global News BC", "Global News Toronto"), channels("global news"))
        assertEquals(listOf("Global TV", "Global News BC", "Global News Toronto"), channels("global"))
        // Canadian lines first, then English ones.
        // Canadian broadcasters first, then the US channels a Canadian line carries, then English ones.
        assertEquals(listOf("CTV News", "Global News BC", "CBC News Network", "Global News Toronto", "FOX NEWS", "BBC News"), channels("news"))
        // CBC Canada before Egypt's CBC channels, and the cut-off feed last.
        assertEquals(listOf("11", "8", "12", "9", "10"), runBlocking { dao.searchChannels("cbc", 50).map { it.streamId } })
        assertTrue(channels("ca").isEmpty())
    }
}
