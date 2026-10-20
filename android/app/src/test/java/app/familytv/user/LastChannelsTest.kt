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

    @Test
    fun recentKeepsTheLastEightNewestFirst() {
        val last = LastChannels(prefs)
        val all = (1..10).map { ChannelRef("a", "1", "$it") }
        all.forEach { last.played(home, it) }
        assertEquals(all.takeLast(8).reversed(), last.recent(home))
        // Playing an older one again moves it to the front without a duplicate.
        last.played(home, all[5])
        assertEquals(listOf(all[5], all[9], all[8], all[7], all[6], all[4], all[3], all[2]), last.recent(home))
        assertEquals(all[9], last.previous(home))
        assertEquals(emptyList<ChannelRef>(), last.recent(other))
    }

    @Test
    fun olderBuildsTwoChannelsCarryOver() {
        prefs.edit().putString("a/now", "a\u001f2\u001f200").putString("a/before", "a\u001f1\u001f100").commit()
        val last = LastChannels(prefs)
        assertEquals(listOf(sports, news), last.recent(home))
        assertEquals(news, last.previous(home))
        last.played(home, ChannelRef("a", "3", "300"))
        assertEquals(listOf(ChannelRef("a", "3", "300"), sports, news), last.recent(home))
    }
}
