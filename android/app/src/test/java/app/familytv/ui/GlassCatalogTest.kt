package app.familytv.ui

import app.familytv.data.SearchText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassCatalogTest {
    private fun provider(name: String) = Provider.entries.filter { it.matches(name) }

    @Test
    fun providersMatchTheirCategories() {
        assertEquals(listOf(Provider.Netflix), provider("NETFLIX MOVIES"))
        assertEquals(listOf(Provider.Paramount), provider("PARAMOUNT PICTURES"))
        assertEquals(listOf(Provider.Paramount), provider("PARAMOUNT+ SERIES"))
        assertEquals(listOf(Provider.Prime), provider("AMAZON PRIME MOVIES"))
        assertEquals(listOf(Provider.AppleTv), provider("APPLE+ SERIES"))
        assertEquals(listOf(Provider.Sky), provider("SKY CINEMA"))
        assertEquals(listOf(Provider.Nickelodeon), provider("NICK JR"))
        assertEquals(emptyList<Provider>(), provider("EN - ACTION"))
        // A language's own provider list stays with the language.
        assertEquals(emptyList<Provider>(), provider("FR - NETFLIX FILMS"))
        // Whole words only: "SKYFALL" is a film, not Sky.
        assertEquals(emptyList<Provider>(), provider("SKYFALL COLLECTION"))
    }

    @Test
    fun languageGroupsReadTheNameStart() {
        assertTrue(LanguageGroup.English.matches("EN - ACTION"))
        assertTrue(LanguageGroup.English.matches("ENGLISH SERIES"))
        assertTrue(LanguageGroup.Hindi.matches("IN - BOLLYWOOD"))
        assertTrue(LanguageGroup.Hindi.matches("INDIA MOVIES"))
        assertTrue(LanguageGroup.Pakistani.matches("PK - DRAMA"))
        assertTrue(LanguageGroup.Pakistani.matches("PAKISTAN DRAMAS"))
        assertTrue(LanguageGroup.Persian.matches("PERSIAN MOVIES"))
        assertTrue(LanguageGroup.Philippines.matches("PH - TAGALOG"))
        assertTrue(LanguageGroup.Quebec.matches("QC - FILMS"))
        assertFalse(LanguageGroup.English.matches("NETFLIX ENGLISH"))
        assertFalse(LanguageGroup.Hindi.matches("INDEPENDENT FILMS"))
        // Places are regions, with place names; languages are languages.
        assertEquals(listOf("Pakistan", "Philippines", "Québec", "Africa", "Asia"), LanguageGroup.entries.filter { it.region }.map { it.label })
        assertFalse(LanguageGroup.Hindi.region)
    }

    @Test
    fun liveGenres() {
        assertEquals(LiveGenre.News, LiveGenre.of("UK| NEWS ᴴᴰ"))
        assertEquals(LiveGenre.Sports, LiveGenre.of("UK| SPORT ᴴᴰ"))
        assertEquals(LiveGenre.Religious, LiveGenre.of("AR| QURAN 4K"))
        assertEquals(LiveGenre.Kids, LiveGenre.of("CA| KIDS"))
        assertEquals(LiveGenre.Entertainment, LiveGenre.of("CA| CANADA HD"))
        assertEquals(LiveGenre.Entertainment, LiveGenre.of("UK| GENERAL ᴴᴰ"))
        assertEquals(LiveGenre.Other, LiveGenre.of("4K| RELAX"))
        assertEquals(LiveGenre.Adult, LiveGenre.of("FOR ADULTS", adult = true))
    }

    @Test
    fun liveCountries() {
        assertEquals("Canada", liveCountry("CA| NEWS EN"))
        assertEquals("UK", liveCountry("UK| SPORT ᴴᴰ"))
        assertEquals("Ukraine", liveCountry("UK| UKRAINE"))
        assertEquals("China", liveCountry("CH| CHINA"))
        assertEquals("Switzerland", liveCountry("CH| SWISS"))
        assertEquals("Pakistan", liveCountry("ASIA| PAKISTAN"))
        assertEquals("India", liveCountry("ASIA| HINDI"))
        assertNull(liveCountry("HR| HORSE RACING"))
        assertNull(liveCountry("4K| RELAX"))
    }

    @Test
    fun leadingCountriesComeFirstThenByCount() {
        val order = orderCountries(mapOf("Germany" to 40, OTHER_COUNTRY to 90, "UK" to 10, "Canada" to 5, "Arabic" to 70))
        assertEquals(listOf("Canada", "UK", "Arabic", "Germany", OTHER_COUNTRY), order)
    }

    @Test
    fun searchMatchesWordStarts() {
        assertTrue(SearchText.matches("Star Wars: A New Hope", "star wa"))
        assertTrue(SearchText.matches("Pokémon: The Movie", "pokemon"))
        assertFalse(SearchText.matches("Mustard", "star"))
        assertTrue(SearchText.matches("Anything", " "))
    }
}
