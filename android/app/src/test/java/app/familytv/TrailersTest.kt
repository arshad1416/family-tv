package app.familytv

import app.familytv.iptv.XtreamParser
import app.familytv.trailer.EpisodeMeta
import app.familytv.trailer.TmdbParse
import app.familytv.trailer.Trailers
import app.familytv.trailer.Trailers.Video
import app.familytv.trailer.TvdbParse
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailersTest {
    private fun json(s: String) = JsonParser.parseString(s).asJsonObject

    @Test
    fun youtubeIdFromProviderFields() {
        assertEquals("dQw4w9WgXcQ", Trailers.youtubeId("dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", Trailers.youtubeId(" https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1 "))
        assertEquals("dQw4w9WgXcQ", Trailers.youtubeId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", Trailers.youtubeId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", Trailers.youtubeId("plugin://plugin.video.youtube/?action=play_video&videoid=dQw4w9WgXcQ"))
        assertNull(Trailers.youtubeId(""))
        assertNull(Trailers.youtubeId("null"))
        assertNull(Trailers.youtubeId("0"))
        assertNull(Trailers.youtubeId("https://vimeo.com/123456789"))
        assertNull(Trailers.youtubeId("https://www.youtube.com/watch?v=tooShort"))
    }

    @Test
    fun pickPrefersLanguageThenTrailerThenOfficial() {
        val videos = listOf(
            Video("aaaaaaaaaaa", "YouTube", "Clip", official = true, language = "en"),
            Video("bbbbbbbbbbb", "YouTube", "Teaser", official = true, language = "en"),
            Video("ccccccccccc", "YouTube", "Trailer", official = false, language = "en"),
            Video("ddddddddddd", "YouTube", "Trailer", official = true, language = "en"),
            Video("eeeeeeeeeee", "Vimeo", "Trailer", official = true, language = "en"),
            Video("fffffffffff", "YouTube", "Trailer", official = true, language = "fr"),
        )
        assertEquals("ddddddddddd", Trailers.pick(videos, listOf("en")))
        assertEquals("fffffffffff", Trailers.pick(videos, listOf("fr", "en")))
        assertEquals("fffffffffff", Trailers.pick(videos, listOf("fr-CA")))
        // German isn't there: best of the rest.
        assertEquals("ddddddddddd", Trailers.pick(videos.filter { it.language == "en" }, listOf("de", "en")))
        // Only a teaser in the wanted language beats a trailer in another one.
        assertEquals("bbbbbbbbbbb", Trailers.pick(videos.filter { it.key != "ddddddddddd" && it.key != "ccccccccccc" }, listOf("en", "fr")))
        assertNull(Trailers.pick(listOf(videos[0], videos[4]), listOf("en")))
        assertNull(Trailers.pick(emptyList(), listOf("en")))
    }

    @Test
    fun providerTrailerFields() {
        val vod = XtreamParser.vodInfo("""{"info":{"tmdb_id":"603","youtube_trailer":"https://www.youtube.com/watch?v=vKQi3bBA1y8","genre":"Action"},"movie_data":{"stream_id":1}}""".reader())
        assertEquals("vKQi3bBA1y8", vod.trailer)
        assertEquals("603", vod.tmdb)
        val none = XtreamParser.vodInfo("""{"info":{"youtube_trailer":"","tmdb_id":"0"}}""".reader())
        assertNull(none.trailer)
        assertNull(none.tmdb)
        assertNull(XtreamParser.vodInfo("[]".reader()).trailer)
    }

    @Test
    fun tmdbResponses() {
        val season = TmdbParse.season(
            json(
                """{"overview":"Walt grows bolder.","episodes":[{"episode_number":1,"still_path":"/s1.jpg","overview":"Seven Thirty-Seven."},{"episode_number":2,"still_path":null,"overview":""}],
                "videos":{"results":[{"key":"HhesaQXLuRY","site":"YouTube","type":"Trailer","official":true,"iso_639_1":"en"}]}}""",
            ),
        )
        assertEquals("Walt grows bolder.", season.overview)
        assertEquals(EpisodeMeta("https://image.tmdb.org/t/p/w780/s1.jpg", "Seven Thirty-Seven."), season.episodes[1])
        assertEquals(EpisodeMeta(null, null), season.episodes[2])
        assertEquals("HhesaQXLuRY", Trailers.pick(season.videos, listOf("en")))
        assertEquals("1396", TmdbParse.firstId(json("""{"results":[{"id":1396,"name":"Breaking Bad"},{"id":2}]}""")))
        assertNull(TmdbParse.firstId(json("""{"results":[]}""")))
    }

    @Test
    fun tvdbResponses() {
        assertEquals("tok", TvdbParse.token(json("""{"status":"success","data":{"token":"tok"}}""")))
        val remote = json("""{"data":[{"movie":{"id":5}},{"series":{"id":81189,"name":"Breaking Bad"}}]}""")
        assertEquals("81189", TvdbParse.remoteId(remote, "series"))
        assertEquals("5", TvdbParse.remoteId(remote, "movie"))
        assertNull(TvdbParse.remoteId(json("""{"data":[]}"""), "series"))
        assertEquals("81189", TvdbParse.searchId(json("""{"data":[{"tvdb_id":"81189","name":"Breaking Bad"}]}""")))
        val eps = json(
            """{"data":{"episodes":[{"seasonNumber":1,"number":1,"overview":"Pilot.","image":"/banners/episodes/81189/349232.jpg"},
               {"seasonNumber":1,"number":2,"overview":null,"image":"https://artworks.thetvdb.com/x.jpg"}]},"links":{"next":null}}""",
        )
        val map = TvdbParse.episodes(eps)
        assertEquals(EpisodeMeta("https://artworks.thetvdb.com/banners/episodes/81189/349232.jpg", "Pilot."), map[1 to 1])
        assertEquals(EpisodeMeta("https://artworks.thetvdb.com/x.jpg", null), map[1 to 2])
        assertFalse(TvdbParse.hasNext(eps))
        assertTrue(TvdbParse.hasNext(json("""{"links":{"next":"https://api4.thetvdb.com/v4/series/1/episodes/default?page=1"}}""")))
        val trailers = json("""{"data":{"trailers":[{"url":"https://www.youtube.com/watch?v=aaaaaaaaaaa","language":"spa"},{"url":"https://youtu.be/bbbbbbbbbbb","language":"eng"}]}}""")
        assertEquals("bbbbbbbbbbb", TvdbParse.trailer(trailers, listOf("en")))
        assertEquals("aaaaaaaaaaa", TvdbParse.trailer(trailers, listOf("es")))
        assertNull(TvdbParse.trailer(json("""{"data":{"trailers":[]}}"""), listOf("en")))
    }
}
