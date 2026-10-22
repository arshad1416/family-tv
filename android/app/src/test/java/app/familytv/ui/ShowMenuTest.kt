package app.familytv.ui

import androidx.test.core.app.ApplicationProvider
import app.familytv.FamilyTvApp
import app.familytv.data.SeriesEntity
import app.familytv.portal.Profile
import app.familytv.portal.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Hold-OK on a show poster: Continue or Play, favourite, and mark the series, with the page's own episode pick. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = FamilyTvApp::class, shadows = [GlassHomeTest.LazyWorkManager::class], instrumentedPackages = ["androidx.work"])
class ShowMenuTest {
    private val app get() = ApplicationProvider.getApplicationContext<FamilyTvApp>()
    private val server = MockWebServer().apply { start() }
    private val profile = Profile(sources = listOf(Source("s", "xtream", "Line", listOf(server.url("/").toString()), "u", "p")))
    private val show = SeriesGroup("severance", listOf(SeriesEntity("s", "7", "EN - Severance", null, "c", null, null, gen = 0)))
    private val twoEpisodes = """{"episodes":{"1":[{"id":"71","episode_num":1,"title":"Good News About Hell"},{"id":"72","episode_num":2,"title":"Half Loop"}]}}"""

    @After fun tearDown() = server.shutdown()

    private fun labels(favourite: Boolean = false, body: String = twoEpisodes, code: Int = 200): List<String> = runBlocking {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
        showMenu(app, profile, show, favourite, CoroutineScope(Dispatchers.Unconfined)).actions.map { it.first }
    }

    private fun watch(vararg episodes: Int) = runBlocking { app.watched.episodes("s", "7", episodes.map { 1 to it }, true) }

    @Test
    fun aNewShowOffersItsFirstEpisode() {
        assertEquals(listOf("▶  Play S1 E1", "♡  Add to Favourites", "✓  Mark series as watched"), labels())
    }

    @Test
    fun aStartedShowContinuesWhereContinueWatchingWould() {
        watch(1)
        assertEquals(listOf("▶  Continue S1 E2", "♥  Remove from Favourites", "✓  Mark series as watched"), labels(favourite = true))
    }

    @Test
    fun aWatchedShowCanBeMarkedUnwatched() {
        watch(1, 2)
        assertEquals("Mark series as unwatched", labels().last())
    }

    @Test
    fun withoutAnEpisodeListOnlyTheFavouriteIsOffered() {
        assertEquals(listOf("♡  Add to Favourites"), labels(body = "", code = 500))
    }
}
