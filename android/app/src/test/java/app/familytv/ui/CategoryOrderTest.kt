package app.familytv.ui

import app.familytv.data.CategoryEntity
import app.familytv.data.Kind
import app.familytv.portal.Profile
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryOrderTest {
    private fun cats(vararg names: String) = names.mapIndexed { i, n -> CategoryEntity("s", Kind.LIVE, "c$i", n, i, 0) }
    private val all = cats("News", "Sports", "Kids", "Movies", "XXX Adult")

    @Test
    fun namedCategoriesLeadInTheProfilesOrder() {
        val p = Profile(categoryOrder = mapOf(Kind.LIVE to listOf("kids", "Sports")), hiddenCategories = listOf("Movies"))
        assertEquals(listOf("Kids", "Sports", "News", "XXX Adult"), visibleCategories(all, p, unlocked = false).map { it.name })
    }

    @Test
    fun anotherKindsOrderDoesNotApply() {
        val p = Profile(categoryOrder = mapOf(Kind.MOVIE to listOf("Kids")), parentalPin = "1234")
        assertEquals(listOf("News", "Sports", "Kids", "Movies"), visibleCategories(all, p, unlocked = false).map { it.name })
    }

    @Test
    fun adultMoviesAndSeriesStayHiddenEvenUnlockedOrWithoutAPin() {
        val movies = listOf("Action", "FOR ADULTS", "XXX").mapIndexed { i, n -> CategoryEntity("s", Kind.MOVIE, "m$i", n, i, 0) }
        val series = listOf("Drama", "NETFLIX ADULT-SWIM", "HBO MAX (ADULT SWIM)", "Adult 18+")
            .mapIndexed { i, n -> CategoryEntity("s", Kind.SERIES, "s$i", n, i, 0) }
        for (p in listOf(Profile(), Profile(parentalPin = "1234"), null)) {
            assertEquals(listOf("Action"), visibleCategories(movies, p, unlocked = true).map { it.name })
            assertEquals(listOf("Drama", "NETFLIX ADULT-SWIM", "HBO MAX (ADULT SWIM)"), visibleCategories(series, p, unlocked = true).map { it.name })
        }
    }

    @Test
    fun adultLiveCategoriesStillShowOnceUnlocked() {
        val p = Profile(parentalPin = "1234")
        assertEquals("XXX Adult", visibleCategories(all, p, unlocked = true).last().name)
        assertEquals("XXX Adult", visibleCategories(all, Profile(), unlocked = false).last().name)
    }
}
