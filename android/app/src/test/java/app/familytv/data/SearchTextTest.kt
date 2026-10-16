package app.familytv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun everyWordMustStartAWord() {
        val (prefix, contains) = SearchText.queries("movies", "name", "  Pokemon  fir", 20)!!
        assertEquals("SELECT * FROM movies WHERE searchText LIKE ? AND searchText LIKE ? ORDER BY name LIMIT ?", prefix.sql)
        assertEquals(3, prefix.argCount)
        assertEquals("SELECT * FROM movies WHERE searchText LIKE ? OR (searchText = '' AND name LIKE ?) ORDER BY name LIMIT ?", contains.sql)
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
        assertNull(SearchText.queries("movies", "name", " -: ", 20))
    }

    @Test
    fun prefixMatchesComeFirstThenContainsFillsTheRest() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        val found = SearchText.run<String>("ran", 3, "movies", "name", { it }) { q ->
            calls += q.sql
            if (calls.size == 1) listOf("Rango") else listOf("Rango", "Grand Tour", "Brand New", "Orange")
        }
        assertEquals(listOf("Rango", "Grand Tour", "Brand New"), found)
        assertEquals(2, calls.size)
    }
}
