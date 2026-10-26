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

    private fun channel(id: String, name: String, num: Int, epgId: String? = null) = ChannelEntity("s", id, name, num, null, "c", epgId, null, 0, 0)

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
                // Obscure titles with high ratings: the rating is no sign of how well known a title is.
                movie("16", "EN - Uppity (2020)", "8.9"),
                movie("17", "EN - Uproar (2023)", "8.5"),
                // The line carries the well-known films in several languages and qualities.
                movie("18", "FR - Up (2009)", "8.3"),
                movie("19", "ES - Up (2009)", "8.3"),
                movie("20", "EN - Up (2009) 4K", "8.3"),
                movie("21", "FR - Up in the Air (2009)", "7.4"),
                // Titles that only start with "up", carried in more copies than Up in the Air.
                movie("22", "EN - The Upside (2017)", "7.1"),
                movie("23", "FR - The Upside (2017)", "7.1"),
                movie("24", "ES - The Upside (2017)", "7.1"),
                movie("25", "EN - The Upside (2017) 4K", "7.1"),
                movie("26", "EN - Upgraded (2024)", "6.4"),
                movie("27", "FR - Upgraded (2024)", "6.4"),
                movie("28", "ES - Upgraded (2024)", "6.4"),
                movie("29", "EN - Uprising (2001)", "6.5"),
                movie("30", "FR - Uprising (2001)", "6.5"),
            ),
        )
        dao.upsertSeries(
            listOf(
                show("1", "Prison Break", "8.3"),
                show("2", "Break Point", "9.6"),
                show("3", "EN - Breaking Bad", "9.5"),
                show("8", "BREAK IT ALL", "9.7"),
                show("9", "FR - Breaking Bad", "9.5"),
                show("10", "ES - Breaking Bad", "9.5"),
                show("11", "EN - Breaking Bad 4K", "9.5"),
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
                // A Canadian line's own guide id ends in .ca for every channel it carries, BBC's too.
                channel("14", "CA| BBC World News", 14, "BBCWorldNews.ca"),
                channel("15", "CA| CTV NewsChannel", 15, "CTVNewsChannel.ca"),
                // Canadian news brands whose names aren't one of the network words (round 4).
                channel("16", "CA| City News Toronto ON HD", 16),
                channel("17", "CA| Business News Network HD", 17),
            ),
        )
    }

    @After
    fun close() = db.close()

    // One entry per title, as Search shows them (copies grouped).
    private fun movies(q: String) = runBlocking { dao.searchMovies(q, 50).map { SeriesTitles.parse(it.name).title }.distinct() }
    private fun shows(q: String) = runBlocking { dao.searchSeries(q, 50).map { SeriesTitles.parse(it.name).title }.distinct() }
    private fun channels(q: String) = runBlocking { dao.searchChannels(q, 50).map { Names.channel(it.name) } }

    @Test
    fun aShortTitleComesFirstAndNotInsideOtherWords() {
        // Titles that start with "up" by how well known they are (copies on the line, whole words two ahead),
        // the rating only between equals: Up in the Air (2 copies) before The Upside (4) and the higher rated
        // Uppity and Uproar (round 4: it was 13th on the Kitchen's line).
        assertEquals(
            listOf("Up", "Up in the Air", "The Upside", "Upgraded", "The Up in Smoke Tour", "Uprising", "Uppity", "Uproar", "Upgrade", "What's Up, Doc?"),
            movies("up"),
        )
        assertEquals(movies("up"), movies("UP"))
        assertEquals("Upgrade", movies("upgrade").first())
    }

    @Test
    fun aYearPicksTheFilm() {
        assertEquals(listOf("Dune"), movies("dune 2021"))
        assertEquals(setOf("7", "8"), runBlocking { dao.searchMovies("dune 2021", 50).map { it.streamId }.toSet() })
        // Both films called Dune (three rows) before Dune: Part Two.
        assertEquals(listOf("Dune", "Dune: Part Two"), movies("dune"))
        assertEquals(3, runBlocking { dao.searchMovies("dune", 50).count { SeriesTitles.parse(it.name).title == "Dune" } })
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
        // Breaking Bad's copies put it first, whole word or not, ahead of higher rated obscure shows (round 4).
        assertEquals(listOf("Breaking Bad", "BREAK IT ALL", "Break Point", "Breakout Kings", "Prison Break"), shows("break"))
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
        // Every Canadian broadcaster (a word start too: NewsChannel; City News and BNN by their names) before BBC World News on the Canadian line.
        assertEquals(
            listOf(
                "CTV News", "Global News BC", "CBC News Network", "Global News Toronto", "City News Toronto ON HD", "Business News Network HD",
                "CTV NewsChannel", "FOX NEWS", "BBC World News", "BBC News",
            ),
            channels("news"),
        )
        // CBC Canada before Egypt's CBC channels, and the cut-off feed last.
        assertEquals(listOf("11", "8", "12", "9", "10"), runBlocking { dao.searchChannels("cbc", 50).map { it.streamId } })
        assertTrue(channels("ca").isEmpty())
    }
}
