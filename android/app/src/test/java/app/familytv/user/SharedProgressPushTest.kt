package app.familytv.user

import androidx.test.core.app.ApplicationProvider
import app.familytv.FamilyTvApp
import app.familytv.portal.PortalClient
import app.familytv.sync.PlaybackStatus
import app.familytv.ui.GlassHomeTest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Round 19: the profile's shared progress stopped changing on Oct 22. A push now resumes where it failed, and Trakt's older rows still go. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = FamilyTvApp::class, shadows = [GlassHomeTest.LazyWorkManager::class], instrumentedPackages = ["androidx.work"])
class SharedProgressPushTest {
    private val app get() = ApplicationProvider.getApplicationContext<FamilyTvApp>()
    private val server = MockWebServer().apply { (dispatcher as QueueDispatcher).setFailFast(true); start() }

    @After fun tearDown() = server.shutdown()

    private fun episode(n: Int, at: Long) = ProgressEntity(ProgressKey.episode("s", "7", 1, n), ProgressKey.EPISODE, "s", "7", 1, n, watched = true, percent = 100f, updatedAt = at)

    /** One sync against a portal answering its pull with [rows] at [seq], then each push with [pushCodes] in turn. */
    private fun sync(shared: SharedProgress, vararg pushCodes: Int, rows: String = "", seq: Int = 0) = runBlocking {
        server.enqueue(MockResponse().setBody("""{"profile":"p1","rows":[$rows],"seq":$seq,"more":false}"""))
        pushCodes.forEach { server.enqueue(MockResponse().setResponseCode(it).setBody("""{"ok":true}""")) }
        runCatching { shared.sync() }
    }

    private fun registered(): SharedProgress = runBlocking {
        val portal = PortalClient(app, server.url("/").toString().trimEnd('/'))
        server.enqueue(MockResponse().setBody("""{"deviceId":"d1","deviceToken":"t"}"""))
        portal.ensureRegistered()
        server.takeRequest()
        SharedProgress(app, portal)
    }

    @Test
    fun aFailedChunkIsSentAgainButNotWhatLandedBeforeIt() = runBlocking {
        val shared = registered()
        val dao = app.userDb.user()
        dao.upsertProgressRows((1..250).map { episode(it, 1_000_000L + it) })
        // Marked watched from Trakt's history with Trakt's own, older time.
        val fromTrakt = episode(900, 5_000)
        dao.upsertProgressRows(listOf(fromTrakt))
        shared.resend(listOf(fromTrakt.key))

        sync(shared, 200, 500)
        assertEquals("HTTP 500", PlaybackStatus.progressSync?.get("failed"))

        // The first 100 landed; the rest go now, with the Trakt row again (it's dropped once a push finishes).
        sync(shared, 200, 200)
        assertEquals(153, PlaybackStatus.progressSync?.get("pushed"))

        sync(shared)
        assertEquals(0, PlaybackStatus.progressSync?.get("pushed"))
    }

    /** Round 22: the Kitchen sent back the rows it pulled on every sync, those from a TV whose clock runs ahead for good. */
    @Test
    fun pulledRowsAreNotSentBack() {
        val shared = registered()
        val ahead = System.currentTimeMillis() + 3_600_000
        val row = """{"key":"m/s/1","kind":"movie","sourceId":"s","itemId":"1","percent":100,"watched":true,"updatedAt":$ahead}"""
        sync(shared, rows = row, seq = 5)
        assertEquals(0, PlaybackStatus.progressSync?.get("pushed"))
        sync(shared, seq = 5)
        assertEquals(0, PlaybackStatus.progressSync?.get("pushed"))
        // Changed here after the pull, it goes.
        runBlocking { app.userDb.user().upsertProgressRows(listOf(episode(1, ahead + 1))) }
        sync(shared, 200, seq = 5)
        assertEquals(1, PlaybackStatus.progressSync?.get("pushed"))
    }
}
