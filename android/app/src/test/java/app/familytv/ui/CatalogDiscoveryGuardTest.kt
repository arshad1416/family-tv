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
    @Test fun restrictedTitlesAreComparedAfterNormalizationWithoutSqlQueryHoles() = runTest {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDb::class.java).allowMainThreadQueries().build()
        try {
            db.catalog().upsertCategories(listOf(CategoryEntity("s", Kind.MOVIE, "hidden", "Private", 0, 0), CategoryEntity("s", Kind.MOVIE, "public", "Movies", 1, 0)))
            db.catalog().upsertMovies(listOf(
                MovieEntity("s", "1", "EN - Locked.Film (2024)", null, "hidden", null, null, null, 0, 0),
                MovieEntity("s", "2", "FR - Amélie (2001)", null, "hidden", null, null, null, 0, 0),
                MovieEntity("s", "3", "Open Film", null, "public", null, null, null, 0, 0),
                MovieEntity("s", "4", "The Office (US)", null, "hidden", null, null, null, 0, 0)))
            val guard = CatalogDiscoveryGuard(db.catalog(), Profile(hiddenCategories = listOf("Private"), parentalPin = "1234"))
            val blocked = guard.blocked("Locked Film")!!
            assertFalse(DiscoveryPolicy.allowed("Locked Film", blocked))
            assertFalse(DiscoveryPolicy.allowed("Amelie", blocked))
            assertFalse(DiscoveryPolicy.allowed("The Office US", blocked))
            assertTrue(DiscoveryPolicy.allowed("Open Film", blocked))
            assertFalse(guard.allowed(DiscoveryTitle("movie", "42", "Locked Film", 2024)))
        } finally { db.close() }
    }
}
