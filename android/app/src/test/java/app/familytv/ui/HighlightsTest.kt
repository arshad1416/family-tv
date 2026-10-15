package app.familytv.ui

import app.familytv.data.MovieEntity
import app.familytv.data.SeriesEntity
import app.familytv.trailer.MovieMeta
import app.familytv.trailer.TmdbParse
import app.familytv.user.ProgressEntity
import app.familytv.user.ProgressKey
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighlightsTest {
    private fun movie(rating: String? = "7.84", year: Int? = 2019) =
        MovieEntity("s", "1", "Knives Out", "http://img/p.jpg", "c", rating, "mkv", null, added = 0, gen = 0, year = year)

    @Test
    fun ratingsOutOfTen() {
        assertEquals("★ 7.8", ratingText("7.84"))
        assertEquals("★ 10.0", ratingText(" 10 "))
        assertNull(ratingText("0"))
        assertNull(ratingText("42"))
        assertNull(ratingText("N/A"))
        assertNull(ratingText(null))
    }

    @Test
    fun runningTimes() {
        assertEquals("45 min", runtimeText(45))
        assertEquals("2 h", runtimeText(120))
        assertEquals("2 h 10 min", runtimeText(130))
    }

    @Test
    fun movieSpotJoinsWhatIsKnown() {
        val half = ProgressEntity(ProgressKey.movie("s", "1"), ProgressKey.MOVIE, "s", "1", percent = 40f)
        val s = movieSpot(movie(), half, MovieMeta("A detective investigates.", listOf("Comedy", "Crime", "Drama"), 130))
        assertEquals("Knives Out", s.title)
        assertEquals("2019  ·  ★ 7.8  ·  Comedy, Crime  ·  2 h 10 min  ·  40% watched", s.meta)
        assertEquals("A detective investigates.", s.blurb)
        assertEquals("http://img/p.jpg", s.art)

        val bare = movieSpot(movie(rating = "0", year = null), null, null)
        assertNull(bare.meta)
        assertNull(bare.blurb)
        assertEquals("Watched", movieSpot(movie(rating = null, year = null), half.copy(percent = 95f, watched = true), null).meta)
    }

    @Test
    fun seriesSpotCountsNewEpisodes() {
        val copies = listOf(
            SeriesEntity("s", "1", "EN - Severance", null, "c", null, "junk", gen = 0),
            SeriesEntity("s", "2", "FR - Severance", "http://img/s.jpg", "c", "Office workers split.", "8.7", gen = 0, year = 2022),
        )
        val g = SeriesGroup("severance", copies)
        assertEquals("2022  ·  ★ 8.7  ·  3 new episodes", seriesSpot(g, 3).meta)
        assertEquals("2022  ·  ★ 8.7  ·  New episode", seriesSpot(g, 1).meta)
        assertEquals("Office workers split.", seriesSpot(g, 0).blurb)
        assertEquals("http://img/s.jpg", seriesSpot(g, 0).art)
    }

    @Test
    fun tmdbMovieDetails() {
        val m = TmdbParse.movie(JsonParser.parseString("""{"overview":" Plot. ","runtime":0,"genres":[{"id":1,"name":"Drama"},{"id":2}]}""").asJsonObject)
        assertEquals("Plot.", m.overview)
        assertEquals(listOf("Drama"), m.genres)
        assertNull(m.runtimeMin)
    }
}
