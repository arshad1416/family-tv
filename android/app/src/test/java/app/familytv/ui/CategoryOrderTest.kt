package app.familytv.ui

import app.familytv.data.CategoryEntity
import app.familytv.data.Kind
import app.familytv.portal.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryOrderTest {
    private fun cats(kind: String, vararg names: String) = names.mapIndexed { i, n -> CategoryEntity("s", kind, "c$i", n, i, 0) }
    private val all = cats(Kind.LIVE, "News", "Sports", "Kids", "Movies", "XXX Adult", "Adults 18+")

    @Test
    fun namedCategoriesLeadInTheProfilesOrder() {
        val p = Profile(categoryOrder = mapOf(Kind.LIVE to listOf("kids", "Sports")), hiddenCategories = listOf("Movies"))
        assertEquals(listOf("Kids", "Sports", "News"), visibleCategories(all, p).map { it.name })
    }

    @Test
    fun anotherKindsOrderDoesNotApply() {
        val p = Profile(categoryOrder = mapOf(Kind.MOVIE to listOf("Kids")), parentalPin = "1234")
        assertEquals(listOf("News", "Sports", "Kids", "Movies"), visibleCategories(all, p).map { it.name })
    }

    @Test
    fun adultCategoriesOnlyShowUnderAdultContent() {
        val movies = cats(Kind.MOVIE, "Action", "FOR ADULTS", "XXX")
        val series = cats(Kind.SERIES, "Drama", "NETFLIX ADULT-SWIM", "HBO MAX (ADULT SWIM)", "Adult 18+")
        for (p in listOf(Profile(), Profile(parentalPin = "1234"), null)) {
            assertEquals(listOf("Action"), visibleCategories(movies, p).map { it.name })
            assertEquals(listOf("FOR ADULTS", "XXX"), adultCategories(movies, p).map { it.name })
            assertEquals(listOf("Drama", "NETFLIX ADULT-SWIM", "HBO MAX (ADULT SWIM)"), visibleCategories(series, p).map { it.name })
            assertEquals(listOf("Adult 18+"), adultCategories(series, p).map { it.name })
            assertEquals(listOf("XXX Adult", "Adults 18+"), adultCategories(all, p).map { it.name })
        }
    }

    @Test
    fun adultContentFollowsTheProfilesHiddenListAndOrder() {
        val p = Profile(categoryOrder = mapOf(Kind.LIVE to listOf("Adults 18+")), hiddenCategories = listOf("XXX Adult"))
        assertEquals(listOf("Adults 18+"), adultCategories(all, p).map { it.name })
    }

    @Test
    fun pinIsOnlyAskedForWhenTheProfileHasOne() {
        assertTrue(pinMatches(Profile(), ""))
        assertTrue(pinMatches(Profile(parentalPin = "1234"), "1234"))
        assertFalse(pinMatches(Profile(parentalPin = "1234"), "1111"))
        assertFalse(pinMatches(Profile(parentalPin = "1234"), ""))
    }
}
