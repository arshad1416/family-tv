package app.familytv

import app.familytv.iptv.EpisodeGuide
import app.familytv.iptv.SeriesTitles
import app.familytv.iptv.SeriesTitles.ORIGINAL
import app.familytv.iptv.XtreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesTitlesTest {
    private fun title(raw: String) = SeriesTitles.parse(raw).title
    private fun lang(raw: String) = SeriesTitles.parse(raw).lang
    private fun key(raw: String) = SeriesTitles.parse(raw).key

    @Test
    fun languagePrefixesAreStrippedAndDetected() {
        assertEquals("Breaking Bad", title("EN - Breaking Bad"))
        assertEquals("en", lang("EN - Breaking Bad"))
        assertEquals("fr", lang("FR: Breaking Bad"))
        assertEquals("ar", lang("|AR| Breaking Bad"))
        assertEquals("Breaking Bad", title("|AR| Breaking Bad"))
        assertEquals("de", lang("[DE] Breaking Bad"))
        assertEquals("tr", lang("TR ▎ Kuruluş Osman"))
        assertEquals("Kuruluş Osman", title("TR ▎ Kuruluş Osman"))
        assertEquals("es-419", lang("LAT - La Casa de Papel"))
        assertEquals("en", lang("4K-EN - The Last of Us"))
        assertEquals("The Last of Us", title("4K-EN - The Last of Us"))
        assertEquals("en", lang("NF: EN - Stranger Things"))
        assertEquals("Stranger Things", title("NF: EN - Stranger Things"))
    }

    @Test
    fun shopWindowWordsGoOnlyBesideATag() {
        assertEquals("Dune", title("4K-TOP - Dune"))
        assertEquals(listOf("4K"), SeriesTitles.parse("4K-TOP - Dune").tags)
        assertEquals("Oppenheimer", title("NEW-4K - Oppenheimer"))
        assertEquals("Moana", title("4K-D+ - Moana"))
        // Alone in capitals before a separator they are a tag too.
        assertEquals("The Mandalorian", title("TOP - The Mandalorian"))
        assertEquals("The Bear", title("NEW | The Bear"))
        assertEquals("Shogun", title("VIP: Shogun"))
        assertEquals("Fighting Mulan", title("AS - Fighting Mulan"))
        assertEquals("The Office", title("CA EN: The Office"))
        assertEquals("en", lang("CA EN: The Office"))
        assertEquals("Star Wars: A New Hope", title("Star Wars: A New Hope"))
        // Otherwise they are part of the name.
        assertEquals("Top - Gun", title("Top - Gun"))
        assertEquals("New - Girl", title("New - Girl"))
        assertEquals("Top Gun", title("Top Gun"))
        assertEquals("Kung Fu Panda HOT", title("Kung Fu Panda HOT"))
    }

    @Test
    fun aNameIsParsedOnce() {
        // Posters re-read their names as a shelf scrolls; the second read is the first one's result.
        assertSame(SeriesTitles.parse("4K-TOP - Dune"), SeriesTitles.parse("4K-TOP - Dune"))
    }

    @Test
    fun qualityBadgeSaysFourKAndHdr() {
        assertEquals("4K", SeriesTitles.qualityBadge(listOf("4K-TOP - Dune")))
        assertEquals("4K HDR", SeriesTitles.qualityBadge(listOf("EN - Dune [4K]", "Dune (HDR10)")))
        assertEquals("HDR", SeriesTitles.qualityBadge(listOf("Dune - DV")))
        assertNull(SeriesTitles.qualityBadge(listOf("EN - Dune FHD")))
    }

    @Test
    fun languageSuffixesAreStrippedAndDetected() {
        assertEquals("Dark", title("Dark (DE)"))
        assertEquals("de", lang("Dark (DE)"))
        assertEquals("es", lang("Élite [ES]"))
        assertEquals("fr", lang("Lupin - FR"))
        assertEquals("Lupin", title("Lupin - FR"))
        assertEquals("fr", lang("Lupin (French)"))
        assertEquals("ar", lang("Al Hayba (Arabic) [FHD]"))
        assertEquals("Al Hayba", title("Al Hayba (Arabic) [FHD]"))
    }

    @Test
    fun qualityTagsAndYearsAreStripped() {
        assertEquals("The Bear", title("The Bear [4K]"))
        assertEquals("The Bear", title("The Bear FHD"))
        assertEquals("The Bear", title("The Bear (2022)"))
        assertEquals("The Bear", title("EN - The Bear (2022) [4K]"))
        assertEquals(listOf("4K"), SeriesTitles.parse("EN - The Bear (2022) [4K]").tags)
        assertNull(lang("The Bear [4K]"))
    }

    @Test
    fun realTitlesAreLeftAlone() {
        // Words that look like tags but are the title.
        assertEquals("CSI: Vegas", title("CSI: Vegas"))
        assertEquals("FBI: Most Wanted", title("FBI: Most Wanted"))
        assertEquals("Spider-Man: Into the Spider-Verse", title("Spider-Man: Into the Spider-Verse"))
        assertEquals("1923", title("1923"))
        assertEquals("Blade Runner 2049", title("Blade Runner 2049"))
        assertEquals("The Office (US)", title("The Office (US)"))
        // Lower-case "It" is a word, not Italian.
        assertNull(lang("It - Welcome to Derry"))
        assertNull(lang("No Good Deed"))
    }

    @Test
    fun keysIgnoreCaseAccentsAndPunctuation() {
        assertEquals("elite", key("Élite [ES]"))
        assertEquals(key("EN - Grey's Anatomy"), key("Grey’s Anatomy (FR)"))
        assertEquals(key("Law & Order: SVU"), key("LAW AND ORDER - SVU"))
        assertEquals("the office us", key("The Office (US)"))
    }

    @Test
    fun categoriesNameTheLanguage() {
        assertEquals("en", SeriesTitles.fromCategory("EN | Series"))
        assertEquals("ar", SeriesTitles.fromCategory("|AR| مسلسلات"))
        assertEquals("fr", SeriesTitles.fromCategory("FRENCH SERIES"))
        assertEquals("ar", SeriesTitles.fromCategory("SERIES ARABIC"))
        assertEquals("en", SeriesTitles.fromCategory("SERIES - ENG"))
        assertEquals("en", SeriesTitles.fromCategory("UK | Series"))
        assertEquals("tr", SeriesTitles.fromCategory("TR ▎DIZILER"))
        assertNull(SeriesTitles.fromCategory("NETFLIX SERIES"))
        assertNull(SeriesTitles.fromCategory("Kids"))
        assertNull(SeriesTitles.fromCategory("It's Kids Time"))
        assertEquals("fr", SeriesTitles.detect("Lupin", "FR | Séries"))
        assertEquals("en", SeriesTitles.detect("EN - Lupin", "FR | Séries")) // the title wins
        assertEquals(ORIGINAL, SeriesTitles.detect("Lupin", "Netflix"))
    }

    private data class Row(val id: String, val name: String, val tmdb: String? = null)

    private fun groups(vararg rows: Row) =
        SeriesTitles.group(rows.toList(), { it.tmdb }, { SeriesTitles.parse(it.name).key }).map { (k, v) -> k to v.map { it.id } }

    @Test
    fun copiesInOtherLanguagesGroupByTitle() {
        val g = groups(Row("1", "EN - Money Heist"), Row("2", "Money Heist (ES)"), Row("3", "AR: Money Heist [FHD]"), Row("4", "Dark"))
        assertEquals(listOf("title:money heist" to listOf("1", "2", "3"), "title:dark" to listOf("4")), g)
    }

    @Test
    fun tmdbIdWinsOverTitle() {
        // Translated titles share the TMDB id; same-named different shows don't.
        val g = groups(Row("1", "EN - Money Heist", "71446"), Row("2", "ES - La Casa de Papel", "71446"), Row("3", "The Office", "2316"), Row("4", "The Office", "2996"))
        assertEquals(listOf("tmdb:71446" to listOf("1", "2"), "tmdb:2316" to listOf("3"), "tmdb:2996" to listOf("4")), g)
    }

    @Test
    fun copyWithoutIdJoinsTheOnlyMatchingTmdbGroup() {
        assertEquals(listOf("tmdb:1396" to listOf("1", "2")), groups(Row("1", "EN - Breaking Bad", "1396"), Row("2", "FR - Breaking Bad")))
        // Ambiguous: two "The Office" ids, so the copy without one stays on its own.
        val g = groups(Row("1", "The Office", "2316"), Row("2", "The Office", "2996"), Row("3", "FR - The Office"))
        assertEquals(listOf("tmdb:2316", "tmdb:2996", "title:the office"), g.map { it.first })
    }

    @Test
    fun sameShowRule() {
        assertTrue(SeriesTitles.sameShow("1", "a", "1", "b"))
        assertFalse(SeriesTitles.sameShow("1", "a", "2", "a"))
        assertTrue(SeriesTitles.sameShow(null, "a", "2", "a"))
        assertFalse(SeriesTitles.sameShow(null, "", null, ""))
    }

    @Test
    fun chooseVariantPrefersShowThenLastThenDevice() {
        val langs = listOf("fr", "en", "ar")
        assertEquals(2, SeriesTitles.chooseVariant(langs, listOf("ar", "fr", "en")))
        assertEquals(0, SeriesTitles.chooseVariant(langs, listOf(null, "fr", "en")))
        assertEquals(1, SeriesTitles.chooseVariant(langs, listOf(null, "de", "en")))
        assertEquals(1, SeriesTitles.chooseVariant(listOf("es-419", "es", ORIGINAL), listOf("es")))
        assertEquals(0, SeriesTitles.chooseVariant(listOf("es-419", ORIGINAL), listOf("es")))
        assertEquals(1, SeriesTitles.chooseVariant(listOf("fr", ORIGINAL), listOf("de")))
        assertEquals(0, SeriesTitles.chooseVariant(listOf("fr", "ar"), listOf("de")))
    }

    @Test
    fun labelsTellSameLanguageCopiesApart() {
        assertEquals(
            listOf("English · 4K", "English · FHD", "French", "Original"),
            SeriesTitles.variantLabels(listOf("en" to listOf("4K"), "en" to listOf("FHD"), "fr" to emptyList(), ORIGINAL to emptyList())),
        )
        assertEquals(listOf("Arabic · 1", "Arabic · 2"), SeriesTitles.variantLabels(listOf("ar" to emptyList(), "ar" to emptyList())))
    }

    // ---- episodes ----

    private fun ep(s: Int, e: Int, title: String = "") = XtreamParser.Episode("$s-$e", s, e, title, null, null, null)

    @Test
    fun seasonsInOrderWithSpecialsLast() {
        assertEquals(listOf(1, 2, 0), EpisodeGuide.seasons(listOf(ep(2, 1), ep(0, 1), ep(1, 2), ep(1, 1))))
        assertEquals("Specials", EpisodeGuide.seasonLabel(0))
        assertEquals(2, EpisodeGuide.defaultSeason(listOf(1, 2), 2))
        assertEquals(1, EpisodeGuide.defaultSeason(listOf(1, 2), 7))
        assertNull(EpisodeGuide.defaultSeason(emptyList(), null))
    }

    @Test
    fun nextUpIsInProgressThenAfterLastWatched() {
        val eps = listOf(ep(1, 1), ep(1, 2), ep(2, 1), ep(0, 1))
        assertEquals("1-1", EpisodeGuide.nextUp(eps, { false }, { false })?.id)
        assertEquals("2-1", EpisodeGuide.nextUp(eps, { it.id == "1-2" }, { false })?.id)
        assertEquals("1-2", EpisodeGuide.nextUp(eps, { it.id == "1-1" || it.id == "2-1" }, { it.id == "1-2" })?.id)
        assertNull(EpisodeGuide.nextUp(emptyList(), { false }, { false }))
    }

    @Test
    fun episodeTitlesAndRuntimes() {
        assertEquals("The Swarm", EpisodeGuide.title(ep(1, 3, "Breaking Bad - S01E03 - The Swarm")))
        assertEquals("Pilot", EpisodeGuide.title(ep(1, 1, "Pilot")))
        assertEquals("Episode 4", EpisodeGuide.title(ep(2, 4, "Show S02E04")))
        assertEquals(2530, EpisodeGuide.seconds("00:42:10"))
        assertEquals(2530, EpisodeGuide.seconds("42:10"))
        assertNull(EpisodeGuide.seconds("n/a"))
        assertEquals("42 min", EpisodeGuide.runtime(2530))
        assertEquals("1 h 5 min", EpisodeGuide.runtime(3900))
        assertNull(EpisodeGuide.runtime(null))
        assertEquals(6300, EpisodeGuide.durationSecs("60", "01:45:00"))
        assertEquals(3480, EpisodeGuide.durationSecs("3480", null))
        assertNull(EpisodeGuide.durationSecs("0", ""))
    }

    @Test
    fun anImageAddressIsNeverShownAsText() {
        val json = """{"seasons":[{"season_number":1,"overview":"https://img.example/s1.jpg"}],"info":{"plot":"www.example.com/c.jpg"},"episodes":{}}"""
        val info = XtreamParser.seriesInfo(json.reader())
        assertNull(info.seasons[0].overview)
        assertNull(info.plot)
    }

    @Test
    fun seriesInfoKeepsStillsSynopsesAndSeasons() {
        val json = """{"seasons":[{"season_number":1,"name":"Season 1","overview":"It begins.","cover_big":"http://i/s1.jpg","air_date":"2008-01-20"},{"season_number":"2","overview":""}],
            "info":{"name":"Show","plot":"A teacher.","cover":"http://i/c.jpg","youtube_trailer":"HhesaQXLuRY","backdrop_path":["http://i/b.jpg"]},
            "episodes":{"1":[{"id":"11","episode_num":"1","title":"Show - S01E01 - Pilot","container_extension":"mkv",
              "info":{"movie_image":"http://i/e1.jpg","plot":"Walt starts.","duration_secs":"3480","releasedate":"2008-01-20","rating":"8.2"}},
              {"id":"12","episode_num":2,"title":"","info":{"cover_big":"https://i/e2.jpg","plot":"","duration":"00:47:00","rating":"0","youtube_trailer":"https://www.youtube.com/watch?v=abcdefghijk"}}],
            "2":{"0":{"id":"21","episode_num":1,"info":[]}}}}"""
        val info = XtreamParser.seriesInfo(json.reader())
        assertEquals("A teacher.", info.plot)
        assertEquals("http://i/c.jpg", info.cover)
        assertEquals("HhesaQXLuRY", info.trailer)
        assertEquals(listOf(1, 2), info.seasons.map { it.number })
        assertEquals("It begins.", info.seasons[0].overview)
        assertNull(info.seasons[1].overview)
        val (e1, e2, e3) = info.episodes
        assertEquals("http://i/e1.jpg", e1.image)
        assertEquals("Walt starts.", e1.plot)
        assertEquals(3480, e1.durationSecs)
        assertEquals("2008-01-20", e1.airDate)
        assertEquals("8.2", e1.rating)
        assertEquals("https://i/e2.jpg", e2.image)
        assertNull(e2.plot)
        assertNull(e2.rating)
        assertEquals(2820, e2.durationSecs)
        assertEquals("abcdefghijk", e2.trailer)
        assertNull(e1.trailer)
        assertEquals("21", e3.id)
        assertEquals(2, e3.season)
        assertNull(e3.image)
    }
}
