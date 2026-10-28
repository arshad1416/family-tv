package app.familytv.trailer

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OpenTrailerTest {
    @Test
    fun installedYouTubeAppsComeFirstThenAnyApp() {
        val intents = trailerIntents("dQw4w9WgXcQ", listOf("com.google.android.youtube.tv"))
        assertEquals(
            listOf(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ" to "com.google.android.youtube.tv",
                "vnd.youtube:dQw4w9WgXcQ" to "com.google.android.youtube.tv",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ" to null,
                "vnd.youtube:dQw4w9WgXcQ" to null,
            ),
            intents.map { it.data.toString() to it.`package` },
        )
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        assertTrue(intents.all { it.action == Intent.ACTION_VIEW && it.flags and flags == flags })
    }

    @Test
    fun withoutYouTubeAnyAppMayOpenIt() {
        assertEquals(listOf(null, null), trailerIntents("dQw4w9WgXcQ", emptyList()).map { it.`package` })
    }

    /** Round 22: Google TV's YouTube opened on "Who's watching?" and never played the trailer, so it plays here. */
    @Test
    fun aTrailerPlaysOnItsOwnScreen() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        openTrailer(app, "dQw4w9WgXcQ")
        val started = shadowOf(app).nextStartedActivity
        assertEquals(TrailerActivity::class.java.name, started.component?.className)
        assertEquals("dQw4w9WgXcQ", started.getStringExtra(TrailerActivity.EXTRA_ID))
        assertTrue("dQw4w9WgXcQ" in TrailerActivity.page("dQw4w9WgXcQ"))
    }

    @Test
    fun aBadIdClosesTheTrailerScreen() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), TrailerActivity::class.java).putExtra(TrailerActivity.EXTRA_ID, "x'); alert(1")
        val activity = Robolectric.buildActivity(TrailerActivity::class.java, intent).setup().get()
        assertTrue(activity.isFinishing)
    }
}
