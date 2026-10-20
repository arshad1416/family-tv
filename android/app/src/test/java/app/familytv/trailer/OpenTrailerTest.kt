package app.familytv.trailer

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OpenTrailerTest {
    @Test
    fun installedYouTubeAppsComeFirstThenAnyApp() {
        val intents = trailerIntents("dQw4w9WgXcQ", listOf("com.google.android.youtube.tv"))
        assertEquals(
            listOf(
                "vnd.youtube:dQw4w9WgXcQ" to "com.google.android.youtube.tv",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ" to "com.google.android.youtube.tv",
                "vnd.youtube:dQw4w9WgXcQ" to null,
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ" to null,
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
}
