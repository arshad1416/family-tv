package app.familytv.ui

import android.app.Application
import androidx.room.Room
import app.familytv.data.*
import app.familytv.discovery.DiscoveryTitle
import app.familytv.portal.Profile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercise mainline Room 6/search extensions together with the reconciled source policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KotlinReconciliationTest {
    @Test fun accentSearchAndSourcePolicyCoexistOnSchemaSix() = runTest {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDb::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.catalog()
            dao.upsertCategories(listOf(
                CategoryEntity("s", Kind.MOVIE, "public", "Movies", 0, 0),
                CategoryEntity("s", Kind.MOVIE, "adult", "XXX Adult", 1, 0),
                CategoryEntity("s", Kind.LIVE, "live-adult", "XXX Adult", 2, 0),
            ))
            dao.upsertMovies(listOf(
                MovieEntity("s", "open", "Pokémon: The First Movie", null, "public", null, null, null, 0, 0, tmdb = "42"),
                MovieEntity("s", "locked", "Amélie", null, "adult", null, null, null, 0, 0, tmdb = "43"),
            ))
            assertEquals(6, db.openHelper.readableDatabase.version)
            assertEquals(listOf("open"), dao.searchMovies("first poke", 10).map { it.streamId })
            assertEquals(listOf("locked"), dao.searchMovies("amelie", 10).map { it.streamId })
            val profile = Profile(parentalPin = "1234")
            val guard = CatalogDiscoveryGuard(db, profile)
            assertTrue(guard.allowed(DiscoveryTitle("movie", "42", "Pokémon: The First Movie")))
            assertFalse(guard.allowed(DiscoveryTitle("movie", "43", "Another localized title")))
            // Mainline live parity does not authorize adult movie metadata or profile keys.
            assertTrue(adultOpen(profile, unlocked = true))
            assertEquals(1, visibleCategories(dao.policyCategories(Kind.LIVE), profile, adultLive = true).size)
            assertFalse(guard.allowed(DiscoveryTitle("movie", "43", "Amelie")))
        } finally { db.close() }
    }
}
