package app.familytv.data

import app.familytv.trailer.TmdbVotes
import org.junit.Assert.assertEquals
import org.junit.Test

/** Search results ordered by TMDB's votes (round 5: copies and TMDB's own search both got "up" and "break" wrong). Vote counts here are stubs. */
class PopularOrderTest {
    private data class Title(val name: String, val year: Int? = null, val tmdb: String? = null)

    private fun order(q: String, local: List<Title>, list: Popularity?) =
        PopularOrder.apply(local, q, list, { it.name }, { it.year }, { listOfNotNull(it.tmdb) }).map { it.name }

    @Test
    fun upPutsTheMostVotedFilmsFirst() {
        val films = Popularity(
            listOf(
                TmdbVotes("14160", "Up", 2009, 20_000), TmdbVotes("22947", "Up in the Air", 2009, 4_800), TmdbVotes("9762", "Step Up", 2006, 4_500),
                TmdbVotes("500664", "Upgrade", 2018, 4_000), TmdbVotes("440472", "The Upside", 2017, 2_000), TmdbVotes("11963", "Up in Smoke", 1978, 600),
                TmdbVotes("1924", "Superman", 1978, 4_000),
            ),
        )
        // The line's order (copies first) as the Kitchen showed it.
        val local = listOf(
            Title("Up", 2009), Title("The Upside", 2017), Title("Upgrade", 2018), Title("Upgraded", 2024), Title("Up in Smoke", 1978),
            Title("Uprising", 2001), Title("Up in the Air", 2009), Title("Step Up", 2006), Title("What's Up, Doc?", 1972), Title("Superman", 1978),
        )
        assertEquals(
            listOf("Up", "Up in the Air", "Step Up", "Upgrade", "The Upside", "Up in Smoke", "Upgraded", "Uprising", "What's Up, Doc?", "Superman"),
            order("up", local, films),
        )
    }

    @Test
    fun breakPutsBreakingBadFirstWhileTheWordIsStillBeingTyped() {
        val shows = Popularity(listOf(TmdbVotes("1396", "Breaking Bad", 2008, 15_000), TmdbVotes("2288", "Prison Break", 2005, 5_000)))
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
