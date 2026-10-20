package app.familytv.update

import android.content.Context
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** An install offer left over from an earlier run never resurfaces, and only an APK has a build number. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class UpdaterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun leftoverInstallSessionsAreAbandoned() {
        val installer = context.packageManager.packageInstaller
        repeat(2) { installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)) }
        assertEquals(2, installer.mySessions.size)
        Updater.abandonLeftovers(context)
        assertEquals(0, installer.mySessions.size)
    }

    @Test
    fun aDownloadThatIsNotAnAppHasNoBuild() {
        val file = File(context.cacheDir, "not-an-apk.apk").apply { writeText("<html>Not found</html>") }
        assertNull(Updater.versionCode(context, file))
        file.delete()
    }
}
