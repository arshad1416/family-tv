package app.familytv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {
    @Test
    fun namesAreStoredWithoutAccentsOneSpacePerWord() {
        assertEquals(" pokemon the first movie", SearchText.index("Pokémon: The First Movie"))
        assertEquals(" amelie 2001", SearchText.index("AMÉLIE (2001)"))
        assertEquals(" spider man no way home | spiderman", SearchText.index("Spider-Man: No Way Home"))
        assertEquals("", SearchText.index("--"))
    }

    @Test
    fun everyWordMustStartAWordAndShortWordsNeverMatchInside() {
        val sql = SearchText.query("movies", "name", "  Pokemon  fi", 20)!!.sql
        assertTrue(sql.startsWith("SELECT * FROM movies WHERE (searchText LIKE ? AND searchText LIKE ?) OR (searchText = '' AND name LIKE ?)"))
        // Every word 3 letters or more: names that merely contain them fill in after.
        assertTrue(SearchText.query("movies", "name", "erman", 20)!!.sql.contains("WHERE (searchText LIKE ?) OR (searchText LIKE ?) OR"))
    }

    @Test
    fun titlesAreIndexedWithoutProviderTags() {
        assertEquals(" up 2009", SearchText.titleIndex("EN - Up (2009)", null))
        assertEquals(" dune 1984", SearchText.titleIndex("Dune", 1984))
        assertEquals(" cp24 hd", SearchText.channelIndex("CA| CP24 HD"))
    }

    @Test
    fun rankIsExactThenWholeWordsThenWordStarts() {
        assertEquals(0, SearchText.rank(" up 2009", "up"))
        assertEquals(0, SearchText.rank(" the office", "office"))
        assertEquals(1, SearchText.rank(" up in the air 2009", "up"))
        assertEquals(2, SearchText.rank(" what s up doc 1972", "up"))
        assertEquals(3, SearchText.rank(" upgrade 2018", "up"))
        assertEquals(4, SearchText.rank(" what s upgrade", "up"))
        assertNull(SearchText.rank(" superman 1978", "up"))
    }

    @Test
    fun theStoredFormMatchesTheWayFts5WouldMatchIt() {
        // What `searchText LIKE '% word%'` does, word by word.
        fun hit(name: String, q: String) = SearchText.words(q).all { w -> SearchText.index(name).contains(" $w") }
        assertEquals(true, hit("Pokémon: The First Movie", "pokemon"))
        assertEquals(true, hit("Pokémon: The First Movie", "first poke"))
        assertEquals(true, hit("Star Wars", "STAR WA"))
        assertEquals(false, hit("Grand Tour", "ran"))
        assertEquals(false, hit("Star Wars", "star trek"))
    }

    @Test
    fun hyphenAndApostropheTitlesMatchTheJoinedSpelling() {
        // Ticket 1: "spiderman" found nothing, only "spider man" did.
        val spider = SearchText.titleIndex("EN - Spider-Man: No Way Home (2021)", null)
        assertEquals(" spider man no way home 2021 | spiderman", spider)
        assertEquals(2, SearchText.rank(spider, "spiderman"))
        assertEquals(2, SearchText.rank(spider, "spiderman no way"))
        assertTrue(SearchText.matches("X-Men: First Class", "xmen"))
        assertTrue(SearchText.matches("Ocean's Eleven", "oceans eleven"))
        assertTrue(SearchText.matches("S.W.A.T.", "swat"))
        assertNull(SearchText.rank(spider, "spidermen"))
    }

    @Test
    fun joinedSpellingsDontSpoilTheExactTitle() {
        assertEquals(0, SearchText.rank(SearchText.titleIndex("Spider-Man", 2002), "spider man"))
        assertEquals(0, SearchText.rank(SearchText.titleIndex("Spider-Man", 2002), "spider-man"))
        assertEquals(1, SearchText.rank(SearchText.titleIndex("Spider-Man: No Way Home", 2021), "spider man"))
        assertEquals(" the office", SearchText.index("The Office"))
        assertEquals(" dune 1984", SearchText.titleIndex("Dune", 1984))
        // The SQL exact tier also takes a stored form with joined spellings after it.
        val args = SearchText.query("movies", SearchText.TITLE_ORDER, "spider man", 20)!!.let { q ->
            val sink = mutableListOf<Any?>()
            q.bindTo(object : androidx.sqlite.db.SupportSQLiteProgram {
                override fun bindNull(index: Int) { sink += null }
                override fun bindLong(index: Int, value: Long) { sink += value }
                override fun bindDouble(index: Int, value: Double) { sink += value }
                override fun bindString(index: Int, value: String) { sink += value }
                override fun bindBlob(index: Int, value: ByteArray) { sink += value }
                override fun clearBindings() {}
                override fun close() {}
            })
            sink
        }
        assertTrue(args.contains(" spider man | *"))
        assertTrue(args.contains(" spider man [12][0-9][0-9][0-9] | *"))
    }

    @Test
    fun noWordsNoSearch() {
        assertNull(SearchText.query("movies", "name", " -: ", 20))
    }
}
