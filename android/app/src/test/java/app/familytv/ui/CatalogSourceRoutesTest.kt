package app.familytv.ui

import android.app.Application
import android.content.ContextWrapper
import app.familytv.FamilyTvApp
import app.familytv.discovery.SourceUnavailable
import app.familytv.data.MovieEntity
import app.familytv.data.SeriesEntity
import app.familytv.discovery.DiscoveryTitle
import app.familytv.iptv.XtreamParser
import app.familytv.portal.Profile
import app.familytv.trakt.PlayItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogSourceRoutesTest {
    private val profile = Profile()
    private val movie = MovieEntity("iptv-a", "movie-7", "Open Film", null, "movies", null, "mp4", null, 0, 0, tmdb = "42", year = 2024)
    private val show = SeriesEntity("iptv-b", "show-9", "Open Series", null, "series", null, null, 0)
    private val episode = XtreamParser.Episode("video-12", 2, 4, "Open Water", "mp4", null, null)

    @Test fun normalMovieSelectionThroughWrappedContextOpensChooserWithoutPlayback() = runTest {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        playMovieNow(ContextWrapper(activity), profile, movie)
        val request = activity.sourceChoice!!
        assertEquals(PlayItem.movie("iptv-a", "movie-7", "Open Film"), request.item)
        assertEquals("42", request.tmdb)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun unavailableIptvLinkDoesNotLaunchOrDiscardTheSourceRequest() = runTest {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val app = FamilyTvApp()
        val attach = android.app.Application::class.java.getDeclaredMethod("attach", android.content.Context::class.java)
        attach.isAccessible = true
        attach.invoke(app, activity)
        val context = object : ContextWrapper(activity) {
            override fun getApplicationContext(): android.content.Context = app
        }
        try {
            playMovieNow(context, Profile(), movie.copy(url = null))
            val request = activity.sourceChoice!!
            try { request.iptv(); fail("Missing IPTV link silently succeeded") } catch (e: SourceUnavailable) {
                assertTrue(e.message!!.contains("IPTV link"))
            }
            assertSame(request, activity.sourceChoice)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { app.db.close() }
    }

    @Test fun directIptvPlaysWithOriginalIdentityWithoutMetadataKeyOrProfileLookup() = runTest {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val app = FamilyTvApp()
        val attach = android.app.Application::class.java.getDeclaredMethod("attach", android.content.Context::class.java)
        attach.isAccessible = true
        attach.invoke(app, activity)
        val context = object : ContextWrapper(activity) {
            override fun getApplicationContext(): android.content.Context = app
        }
        try {
            playMovieNow(context, Profile(), movie.copy(url = "https://stream.example.test/movie.mp4"))
            val request = activity.sourceChoice!!
            try {
                request.iptv { throw SourceUnavailable("Selection changed") }
                fail("Actual IPTV route skipped its launch guard")
            } catch (_: SourceUnavailable) {}
            assertNull(shadowOf(activity).nextStartedActivity)
            request.iptv()
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals("https://stream.example.test/movie.mp4", intent.getStringExtra(PlayerActivity.EXTRA_URL))
            assertEquals(request.item, PlayItem.from(intent))
        } finally { app.db.close() }
    }

    @Test fun languageMenuActionRoutesSelectedCopyToSameManualChooser() = runTest {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val french = movie.copy(streamId = "movie-fr", lang = "fr")
        val copies = listOf(movie.copy(lang = "en"), french)
        val menu = movieMenu(activity, profile, MovieGroup("group", copies), copies, emptySet(), emptyMap(), null, this)
        menu.actions.filter { it.first.startsWith("▶  Play in") }[1].second()
        runCurrent()
        assertEquals("movie-fr", activity.sourceChoice!!.item.itemId)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun sharedEpisodePlayResumeAndPhoneActionKeepsEpisodeNameAndIdentityThroughMetadataChoice() = runTest {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        playEpisode(this, ContextWrapper(activity), profile, show, episode)
        val request = activity.sourceChoice!!
        assertEquals(PlayItem.episode("iptv-b", "show-9", "Open Series", 2, 4), request.item)
        assertTrue(request.label.contains("Open Water"))
        val candidate = DiscoveryTitle("tv", "300", "Open Series", 2024, imdb = "tt1234567")
        val target = request.target(candidate)
        assertEquals("tt1234567:2:4", target.streamId)
        assertTrue(target.label.contains("Open Water"))
        assertEquals("e/iptv-b/show-9/2/4", request.item.key)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
