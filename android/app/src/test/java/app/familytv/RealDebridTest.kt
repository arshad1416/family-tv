package app.familytv

import app.familytv.debrid.RdCopy
import app.familytv.debrid.RdFile
import app.familytv.debrid.RdIndex
import app.familytv.debrid.RdNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealDebridTest {
    @Test
    fun parsesMovieReleaseNames() {
        val p = RdNames.parse("The.Last.Convoy.2023.1080p.WEB-DL.DDP5.1.x264.mkv")
        assertEquals("The Last Convoy", p.title)
        assertEquals(2023, p.year)
        assertNull(p.episode)
        assertEquals(1080, p.quality)
        assertEquals("Dune Part Two", RdNames.parse("Dune Part Two (2024) [2160p] [4K] [WEB].mp4").title)
        assertEquals(2160, RdNames.parse("Dune Part Two (2024) [2160p] [4K] [WEB].mp4").quality)
        // Titles that are years keep them.
        assertEquals("1917", RdNames.parse("1917.2019.720p.BluRay.mkv").title)
        assertEquals(2019, RdNames.parse("1917.2019.720p.BluRay.mkv").year)
        assertEquals("Heat", RdNames.parse("[rartv] Heat 1995 REMASTERED 1080p.mkv").title)
    }

    @Test
    fun parsesEpisodesAndTakesTheShowFromTheFolder() {
        val p = RdNames.parse("Northern.Lights.S01E04.1080p.WEB.h264.mkv", "Northern.Lights.S01.1080p.WEB")
        assertEquals("Northern Lights", p.title)
        assertEquals(1 to 4, p.season to p.episode)
        assertEquals(2 to 3, RdNames.parse("Show.2x03.HDTV.avi").let { it.season to it.episode })
        val bare = RdNames.parse("S02E05.mkv", "Northern Lights (2024) Season 2 1080p")
        assertEquals("Northern Lights", bare.title)
        assertEquals(2024, bare.year)
        assertEquals(1080, bare.quality)
    }

    @Test
    fun skipsSamplesAndNonVideo() {
        assertTrue(RdNames.isExtra("movie.sample.mkv"))
        assertFalse(RdNames.isVideo("movie.nfo"))
        val index = RdIndex(listOf(file("Heat.1995.Sample.mkv"), file("Heat.1995.nfo")))
        assertNull(index.movie("Heat", 1995))
    }

    @Test
    fun matchesCatalogMoviesByTitleAndYear() {
        val index = RdIndex(listOf(file("The.Last.Convoy.2023.1080p.WEB-DL.mkv", 4_300_000_000)))
        val copy = index.movie("EN - The Last Convoy (2023)", null)!!
        assertEquals("1080p · 4.3 GB", copy.label)
        assertEquals(copy, index.movie("Last Convoy", 2024)) // a year apart still counts
        assertNull(index.movie("The Last Convoy", 2015)) // a different film of the same name
        assertNull(index.movie("The Last Convoy 2", null))
    }

    @Test
    fun picksTheCopyEveryTvPlaysSmoothly() {
        val index = RdIndex(
            listOf(
                file("Heat.1995.2160p.UHD.mkv", 60_000_000_000),
                file("Heat.1995.720p.mkv", 4_000_000_000),
                file("Heat.1995.1080p.mkv", 12_000_000_000),
            ),
        )
        assertEquals(1080, index.movie("Heat", 1995)!!.quality)
    }

    @Test
    fun matchesEpisodesOfAShow() {
        val index = RdIndex(
            listOf(
                file("Northern.Lights.S01E04.1080p.mkv", 2_100_000_000),
                file("Northern.Lights.S01E05.1080p.mkv"),
            ),
        )
        assertEquals("1080p · 2.1 GB", index.episode("US| Northern Lights", 2024, 1, 4)!!.label)
        assertNull(index.episode("Northern Lights", null, 2, 4))
        assertTrue(index.hasShow("Northern Lights", null))
        assertFalse(index.hasShow("Southern Lights", null))
        assertNull(index.movie("Northern Lights", null)) // episodes are never a movie
    }

    @Test
    fun bestFirstPicks4kThenSurroundThenSize() {
        val files = listOf(
            file("Heat.1995.1080p.BluRay.DTS-HD.MA.5.1.mkv", 20_000_000_000),
            file("Heat.1995.2160p.WEB.DDP5.1.mkv", 18_000_000_000),
            file("Heat.1995.2160p.WEB.AAC.mkv", 25_000_000_000),
            file("Heat.1995.mkv", 40_000_000_000),
        )
        assertEquals("Heat.1995.2160p.WEB.DDP5.1.mkv", RdIndex(files, bestFirst = true).movie("Heat", 1995)!!.file.name)
        assertEquals(1080, RdIndex(files).movie("Heat", 1995)!!.quality) // the setting off keeps today's order
        val sameSound = listOf(file("Heat.1995.2160p.A.mkv", 10_000_000_000), file("Heat.1995.2160p.B.mkv", 30_000_000_000))
        assertEquals("Heat.1995.2160p.B.mkv", RdIndex(sameSound, bestFirst = true).movie("Heat", 1995)!!.file.name)
    }

    @Test
    fun surroundNames() {
        listOf("Film.Atmos.mkv", "Film.TrueHD.7.1.mkv", "Film.DTS.mkv", "Film.DD+.mkv", "Film.DDP5.1.mkv", "Film.EAC3.mkv", "Film.AC3.mkv", "Film.DD5.1.mkv", "Film 5.1.mkv")
            .forEach { assertTrue(it, RdNames.isSurround(it)) }
        listOf("Film.AAC2.0.mkv", "Credits.mkv", "Film.2015.1080p.mkv", "Odds.and.Ends.mkv").forEach { assertFalse(it, RdNames.isSurround(it)) }
    }

    @Test
    fun searchListsAccountTitlesTheCatalogLacks() {
        val index = RdIndex(
            listOf(
                file("Northern.Lights.S01E05.720p.mkv", 900_000_000),
                file("Northern.Lights.S01E04.1080p.mkv", 1_200_000_000),
                file("Northern.Lights.S01E04.720p.mkv", 800_000_000),
                file("Northern.Exposure.1990.1080p.mkv"),
                file("Heat.1995.1080p.mkv"),
            ),
        )
        val found = index.search("northern", emptySet())
        assertEquals(listOf("Northern Exposure", "Northern Lights"), found.map { it.title })
        val show = found.single { it.isShow }
        assertEquals(listOf("S01E04 · 1080p · 1.2 GB", "S01E05 · 720p · 900 MB"), show.episodes.map { it.label })
        assertEquals(1990, found.single { !it.isShow }.year)
        assertEquals(listOf("Northern Lights"), index.search("Northern", setOf("northernexposure")).map { it.title })
        assertTrue(index.search(" ", emptySet()).isEmpty())
    }

    @Test
    fun sizeLabels() {
        assertEquals("850 MB", RdCopy.size(850_000_000))
        assertNull(RdCopy.size(0))
    }

    private fun file(name: String, bytes: Long = 1_000_000_000) = RdFile(name, "", bytes, "https://real-debrid.com/d/$name")
}
