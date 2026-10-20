package app.familytv.user

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.familytv.portal.Profile
import app.familytv.portal.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LastChannelsTest {
    private val prefs = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("last_channels_test", Context.MODE_PRIVATE)
    private val home = Profile(sources = listOf(Source("a", "xtream", "Line A")))
    private val other = Profile(sources = listOf(Source("b", "xtream", "Line B")))
    private val news = ChannelRef("a", "1", "100")
    private val sports = ChannelRef("a", "2", "200")

    @Test
    fun leftTogglesBetweenTheLastTwo() {
        val last = LastChannels(prefs)
        assertNull(last.previous(home))
        last.played(home, news)
        assertNull(last.previous(home))
        last.played(home, sports)
        assertEquals(news, last.previous(home))
        // Left plays news again: sports is now the previous one, and back.
        last.played(home, news)
        assertEquals(sports, last.previous(home))
        // Re-opening the same channel changes nothing.
        last.played(home, news)
        assertEquals(sports, last.previous(home))
    }

    @Test
    fun keptPerProfileAndThroughRestarts() {
        LastChannels(prefs).apply { played(home, news); played(home, sports) }
        val again = LastChannels(prefs)
        assertEquals(news, again.previous(home))
        assertNull(again.previous(other))
    }
}
