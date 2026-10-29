package app.familytv.iptv

import androidx.test.core.app.ApplicationProvider
import app.familytv.FamilyTvApp
import app.familytv.portal.Profile
import app.familytv.portal.Source
import app.familytv.ui.GlassHomeTest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A list load saves while it downloads: every row lands, over several batches, and a failed download stops it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = FamilyTvApp::class, shadows = [GlassHomeTest.LazyWorkManager::class], instrumentedPackages = ["androidx.work"])
class CatalogRefreshTest {
    private val app get() = ApplicationProvider.getApplicationContext<FamilyTvApp>()
    private val server = MockWebServer()

    @After fun tearDown() = server.shutdown()

    private fun list(n: Int, id: String) = (1..n).joinToString(",", "[", "]") { """{"$id":"$it","name":"Item $it","category_id":"1"}""" }

    private fun serve(movies: Int = Catalog.BATCH * 3 + 7, moviesCode: Int = 200) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.requestUrl?.queryParameter("action")) {
                null -> MockResponse().setBody(if (request.path!!.startsWith("/player_api.php")) """{"user_info":{"auth":1}}""" else "")
                    .setResponseCode(if (request.path!!.startsWith("/player_api.php")) 200 else 404)
                "get_live_categories", "get_vod_categories", "get_series_categories" -> MockResponse().setBody("""[{"category_id":"1","category_name":"All"}]""")
                "get_live_streams" -> MockResponse().setBody(list(Catalog.BATCH + 1, "stream_id"))
                "get_vod_streams" -> MockResponse().setResponseCode(moviesCode).setBody(list(movies, "stream_id"))
                "get_series" -> MockResponse().setBody(list(3, "series_id"))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    private fun count(table: String) = app.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    private val profile get() = Profile(sources = listOf(Source("s", "xtream", "Line", listOf(server.url("/").toString()), "u", "p")))

    @Test
    fun everyRowIsSavedAcrossBatches() = runBlocking {
        serve()
        app.catalog.refresh(profile)
        assertEquals(Catalog.BATCH + 1, count("channels"))
        assertEquals(Catalog.BATCH * 3 + 7, count("movies"))
        assertEquals(3, count("series"))
    }

    @Test(expected = java.io.IOException::class)
    fun aFailedListStopsTheLoad() {
        serve(moviesCode = 500)
        runBlocking { app.catalog.refresh(profile) }
    }
}
