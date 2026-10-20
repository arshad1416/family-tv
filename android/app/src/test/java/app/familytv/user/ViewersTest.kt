package app.familytv.user

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.familytv.data.Kind
import app.familytv.portal.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ViewersTest {
    private val prefs = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("viewers_test", Context.MODE_PRIVATE)
    private val withPin = Profile(parentalPin = "1234")

    @Test
    fun viewersAreAddedOnTheTvAndAKidsViewerNeedsAPin() {
        val viewers = Viewers(prefs)
        assertNull(viewers.add(withPin, "   ", kids = false))
        assertNull(viewers.add(Profile(), "Izaan", kids = true))
        val arshad = viewers.add(withPin, " Arshad ", kids = false)!!
        val izaan = viewers.add(withPin, "Izaan", kids = true)!!
        assertEquals(listOf("Arshad", "Izaan"), viewers.all().map { it.name })
        assertNull(viewers.current())
        viewers.choose(izaan)
        assertTrue(viewers.apply(withPin).kidsOnly)
        // Kept through restarts.
        assertEquals(izaan, Viewers(prefs).current())
        viewers.choose(arshad)
        assertFalse(viewers.apply(withPin).kidsOnly)
        repeat(Viewers.MAX) { viewers.add(withPin, "V$it", kids = false) }
        assertEquals(Viewers.MAX, viewers.all().size)
    }

    @Test
    fun eachViewerHasTheirOwnFavouritesAndPlayedTitles() {
        val viewers = Viewers(prefs)
        // Nobody picked: the TV's own favourites and every resume point count.
        assertNull(viewers.favourites())
        assertNull(viewers.seen())
        viewers.played(ProgressKey.movie("s", "1"))
        val arshad = viewers.add(withPin, "Arshad", kids = false)!!
        val izaan = viewers.add(withPin, "Izaan", kids = false)!!
        viewers.choose(arshad)
        assertTrue(viewers.toggleFavourite(arshad, Kind.MOVIE, "s", "1", "Star Wars"))
        viewers.played(ProgressKey.movie("s", "1"))
        viewers.played(ProgressKey.movie("s", "2"))
        viewers.played(ProgressKey.movie("s", "1"))
        assertEquals(listOf("Star Wars"), viewers.favourites()!!.map { it.name })
        assertEquals(listOf(ProgressKey.movie("s", "1"), ProgressKey.movie("s", "2")), viewers.seen())
        viewers.choose(izaan)
        assertEquals(emptyList<FavouriteEntity>(), viewers.favourites())
        assertEquals(emptyList<String>(), viewers.seen())
        assertEquals(listOf("Star Wars"), viewers.everyonesFavourites().map { it.name })
        // Toggling again removes it; removing a viewer takes their things and the pick with them.
        viewers.choose(arshad)
        assertFalse(viewers.toggleFavourite(arshad, Kind.MOVIE, "s", "1", "Star Wars"))
        viewers.remove(arshad)
        assertNull(viewers.current())
        assertEquals(listOf("Izaan"), viewers.all().map { it.name })
        assertTrue(prefs.all.keys.none { it.endsWith(arshad.id) })
    }

    @Test
    fun theOlderPortalPickIsForgotten() {
        prefs.edit().putString("s1,s2", "portal-viewer").apply()
        val viewers = Viewers(prefs)
        assertNull(viewers.current())
        assertFalse("s1,s2" in prefs.all)
        assertNotNull(viewers.add(withPin, "Arshad", kids = false))
    }
}
