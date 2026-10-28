package app.familytv.transfer

import androidx.test.core.app.ApplicationProvider
import app.familytv.FamilyTvApp
import app.familytv.data.Kind
import app.familytv.iptv.ServerSwitch
import app.familytv.portal.PortalClient
import app.familytv.portal.Profile
import app.familytv.portal.Source
import app.familytv.ui.GlassHomeTest
import app.familytv.user.FavouriteEntity
import app.familytv.user.Pending
import app.familytv.user.SaverSource
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The one-button move from the Rust app: the code is claimed once, and the Rust app's settings land on this TV. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = FamilyTvApp::class, shadows = [GlassHomeTest.LazyWorkManager::class], instrumentedPackages = ["androidx.work"])
class TransferTest {
    private val app get() = ApplicationProvider.getApplicationContext<FamilyTvApp>()
    private val server = MockWebServer().apply { start() }
    private val code = "0123456789abcdef0123456789abcdef"

    @After fun tearDown() = server.shutdown()

    @Test
    fun onlyACodeIsTakenFromTheIntentAndOnlyOnce() {
        assertFalse(Transfer.offer(null))
        assertFalse(Transfer.offer("not a code"))
        assertTrue(Transfer.offer(" ${code.uppercase()} "))
        assertEquals(code, Transfer.take())
        assertNull(Transfer.take())
    }

    @Test
    fun claimingTakesOverTheTvsDeviceRow() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"deviceId":"abc1","deviceToken":"new-token","local":{"v":1,"currentViewer":"v1"}}"""))
        val portal = PortalClient(app, server.url("/").toString().trimEnd('/'))
        val local = Transfer.parse(portal.claimTransfer(code))
        val req = server.takeRequest()
        assertEquals("/api/device/transfer-claim", req.path)
        assertEquals("""{"code":"$code"}""", req.body.readUtf8())
        // A fresh install has no token to name.
        assertNull(req.getHeader("Authorization"))
        assertEquals("abc1", portal.deviceId)
        assertEquals("v1", local?.currentViewer)
    }

    @Test
    fun theRustAppsSettingsLandWhereThisTvHasNoneOfItsOwn() = runBlocking {
        val dao = app.userDb.user()
        dao.upsertFavourite(FavouriteEntity(Kind.LIVE, "s", "1", "Mine", 5))
        val profile = Profile(sources = listOf(Source("s", "xtream", "Line", listOf("http://a.test/", "http://b.test/"), "u", "p")))
        val local = Transfer.parse(
            com.google.gson.JsonParser.parseString(
                """{"v":1,
                  "favourites":[{"kind":"live","sourceId":"s","itemId":"1","name":"Theirs","addedAt":1},
                                {"kind":"movie","sourceId":"s","itemId":"9","name":"Up","addedAt":2},
                                {"kind":"radio","sourceId":"s","itemId":"3"},{"kind":"live","sourceId":"s"}],
                  "viewers":[{"id":"v1","name":" Izaan ","kids":true,"favourites":[{"kind":"series","sourceId":"s","itemId":"7","name":"Bluey"}]},{"name":"  "}],
                  "currentViewer":"v1",
                  "screensaver":{"minutes":10,"source":"space"},
                  "servers":{"s":1,"gone":3},
                  "later":"ignored"}""",
            ),
        )!!
        Transfer.apply(app, local, profile)

        // This TV's own favourite stays as it was; the movie comes over as one added here.
        assertEquals("Mine", dao.favourite(Kind.LIVE, "s", "1")!!.name)
        assertEquals(Pending.ADD, dao.favourite(Kind.MOVIE, "s", "9")!!.pending)
        assertNull(dao.favourite("radio", "s", "3"))
        assertEquals(listOf("Izaan"), app.viewers.all().map { it.name })
        assertTrue(app.viewers.current()!!.kids)
        assertEquals(listOf("7"), app.viewers.favourites()!!.map { it.itemId })
        assertEquals(10, app.glassPrefs.saverMinutes.value)
        assertEquals(SaverSource.SPACE, app.glassPrefs.saverSource.value)
        assertEquals("http://b.test", ServerSwitch.chosen(profile.sources[0]))
    }

    @Test
    fun settingsThatDontReadAreDropped() {
        assertNull(Transfer.parse(com.google.gson.JsonParser.parseString("[1,2]")))
        assertNull(Transfer.parse(com.google.gson.JsonParser.parseString("""{"screensaver":"soon"}""")))
        assertNull(Transfer.parse(null))
    }
}
