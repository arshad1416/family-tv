package app.familytv.ui

import android.app.Application
import androidx.room.Room
import app.familytv.data.*
import app.familytv.discovery.*
import app.familytv.portal.Profile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CatalogDiscoveryGuardTest {
    @Test fun largeRestrictedCatalogDoesNotHideUnrelatedTitleOrAuthorizeLateRestrictedMatch() = runTest {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDb::class.java).allowMainThreadQueries().build()
        try {
            db.catalog().upsertCategories(listOf(CategoryEntity("s", Kind.MOVIE, "hidden", "Private", 0, 0)))
            val rows = (1..20005).map { i -> MovieEntity("s", "$i", "Restricted Film $i", null, "hidden", null, null, null, 0, 0) }
            rows.chunked(1000).forEach { db.catalog().upsertMovies(it) }
            db.catalog().upsertMovies(listOf(MovieEntity("s", "last", "EN - Late.Locked.Film (2024)", null, "hidden", null, null, null, 0, 0, tmdb = "100005")))
            val guard = CatalogDiscoveryGuard(db, Profile(hiddenCategories = listOf("Private")))
            assertTrue(guard.allowed(DiscoveryTitle("movie", "42", "Unrelated Open Film")))
            assertFalse(guard.allowed(DiscoveryTitle("movie", "43", "Late Locked Film", 2024)))
            assertFalse(guard.allowed(DiscoveryTitle("movie", "100005", "A translated title", 2024)))
        } finally { db.close() }
    }

    @Test fun moreThanNineHundredVisibleCategoriesDoNotDisableOrdinaryDiscovery() = runTest {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDb::class.java).allowMainThreadQueries().build()
        try {
            db.catalog().upsertCategories((1..1001).map { i -> CategoryEntity("s", Kind.MOVIE, "$i", "Movies $i", i, 0) })
            val guard = CatalogDiscoveryGuard(db, Profile())
            assertTrue(guard.allowed(DiscoveryTitle("movie", "42", "Open Film")))
        } finally { db.close() }
    }

    @Test fun restrictedTitlesAreComparedAfterNormalizationWithoutSqlQueryHoles() = runTest {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDb::class.java).allowMainThreadQueries().build()
        try {
            db.catalog().upsertCategories(listOf(CategoryEntity("s", Kind.MOVIE, "hidden", "Private", 0, 0), CategoryEntity("s", Kind.MOVIE, "public", "Movies", 1, 0)))
            db.catalog().upsertMovies(listOf(
                MovieEntity("s", "1", "EN - Locked.Film (2024)", null, "hidden", null, null, null, 0, 0),
                MovieEntity("s", "2", "FR - Amélie (2001)", null, "hidden", null, null, null, 0, 0),
                MovieEntity("s", "3", "Open Film", null, "public", null, null, null, 0, 0),
                MovieEntity("s", "4", "The Office (US)", null, "hidden", null, null, null, 0, 0),
                MovieEntity("s", "5", "गुप्त चलचित्र", null, "hidden", null, null, null, 0, 0)))
            val guard = CatalogDiscoveryGuard(db, Profile(hiddenCategories = listOf("Private"), parentalPin = "1234"))
            val allowed = guard.filterNames(listOf("Locked Film", "Amelie", "The Office US", "Open Film"))
            assertEquals(setOf("Open Film"), allowed)
            assertFalse(guard.allowed(DiscoveryTitle("movie", "42", "Locked Film", 2024)))
            assertFalse(guard.allowed(DiscoveryTitle("movie", "43", "गुप्त चलचित्र")))
        } finally { db.close() }
    }
}
