package app.familytv

import android.app.Application
import androidx.work.NetworkType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackgroundSyncPolicyTest {
    @Test fun periodicSyncWaitsForNetworkAndAdequateBattery() {
        val request = backgroundSyncRequest()
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(30 * 60_000L, request.workSpec.intervalDuration)
    }
}
