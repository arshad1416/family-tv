package app.familytv.ui

import app.familytv.data.ChannelEntity
import app.familytv.data.MovieEntity
import app.familytv.data.SeriesEntity
import app.familytv.trailer.TmdbParse
import app.familytv.trailer.TmdbTitle
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturedTest {
    private fun json(s: String) = JsonParser.parseString(s).asJsonObject

    private fun movie(n: Int) = Slide("m$n", "Film $n", null, "b", null, "", Slide.Target.Movie(MovieGroup("m$n", listOf(MovieEntity("s", "$n", "Film $n", null, "c", null, null, null, 0, 0)))))
    private fun show(n: Int) = Slide("s$n", "Show $n", null, "b", null, "", Slide.Target.Show(SeriesGroup("s$n", listOf(SeriesEntity("s", "$n", "Show $n", null, "c", null, null, 0)))))
    private fun event(n: Int) = Slide("e$n", "Event $n", null, null, null, "", Slide.Target.Event(ChannelEntity("s", "$n", "PPV $n", n, null, "p", "epg$n", null, 0, 0), 0))

    @Test
    fun slidesAlternateWithAnEventAfterEveryTwo() {
        val mixed = Featured.mix((1..5).map(::movie), (1..5).map(::show), (1..4).map(::event))
        assertEquals(listOf("m1", "s1", "e1", "m2", "s2", "e2", "m3", "s3", "e3", "m4", "s4", "m5"), mixed.map { it.key })
        // A short line: whatever there is, in turn, with the events after.
        assertEquals(listOf("m1", "e1", "e2"), Featured.mix(listOf(movie(1)), emptyList(), listOf(event(1), event(2))).map { it.key })
        assertEquals(listOf("s1", "s2"), Featured.mix(emptyList(), listOf(show(1), show(2)), emptyList()).map { it.key })
    }

    @Test
    fun detailLine() {
        assertEquals("2024  ·  Action  ·  ★ 7.8", Featured.detail(TmdbTitle("1", false, "A", 2024, "b", listOf(28, 12), 7.81)))
        assertEquals("Sci-Fi & Fantasy", Featured.detail(TmdbTitle("1", true, "A", null, "b", listOf(10765), null)))
        assertEquals("", Featured.detail(TmdbTitle("1", true, "A", null, "b", listOf(1), null)))
    }

    @Test
    fun eventsComeFromPpvAndEventListsAndSkipFillers() {
        assertTrue(Featured.isEventCategory("UK| PPV EVENTS"))
        assertTrue(Featured.isEventCategory("SPORTS PPV"))
        assertTrue(Featured.isEventCategory("LIVE EVENTS ᴴᴰ"))
        assertFalse(Featured.isEventCategory("UK| SPORT ᴴᴰ"))
        assertFalse(Featured.isEventCategory("PREVENT"))
        assertTrue(Featured.isFiller("No Event Scheduled"))
        assertTrue(Featured.isFiller("Off Air"))
        assertFalse(Featured.isFiller("UFC 310: Pantoja vs Asakura"))
        assertEquals("Live event  ·  On now", Featured.eventDetail(1_000, 2_000))
    }

    @Test
    fun tmdbListsAndLogos() {
        val list = json(
            """{"results":[
              {"id":1,"title":"Dune","release_date":"2024-02-27","backdrop_path":"/d.jpg","genre_ids":[878],"vote_average":8.1},
              {"id":2,"title":"Adult","adult":true,"backdrop_path":"/a.jpg"},
              {"id":3,"title":"No art"}
            ]}""",
        )
        val titles = TmdbParse.titles(list, tv = false)
        assertEquals(listOf("1"), titles.map { it.id })
        assertEquals(2024, titles[0].year)
        assertEquals("https://image.tmdb.org/t/p/w1280/d.jpg", titles[0].backdrop)
        val shows = TmdbParse.titles(json("""{"results":[{"id":9,"name":"Severance","first_air_date":"2022-02-18","backdrop_path":"/s.jpg"}]}"""), tv = true)
        assertEquals("Severance", shows.single().title)

        val logos = json(
            """{"logos":[
              {"file_path":"/fr.png","iso_639_1":"fr","vote_average":9},
              {"file_path":"/en1.png","iso_639_1":"en","vote_average":5},
              {"file_path":"/en2.png","iso_639_1":"en","vote_average":6},
              {"file_path":"/none.png","iso_639_1":null,"vote_average":10}
            ]}""",
        )
        assertEquals("https://image.tmdb.org/t/p/w500/en2.png", TmdbParse.logo(logos))
        assertEquals("https://image.tmdb.org/t/p/w500/none.png", TmdbParse.logo(json("""{"logos":[{"file_path":"/none.png","iso_639_1":null}]}""")))
        assertNull(TmdbParse.logo(json("""{"logos":[{"file_path":"/fr.png","iso_639_1":"fr"}]}""")))
    }
}
