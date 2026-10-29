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
        Transfer.apply(app, Transfer.Claimed(local, paired = false), profile)

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
    fun aPairedTvSendsItsOwnTokenToAbsorbTheCode() = runBlocking {
        val portal = PortalClient(app, server.url("/").toString().trimEnd('/'))
        server.enqueue(MockResponse().setBody("""{"deviceId":"kt01","deviceToken":"kt-token"}"""))
        portal.ensureRegistered()
        server.takeRequest()
        server.enqueue(MockResponse().setBody("""{"absorbed":"abc1","local":{"v":1,"currentViewer":"v1"}}"""))
        val local = Transfer.parse(portal.absorbTransfer(code))
        val req = server.takeRequest()
        assertEquals("/api/device/transfer-absorb", req.path)
        assertEquals("""{"code":"$code"}""", req.body.readUtf8())
        assertEquals("Bearer kt-token", req.getHeader("Authorization"))
        // This TV keeps its own registration.
        assertEquals("kt01", portal.deviceId)
        assertEquals("v1", local?.currentViewer)
    }

    @Test
    fun aPairedTvMergesTheRustAppsViewersAndFavouritesAndKeepsItsOwnSettings() = runBlocking {
        val profile = Profile(sources = listOf(Source("s", "xtream", "Line", listOf("http://a.test/", "http://b.test/"), "u", "p")))
        val mine = app.viewers.add(profile, "Mum", kids = false)!!
        app.viewers.toggleFavourite(mine, Kind.LIVE, "s", "1", "News")
        app.viewers.choose(mine)
        app.glassPrefs.setSaverMinutes(5)
        val local = Transfer.parse(
            com.google.gson.JsonParser.parseString(
                """{"v":1,
                  "viewers":[{"id":"r1","name":"mum","favourites":[{"kind":"live","sourceId":"s","itemId":"1","name":"News"},{"kind":"movie","sourceId":"s","itemId":"9","name":"Up"}]},
                             {"id":"r2","name":"Izaan","kids":true,"favourites":[{"kind":"series","sourceId":"s","itemId":"7","name":"Bluey"}]}],
                  "currentViewer":"r2",
                  "screensaver":{"minutes":15,"source":"clock"},
                  "servers":{"s":1}}""",
            ),
        )!!
        Transfer.apply(app, Transfer.Claimed(local, paired = true), profile)

        // "mum" is this TV's Mum: she only gains the movie. Izaan is added; Mum is still the one watching.
        assertEquals(listOf("Mum", "Izaan"), app.viewers.all().map { it.name })
        assertEquals(listOf("1", "9"), app.viewers.favourites()!!.map { it.itemId })
        assertEquals("Mum", app.viewers.current()!!.name)
        assertEquals(5, app.glassPrefs.saverMinutes.value)
        assertNull(ServerSwitch.chosen(profile.sources[0]))
    }

    @Test
    fun settingsThatDontReadAreDropped() {
        assertNull(Transfer.parse(com.google.gson.JsonParser.parseString("[1,2]")))
        assertNull(Transfer.parse(com.google.gson.JsonParser.parseString("""{"screensaver":"soon"}""")))
        assertNull(Transfer.parse(null))
    }
}
