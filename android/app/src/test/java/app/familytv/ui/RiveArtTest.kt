package app.familytv.ui

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import app.familytv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The Rive files ship in the app, and where Rive's library can't load the drawn switch, the videos, the spinner and plain page swaps stand in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RiveArtTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun theRiveFilesShip() {
        for (raw in listOf(R.raw.family_tv_appearance_switch, R.raw.family_tv_splash, R.raw.family_tv_transition, R.raw.family_tv_nav_icons)) {
            val magic = ctx.resources.openRawResource(raw).use { String(it.readNBytes(4)) }
            assertEquals("RIVE", magic)
        }
    }

    @Test
    fun withoutRivesLibraryTheFallbacksAreUsed() {
        // The JVM has no Rive library for Android, as an x86 emulator has none in the APK.
        assertFalse(RiveArt.ready(ctx))
        // Pages swap without the wipe then.
        assertNull(RiveTransition.create(ctx))
    }

    @Test
    fun neverOnATv() {
        assertTrue(RiveArt.allowed(ctx))
        // An onn or Chromecast with Google TV (leanback), as the Fire Stick: the still tab icons, drawn switch and plain page swaps.
        shadowOf(ctx.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        assertFalse(RiveArt.allowed(ctx))
    }
}
