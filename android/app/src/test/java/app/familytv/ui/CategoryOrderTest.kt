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
}
