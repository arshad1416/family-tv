package app.familytv

import app.familytv.iptv.XtreamParser
import app.familytv.trakt.MediaIds
import app.familytv.trakt.ProgressRules
import app.familytv.trakt.TitleMatch
import app.familytv.trakt.WatchlistMerge
import app.familytv.trakt.WatchlistMerge.Op
import app.familytv.user.ProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleMatchTest {
    @Test
    fun parseStripsProviderTagsQualityAndYear() {
        assertEquals(TitleMatch.Parsed("The Matrix", 1999), TitleMatch.parse("EN - The Matrix (1999) [4K]"))
        assertEquals(TitleMatch.Parsed("Dune: Part Two", 2024), TitleMatch.parse("|EN| Dune: Part Two - 2024"))
        assertEquals(TitleMatch.Parsed("Amélie", 2001), TitleMatch.parse("[FR] Amélie (2001) MULTI-SUB"))
        assertEquals(TitleMatch.Parsed("Oppenheimer", null), TitleMatch.parse("NF: Oppenheimer FHD"))
    }

    @Test
    fun parseKeepsTitlesThatLookLikeTagsOrYears() {
        assertEquals("WALL-E", TitleMatch.parse("WALL-E (2008)").title)
        assertEquals(TitleMatch.Parsed("1917", null), TitleMatch.parse("1917"))
        assertEquals(TitleMatch.Parsed("2012", 2009), TitleMatch.parse("2012 (2009)"))
    }

    @Test
    fun keyIgnoresCaseAccentsPunctuationAndLeadingThe() {
        assertEquals(TitleMatch.key("Amelie"), TitleMatch.key("Amélie"))
        assertEquals(TitleMatch.key("Spider-Man: No Way Home"), TitleMatch.key("Spider Man No Way Home"))
        assertEquals(TitleMatch.key("Fast & Furious"), TitleMatch.key("Fast and Furious"))
        assertEquals(TitleMatch.key("The Office"), TitleMatch.key("Office"))
    }

    @Test
    fun sameAllowsOneYearOfDrift() {
        assertTrue(TitleMatch.same("Dune", 2021, "Dune", 2021))
        assertTrue(TitleMatch.same("Dune", 2021, "Dune", 2020))
        assertFalse(TitleMatch.same("Dune", 2021, "Dune", 1984))
        assertTrue(TitleMatch.same("Dune", null, "Dune", 1984))
        assertFalse(TitleMatch.same("Dune", null, "Dunkirk", null))
    }

    @Test
    fun bestPrefersExactYearAndRejectsOtherTitles() {
        val hits = listOf(
            TitleMatch.Candidate("Dune", 1984, "old"),
            TitleMatch.Candidate("Dune", 2021, "new"),
            TitleMatch.Candidate("Dune: Part Two", 2024, "two"),
        )
        assertEquals("new", TitleMatch.best("EN - Dune (2021)", null, hits))
        assertEquals("old", TitleMatch.best("Dune", 1984, hits))
        assertEquals("old", TitleMatch.best("Dune", null, hits)) // no year: Trakt's first hit
        assertNull(TitleMatch.best("Dune (1999)", null, hits))
        assertNull(TitleMatch.best("Arrival", 2016, hits))
    }

    @Test
    fun catalogMatchesUsesTheProviderNameAndYear() {
        val rows = listOf(
            TitleMatch.Candidate("EN - Dune (2021)", null, 1),
            TitleMatch.Candidate("Dune", 1984, 2),
            TitleMatch.Candidate("4K - Dune 2021", null, 3),
            TitleMatch.Candidate("Dune: Part Two", 2024, 4),
        )
        assertEquals(listOf(1, 3), TitleMatch.catalogMatches("Dune", 2021, rows))
    }
}

class WatchlistMergeTest {
    private val matrix = MediaIds(tmdb = "603")
    private val dune = MediaIds(tmdb = "438631", imdb = "tt1160419")
    private val office = MediaIds(tmdb = "2316")

    private fun local(key: String, ids: MediaIds, pending: Op? = null, onTrakt: Boolean = false, kind: String = "movie") =
        WatchlistMerge.LocalFav(key, kind, ids, pending, onTrakt)

    private fun remote(ids: MediaIds, kind: String = "movie") = WatchlistMerge.Remote(kind, ids, "t", null)

    @Test
    fun newFavouritesArePushedAndRemoteOnesPulled() {
        val plan = WatchlistMerge.plan(listOf(local("m", matrix, Op.ADD)), listOf(remote(dune)))
        assertEquals(listOf("m"), plan.pushAdd.map { it.key })
        assertEquals(listOf(dune), plan.pull.map { it.ids })
        assertTrue(plan.deleteLocal.isEmpty())
    }

    @Test
    fun addAlreadyOnTraktIsJustMarkedSynced() {
        val plan = WatchlistMerge.plan(listOf(local("m", matrix, Op.ADD)), listOf(remote(MediaIds(tmdb = "603", trakt = 1))))
        assertEquals(listOf("m"), plan.markSynced)
        assertTrue(plan.pushAdd.isEmpty())
        assertTrue(plan.pull.isEmpty())
    }

    @Test
    fun removedOnAnotherDeviceIsRemovedHere() {
        val plan = WatchlistMerge.plan(listOf(local("m", matrix, onTrakt = true), local("d", dune, onTrakt = true)), listOf(remote(dune)))
        assertEquals(listOf("m"), plan.deleteLocal)
        assertTrue(plan.markSynced.isEmpty()) // "d" is already in step
    }

    @Test
    fun removalHereIsPushedOnceAndNotPulledBack() {
        // The same movie from two providers, both removed here.
        val plan = WatchlistMerge.plan(
            listOf(local("a", matrix, Op.REMOVE, onTrakt = true), local("b", MediaIds(imdb = "tt0133093", tmdb = "603"), Op.REMOVE, onTrakt = true)),
            listOf(remote(MediaIds(tmdb = "603", imdb = "tt0133093"))),
        )
        assertEquals(listOf("a"), plan.pushRemove.map { it.key })
        assertEquals(listOf("b"), plan.deleteLocal)
        assertTrue(plan.pull.isEmpty())
    }

    @Test
    fun tombstoneForSomethingAlreadyGoneIsDropped() {
        val plan = WatchlistMerge.plan(listOf(local("m", matrix, Op.REMOVE, onTrakt = true)), emptyList())
        assertEquals(listOf("m"), plan.deleteLocal)
        assertTrue(plan.pushRemove.isEmpty())
    }

    @Test
    fun unmatchedFavouritesStayLocal() {
        val plan = WatchlistMerge.plan(listOf(local("x", MediaIds(), Op.ADD)), emptyList())
        assertEquals(WatchlistMerge.Plan<String>(), plan)
    }

    @Test
    fun moviesAndShowsDoNotMatchEachOther() {
        val plan = WatchlistMerge.plan(listOf(local("s", office, onTrakt = true, kind = "series")), listOf(remote(office, kind = "movie")))
        assertEquals(listOf("s"), plan.deleteLocal)
        assertEquals(1, plan.pull.size)
    }

    @Test
    fun favouriteFromBeforeConnectingIsPushed() {
        val plan = WatchlistMerge.plan(listOf(local("m", matrix)), emptyList())
        assertEquals(listOf("m"), plan.pushAdd.map { it.key })
    }
}

class ProgressRulesTest {
    private fun p(percent: Float, positionMs: Long = 0, watched: Boolean = false) =
        ProgressEntity("k", "movie", "s", "1", positionMs = positionMs, percent = percent, watched = watched)

    @Test
    fun percentAndWatchedLine() {
        assertEquals(50f, ProgressRules.percent(30_000, 60_000), 0.001f)
        assertEquals(0f, ProgressRules.percent(30_000, 0), 0.001f)
        assertTrue(ProgressRules.isWatched(80f))
        assertFalse(ProgressRules.isWatched(79.9f))
    }

    @Test
    fun resumesFromAnotherDevicesPercentage() {
        assertEquals(3_600_000L, ProgressRules.resumeAt(p(50f), 7_200_000L))
        // No duration yet: fall back to this device's own position.
        assertEquals(90_000L, ProgressRules.resumeAt(p(0f, positionMs = 90_000), 0))
    }

    @Test
    fun streamWithNoLengthStillShowsResume() {
        assertTrue(ProgressRules.started(p(0f, positionMs = 90_000)))
        assertFalse(ProgressRules.started(p(0f, positionMs = 10_000)))
        assertTrue(ProgressRules.started(p(50f)))
        assertFalse(ProgressRules.started(p(85f)))
        assertFalse(ProgressRules.started(null))
    }

    @Test
    fun startsOverWhenNearlyUntouchedOrFinished() {
        assertNull(ProgressRules.resumeAt(null, 7_200_000L))
        assertNull(ProgressRules.resumeAt(p(0.2f), 7_200_000L)) // 14 s in
        assertNull(ProgressRules.resumeAt(p(85f), 7_200_000L))
        // A rewatch of something watched before still resumes.
        assertEquals(3_600_000L, ProgressRules.resumeAt(p(50f, watched = true), 7_200_000L))
    }

    @Test
    fun onlyNewerRemotePointsWin() {
        val local = p(40f).copy(updatedAt = 1_000)
        assertTrue(ProgressRules.remoteIsNewer(2_000, local))
        assertFalse(ProgressRules.remoteIsNewer(500, local))
        assertTrue(ProgressRules.remoteIsNewer(0, null))
    }

    @Test
    fun barHidesFinishedItems() {
        assertEquals(0.4f, ProgressRules.bar(p(40f)), 0.001f)
        assertEquals(0f, ProgressRules.bar(p(95f)), 0.001f)
        assertEquals(0f, ProgressRules.bar(null), 0.001f)
    }
}

class XtreamIdsTest {
    @Test
    fun streamsCarryTmdbAndYearWhenListed() {
        val json = """[{"stream_id":7,"name":"Dune","tmdb":"438631","year":"2021","category_id":"1"},
            {"stream_id":8,"name":"X","tmdb_id":0,"releaseDate":"1999-03-31","category_id":"1"},
            {"series_id":9,"name":"Y","tmdb":"","category_id":"2"}]"""
        val out = mutableListOf<app.familytv.iptv.XtStream>()
        XtreamParser.streams(json.reader()) { out += it }
        assertEquals(listOf("438631", null, null), out.map { it.tmdb })
        assertEquals(listOf(2021, 1999, null), out.map { it.year })
    }

    @Test
    fun infoIdsReadsVodAndSeriesInfo() {
        val vod = """{"info":{"tmdb_id":"603","imdb_id":"tt0133093","releasedate":"1999-03-31","genre":"Action"},"movie_data":{"stream_id":7}}"""
        val ids = XtreamParser.infoIds(vod.reader())
        assertEquals("603", ids.tmdb)
        assertEquals("tt0133093", ids.imdb)
        assertEquals(1999, ids.year)
        val series = """{"seasons":[],"info":{"name":"The Office","tmdb":"2316","imdb":"n/a","releaseDate":"2005-03-24"},"episodes":{"1":[]}}"""
        val s = XtreamParser.infoIds(series.reader())
        assertEquals("2316", s.tmdb)
        assertNull(s.imdb)
        assertNull(XtreamParser.infoIds("[]".reader()).tmdb)
    }
}
