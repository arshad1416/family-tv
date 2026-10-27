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
        assertEquals(" spider man no way home", SearchText.index("Spider-Man: No Way Home"))
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
    fun noWordsNoSearch() {
        assertNull(SearchText.query("movies", "name", " -: ", 20))
    }
}
