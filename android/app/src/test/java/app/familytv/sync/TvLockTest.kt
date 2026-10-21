package app.familytv.sync

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.familytv.portal.DeviceLock
import app.familytv.portal.HeartbeatResponse
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TvLockTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun locksWithTheMessageOrTheDefaultAndKeepsItAcrossARestart() {
        TvLock.init(context)
        TvLock.apply(DeviceLock("  Homework first  "))
        assertEquals("Homework first", TvLock.message.value)
        TvLock.apply(DeviceLock(" "))
        assertEquals(TvLock.DEFAULT_MESSAGE, TvLock.message.value)

        TvLock.init(context) // as on the next start, offline
        assertEquals(TvLock.DEFAULT_MESSAGE, TvLock.message.value)

        TvLock.apply(null)
        TvLock.init(context)
        assertNull(TvLock.message.value)
    }

    @Test
    fun readsThePortalsLock() {
        val gson = Gson()
        assertEquals(DeviceLock("Paused"), gson.fromJson("""{"paired":true,"commands":[],"lock":{"message":"Paused"}}""", HeartbeatResponse::class.java).lock)
        // A portal from before the lock sends none: unlocked.
        assertNull(gson.fromJson("""{"paired":true,"commands":[]}""", HeartbeatResponse::class.java).lock)
    }

    @Test
    fun uninstallOpensAndroidsPromptForThisApp() {
        val i = CommandRunner.uninstallIntent("app.familytv")
        assertEquals(Intent.ACTION_DELETE, i.action)
        assertEquals("package:app.familytv", i.data.toString())
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, i.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
