package app.familytv

import app.familytv.discovery.*
import org.junit.Assert.*
import org.junit.Test

class SourceDiscoveryTest {
    private val hash = "0123456789abcdef0123456789abcdef01234567"
    private val movie = DiscoveryTitle("movie", "603", "The Matrix", 1999, imdb = "tt0133093")
    private val episode = DiscoveryTarget(DiscoveryTitle("tv", "1", "Northern Lights", 2024, imdb = "tt1234567"), 2, 3)

    @Test fun credentiallessSourcesRejectUrlsInvalidHashesAndIndices() {
        val rows = TorrentioParse.sources("""{"streams":[
          {"infoHash":"$hash","fileIdx":0,"name":"Torrentio 1080p","title":"The.Matrix.1999.1080p.x264.mkv"},
          {"url":"https://example.test/token","infoHash":"$hash","name":"1080p x264"},
          {"infoHash":"bad","name":"1080p x264"},
          {"infoHash":"$hash","fileIdx":-1,"name":"1080p x264"},
          {"infoHash":"$hash","fileIdx":0.5,"name":"1080p x264"}
        ]}""")
        assertEquals(1, rows.size)
        assertEquals(0, rows.single().fileIdx)
        assertTrue(rows.single().compatible)
    }

    @Test fun conservativeCompatibilityDoesNotPrefer4kOrHdrOrUnknownCodec() {
        fun source(s: String) = TorrentioParse.sources("""{"streams":[{"infoHash":"$hash","title":"$s"}]}""").single()
        assertTrue(source("Film 1080p WEB x264 AAC").compatible)
        assertFalse(source("Film 2160p HDR x265").compatible)
        assertFalse(source("Film 1080p Dolby Vision AVC").compatible)
        assertFalse(source("Film 1080p").compatible)
        assertNull(source("Film 1080p x264").fileIdx)
    }

    @Test fun sourceRankingPrefers1080pSdrButKeepsManual4kChoice() {
        val rows = TorrentioParse.sources("""{"streams":[
          {"infoHash":"$hash","fileIdx":0,"title":"Open.Film.2160p.HDR.x265.mkv"},
          {"infoHash":"$hash","fileIdx":1,"title":"Open.Film.720p.x264.mkv"},
          {"infoHash":"$hash","fileIdx":2,"title":"Open.Film.1080p.x264.mkv"}
        ]}""")
        assertEquals(listOf(2, 1, 0), rows.map { it.fileIdx })
    }

    @Test fun wrongShapedHintsAndAdultFlagsFailSafely() {
        assertEquals(1, TorrentioParse.sources("""{"streams":[{"infoHash":"$hash","title":"Open Film 1080p x264","behaviorHints":[]}]}""").size)
        assertTrue(DiscoveryParse.search("""{"results":[{"id":42,"media_type":"movie","title":"Open Film","adult":{}}]}""").isEmpty())
    }

    @Test fun fileIndexCannotSelectSparseOrReorderedRdIds() {
        assertThrows(SourceUnavailable::class.java) {
            SourceRules.select(SourceCandidate(hash, 0, "Open Film", true), DiscoveryTarget(movie),
                listOf(RdSourceFile(7, "The.Matrix.1999.mkv", 100)), "The Matrix")
        }
    }

    @Test fun externalProgressKeepsTmdbAndEpisodeIdentityAcrossSources() {
        val film = DiscoveryTarget(movie).playItem()
        assertEquals("m/tmdb/movie/603", film.key)
        assertEquals("603", film.tmdb)
        assertEquals("tt0133093", film.imdb)
        assertEquals("e/tmdb/tv/1/2/3", episode.playItem().key)
        assertEquals("tt1234567", episode.playItem().imdb) // show ID, never an episode-specific IMDb ID
    }

    @Test fun fileIndexUsesRealDebridIdAndEpisodeMustMatch() {
        val source = SourceCandidate(hash, 1, "1080p x264", true)
        val files = listOf(RdSourceFile(1, "Northern.Lights.S02E02.mkv", 100), RdSourceFile(2, "Northern.Lights.S02E03.mkv", 200))
        assertEquals(2, SourceRules.select(source, episode, files, "Northern Lights S02").id)
        assertThrows(SourceUnavailable::class.java) { SourceRules.select(source.copy(fileIdx = 0), episode, files, "Northern Lights S02") }
        assertThrows(SourceUnavailable::class.java) { SourceRules.select(source.copy(fileIdx = 4), episode, files, "Northern Lights S02") }
        assertThrows(SourceUnavailable::class.java) { SourceRules.select(source.copy(fileIdx = null), episode, files + files.last().copy(id = 3), "Northern Lights S02") }
    }

    @Test fun noFirstFileFallbackForAmbiguousMovie() {
        val files = listOf(RdSourceFile(1, "The.Matrix.1999.1080p.mkv", 100), RdSourceFile(2, "The.Matrix.1999.720p.mkv", 100))
        assertThrows(SourceUnavailable::class.java) { SourceRules.select(SourceCandidate(hash, null, "1080p x264", true), DiscoveryTarget(movie), files, "The Matrix 1999") }
    }

    @Test fun combinedEpisodesCannotMasqueradeAsOneEpisode() {
        for (release in listOf("Northern.Lights.S02E03E04.mkv", "Northern.Lights.S02E03-04.mkv", "Northern.Lights.2x03-04.mkv", "Northern.Lights.2x03x04.mkv", "Northern.Lights.S02E03.S02E04.mkv",
            "Northern.Lights.S02E03.E04.mkv", "Northern.Lights.S02E03_E04.mkv", "Northern.Lights.S02E03.x04.mkv", "Northern.Lights.2x03.x04.mkv", "Northern.Lights.2x03.2x04.mkv")) {
            assertThrows(release, SourceUnavailable::class.java) {
                SourceRules.select(SourceCandidate(hash, 0, "1080p x264", true), episode, listOf(RdSourceFile(1, release, 100)), "Northern Lights S02")
            }
        }
        for (release in listOf("Northern.Lights.S02E03.x264.mkv", "Northern.Lights.S02E03.x265.mkv", "Northern.Lights.2x03.x264.mkv",
            "Northern.Lights.S02E03-480p.x264.mkv", "Northern.Lights.S02E03-576p.x264.mkv", "Northern.Lights.S02E03-720p.x264.mkv", "Northern.Lights.S02E03-1080p.x264.mkv", "Northern.Lights.S02E03-x264.mkv", "Northern.Lights.S02E03-x265.mkv")) {
            assertEquals(release, SourceRules.select(SourceCandidate(hash, 0, "1080p", true), episode, listOf(RdSourceFile(1, release, 100)), "Northern Lights S02").path)
        }
    }

    @Test fun movieSelectionRejectsSeasonOnlyAndCompleteSeasonVideos() {
        val source = SourceCandidate(hash, 0, "1080p", true)
        for ((file, folder) in listOf("The.Matrix.S01.1080p.mkv" to "The Matrix", "The.Matrix.Season.1.Complete.mkv" to "The Matrix", "The.Matrix.1080p.mkv" to "The Matrix S01 Complete")) {
            assertThrows(file, SourceUnavailable::class.java) { SourceRules.select(source, DiscoveryTarget(movie), listOf(RdSourceFile(1, file, 100)), folder) }
        }
        assertEquals(1, SourceRules.select(source, DiscoveryTarget(movie), listOf(RdSourceFile(1, "The.Matrix.1999.1080p.mkv", 100)), "The Matrix 1999").id)
    }

    @Test fun tmdbExcludesAdultPeopleAndMalformedResults() {
        val titles = DiscoveryParse.search("""{"results":[
          {"id":603,"media_type":"movie","title":"The Matrix","release_date":"1999-03-30","adult":false},
          {"id":9,"media_type":"movie","title":"Restricted","adult":true},
          {"id":2,"media_type":"person","name":"Person"},
          {"id":-1,"media_type":"tv","name":"Bad"}
        ]}""")
        assertEquals(listOf("603"), titles.map { it.tmdb })
        assertEquals(1999, titles.single().year)
    }

    @Test fun knownHiddenCatalogTitleCannotReappearAsRdOnly() {
        assertFalse(DiscoveryPolicy.allowed("EN - Locked Film (2024)", setOf("lockedfilm")))
        assertTrue(DiscoveryPolicy.allowed("Other film", setOf("lockedfilm")))
    }
}
