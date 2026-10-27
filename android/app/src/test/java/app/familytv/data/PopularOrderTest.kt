package app.familytv.data

import app.familytv.trailer.TmdbVotes
import org.junit.Assert.assertEquals
import org.junit.Test

/** Search results ordered by TMDB's votes (rounds 5 and 6 on the TVs). Vote counts here are stubs, near TMDB's own. */
class PopularOrderTest {
    private data class Title(val name: String, val year: Int? = null, val tmdb: String? = null)

    private fun order(q: String, local: List<Title>, list: Popularity?) =
        PopularOrder.apply(local, q, list, { it.name }, { it.year }, { listOfNotNull(it.tmdb) }).map { it.name }

    @Test
    fun upPutsWholeWordsBeforeWordsThatOnlyStartWithIt() {
        val films = Popularity(
            listOf(
                TmdbVotes("14160", "Up", 2009, 20_000), TmdbVotes("22947", "Up in the Air", 2009, 4_600), TmdbVotes("646380", "Don't Look Up", 2021, 9_500),
                TmdbVotes("9762", "Step Up", 2006, 5_800), TmdbVotes("500664", "Upgrade", 2018, 6_500), TmdbVotes("440472", "The Upside", 2017, 2_200),
                TmdbVotes("466272", "Once Upon a Time... in Hollywood", 2019, 14_000), TmdbVotes("1924", "Superman", 1978, 4_000),
            ),
        )
        // The line's order (copies first) as the Kitchen showed it; Up in Smoke and Upgraded aren't among TMDB's most voted.
        val local = listOf(
            Title("Up", 2009), Title("Once Upon a Time... in Hollywood", 2019), Title("The Upside", 2017), Title("Upgrade", 2018), Title("Upgraded", 2024),
            Title("Up in Smoke", 1978), Title("Don't Look Up", 2021), Title("Uprising", 2001), Title("Up in the Air", 2009), Title("Step Up", 2006),
            Title("What's Up, Doc?", 1972), Title("Superman", 1978),
        )
        assertEquals(
            listOf(
                "Up", "Don't Look Up", "Up in the Air", "Step Up", "Upgrade", "The Upside", "Upgraded", "Up in Smoke", "Uprising", "What's Up, Doc?",
                "Once Upon a Time... in Hollywood", "Superman",
            ),
            order("up", local, films),
        )
    }

    @Test
    fun breakPutsBreakingBadFirstWhileTheWordIsStillBeingTyped() {
        // Prison Break's whole word counts twice its votes; Breaking Bad still has more.
        val shows = Popularity(listOf(TmdbVotes("1396", "Breaking Bad", 2008, 16_000), TmdbVotes("2288", "Prison Break", 2005, 5_500)))
        // Breaking Bad found by its TMDB id even under another spelling of the title.
        val local = listOf(Title("Break Point"), Title("BREAK IT ALL"), Title("Break Clause"), Title("Breaking Bad (US)", tmdb = "1396"), Title("Breakout Kings"), Title("Prison Break", 2005))
        assertEquals(
            listOf("Breaking Bad (US)", "Prison Break", "Break Point", "BREAK IT ALL", "Break Clause", "Breakout Kings"),
            order("break", local, shows),
        )
    }

    @Test
    fun yearsTellRemakesApartAndNoListKeepsTheLinesOrder() {
        val films = Popularity(listOf(TmdbVotes("438631", "Dune", 2021, 12_000), TmdbVotes("841", "Dune", 1984, 3_000)))
        val dunes = listOf(Title("Dune", 1984), Title("Dune", 2021), Title("Dune", 2000))
        assertEquals(listOf(2021, 1984, 2000), PopularOrder.apply(dunes, "dune", films, { it.name }, { it.year }, { emptyList() }).map { it.year })
        // Before the lists are fetched, or without a TMDB key: the line's order as it was.
        val local = listOf(Title("Upgrade"), Title("Up in the Air"))
        assertEquals(listOf("Upgrade", "Up in the Air"), order("up", local, null))
        assertEquals(listOf("Upgrade", "Up in the Air"), order("up", local, Popularity(emptyList())))
    }
}
