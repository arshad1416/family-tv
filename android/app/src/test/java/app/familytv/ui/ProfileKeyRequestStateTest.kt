package app.familytv.ui

import app.familytv.discovery.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ProfileKeyRequestStateTest {
    @Test fun cancelAfterWriteStartsClearsPreviewUntilVerifiedReload() = runTest {
        val identity = DiscoveryIdentity("profile-a", "policy-v1", "device-1")
        val session = DiscoverySession(identity) { identity }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        var stored = ProfileRdKey("profile-a", 1, true, "ABCDsyntheticOldKey123WXYZ")
        val gateway = object : ProfileRdGateway {
            override suspend fun rdKeyRead(profileId: String) = stored
            override suspend fun rdKeyWrite(profileId: String, revision: Long, apiKey: String): ProfileRdKey {
                writes++
                // Model server acceptance before the response reaches this UI.
                stored = ProfileRdKey(profileId, revision + 1, true, apiKey)
                started.complete(Unit)
                release.await()
                return stored
            }
            override suspend fun rdKeyRemove(profileId: String, revision: Long): ProfileRdKey = error("Unexpected removal")
        }
        val rd = ProfileRealDebrid(gateway, DiscoveryHttp(OkHttpClient()))
        val ui = ProfileKeyRequestState()
        ui.status.value = rd.status(session)
        val expected = ui.status.value!!
        val request = launch { ui.status.value = rd.replace(session, expected, "EFGHsyntheticNewKey123IJKL") }
        started.await()
        ui.cancel(request)
        assertNull("A submitted write has an uncertain outcome until reload", ui.status.value)
        request.join()
        release.complete(Unit)
        assertNull(ui.status.value)
        assertEquals(1, writes)
        ui.status.value = rd.status(session)
        assertEquals("EFGH********IJKL", ui.status.value?.maskedPreview)
        assertEquals(1, writes)
    }
}
