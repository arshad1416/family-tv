package app.familytv

import app.familytv.data.MovieEntity
import app.familytv.iptv.MovieTitles
import app.familytv.iptv.SeriesTitles
import app.familytv.iptv.groupKey
import app.familytv.iptv.language
import app.familytv.iptv.releaseYear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovieTitlesTest {
    private fun movie(id: String, name: String, tmdb: String? = null, year: Int? = null) =
        MovieEntity("s", id, name, null, "c", null, null, null, 0, 0, tmdb, year)

    private fun group(vararg m: MovieEntity) =
        MovieTitles.group(m.toList(), { it.tmdb }, { it.groupKey }, { it.releaseYear }).map { (_, v) -> v.map { it.streamId } }

    @Test
    fun yearsAreReadFromTheEndOfATitle() {
        assertEquals(2021, MovieTitles.yearIn("Dune (2021)"))
        assertEquals(2021, MovieTitles.yearIn("EN - Dune 2021 [4K]"))
        assertEquals(1984, MovieTitles.yearIn("Dune [1984] (FR)"))
        assertNull(MovieTitles.yearIn("Dune"))
        assertNull(MovieTitles.yearIn("1917"))
    }

    @Test
    fun languageCopiesOfOneFilmAreOneGroup() {
        assertEquals(
            listOf(listOf("1", "2", "4"), listOf("3")),
            group(movie("1", "EN - Dune (2021)"), movie("2", "FR - Dune (2021)"), movie("3", "Arrival"), movie("4", "Dune [4K]")),
        )
    }

    @Test
    fun remakesYearsApartStayApart() {
        assertEquals(
            listOf(listOf("1", "3"), listOf("2")),
            group(movie("1", "EN - Dune (2021)"), movie("2", "Dune (1984)"), movie("3", "FR - Dune", year = 2022)),
        )
    }

    @Test
    fun tmdbIdsKeepSameTitledFilmsApart() {
        assertEquals(
            listOf(listOf("1"), listOf("2")),
            group(movie("1", "The Thing", tmdb = "1091"), movie("2", "The Thing", tmdb = "60935")),
        )
    }

    @Test
    fun oldRowsFallBackToTheTitle() {
        val m = movie("1", "FR - Amélie (2001)")
        assertEquals("fr", m.language)
        assertEquals(SeriesTitles.parse("Amélie").key, m.groupKey)
        assertEquals(2001, m.releaseYear)
        assertEquals(SeriesTitles.ORIGINAL, movie("2", "Amélie").language)
    }
}
