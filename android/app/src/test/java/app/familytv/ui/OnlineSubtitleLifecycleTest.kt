package app.familytv.ui

import androidx.media3.common.MediaItem
import app.familytv.core.ForegroundWork
import app.familytv.player.OnlineSubtitles
import app.familytv.subs.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnlineSubtitleLifecycleTest {
    private val hit = SubtitleHit(42, "en", "Synthetic", 1, false, false, false, null, null, null, false)
    @Test fun stopDuringDownloadCannotApplySubtitlesOrPrepareHiddenPlayer() = runBlocking {
        delayedDownload(stop = true, changeItem = false)
    }
    @Test fun subtitlesForAnOldItemCannotReloadTheNewItem() = runBlocking {
        delayedDownload(stop = false, changeItem = true)
    }
    private suspend fun delayedDownload(stop: Boolean, changeItem: Boolean) {
        MockWebServer().use { server ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/download" -> MockResponse().setBody("""{"link":"${server.url("/fixture.srt")}"}""")
                    "/fixture.srt" -> { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); MockResponse().setBody("1\n00:00:01,000 --> 00:00:02,000\nSynthetic\n") }
                    else -> error("Unexpected synthetic path")
                }
            }
            val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val owner = ForegroundWork(parent)
            var item = MediaItem.fromUri("https://fixture.example/first")
            var loads = 0
            var prepares = 0
            val dir = Files.createTempDirectory("subtitle-lifecycle").toFile()
            val online = OnlineSubtitles(OpenSubtitles("synthetic", "synthetic", OkHttpClient(), server.url("/").toString()),
                owner.coroutineScope, dir, { SubtitleRequest(false, "Synthetic") }, { _, _ -> loads++; prepares++ }, { item })
            try {
                online.pick(hit)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                if (stop) owner.stop()
                if (changeItem) item = MediaItem.fromUri("https://fixture.example/second")
                release.countDown()
                val job = OnlineSubtitles::class.java.getDeclaredField("job").apply { isAccessible = true }.get(online) as Job
                job.join()
                assertEquals("Late subtitle callback must not reload the player", 0, loads)
                assertEquals(0, prepares)
            } finally {
                release.countDown(); owner.stop(); parent.cancel(); dir.deleteRecursively()
            }
        }
    }
}
