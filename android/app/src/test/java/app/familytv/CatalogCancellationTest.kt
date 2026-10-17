package app.familytv

import android.app.Application
import androidx.room.Room
import app.familytv.data.AppDb
import app.familytv.iptv.Catalog
import app.familytv.portal.Profile
import app.familytv.portal.Source
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CatalogCancellationTest {
    @Test fun cancellingSlowDripCatalogClosesSocketAndDoesNotRequestNextGuide() = runBlocking {
        MockWebServer().use { server ->
            val bodyStarted = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val activeCall = AtomicReference<Call>()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callStart(call: Call) { activeCall.set(call) }
                override fun responseBodyStart(call: Call) { bodyStarted.countDown() }
                override fun canceled(call: Call) { cancelled.countDown() }
            }).build()
            server.enqueue(MockResponse().setBody("#EXTM3U\n" + "#EXTINF:-1,Synthetic\nhttps://fixture.example/channel\n".repeat(2000)).throttleBody(32, 100, TimeUnit.MILLISECONDS))
            val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDb::class.java).allowMainThreadQueries().build()
            val source = Source("synthetic", "m3u", "Synthetic", m3uUrl = server.url("/playlist").toString(), epgUrl = server.url("/guide").toString())
            val job = launch(Dispatchers.IO) { Catalog(db, client).refresh(Profile(sources = listOf(source))) }
            try {
                assertTrue(bodyStarted.await(5, TimeUnit.SECONDS))
                job.cancel()
                val closedByCancellation = cancelled.await(1, TimeUnit.SECONDS)
                activeCall.get().cancel() // Cleanup also makes the RED run finite.
                job.join()
                assertTrue("STOP must cancel an ongoing response body, not wait for a per-read timeout", closedByCancellation)
                assertEquals(1, server.requestCount)
            } finally { job.cancel(); activeCall.get()?.cancel(); job.join(); db.close() }
        }
    }
}
