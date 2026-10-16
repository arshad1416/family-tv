package app.familytv

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import app.familytv.data.Kind
import app.familytv.portal.*
import app.familytv.trakt.Trakt
import app.familytv.user.*
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TraktAcceptedMutationTest {
    private fun application(): FamilyTvApp {
        val app = FamilyTvApp()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = app
        }
        Application::class.java.getDeclaredMethod("attach", Context::class.java).apply { isAccessible = true }.invoke(app, context)
        app.portal.cachedConfig = DeviceConfig(true, profile = Profile(), trakt = TraktPortal("synthetic-client"))
        app.getSharedPreferences("trakt", Context.MODE_PRIVATE).edit().clear()
            .putString("access", "synthetic-access").putLong("expiresAt", System.currentTimeMillis() + 7 * 86_400_000L).apply()
        return app
    }

    @Test fun acceptedHistoryAddIsNotReplayedWhenTheFollowingRemoveIsCancelled() = runBlocking {
        MockWebServer().use { server ->
            val removeStarted = CountDownLatch(1)
            val adds = AtomicInteger()
            val removes = AtomicInteger()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/sync/history" -> { adds.incrementAndGet(); MockResponse().setResponseCode(201).setBody("{}") }
                    "/sync/history/remove" -> if (removes.incrementAndGet() == 1) {
                        removeStarted.countDown(); MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    } else MockResponse().setBody("{}")
                    else -> MockResponse().setBody("[]")
                }
            }
            val app = application()
            try {
                withContext(Dispatchers.IO) { app.userDb.clearAllTables(); app.db.clearAllTables() }
                val dao = app.userDb.user()
                for (id in listOf("add", "remove")) dao.putIds(MediaIdsEntity(Kind.MOVIE, "synthetic", id, if (id == "add") "42" else "43", null, null, 1))
                val add = ProgressEntity(ProgressKey.movie("synthetic", "add"), ProgressKey.MOVIE, "synthetic", "add", watched = true, updatedAt = 1, pendingHistory = true)
                val remove = ProgressEntity(ProgressKey.movie("synthetic", "remove"), ProgressKey.MOVIE, "synthetic", "remove", watched = false, updatedAt = 1, pendingHistory = true)
                dao.upsertProgressRows(listOf(add, remove))
                val trakt = Trakt(app, OkHttpClient(), server.url("/").toString().trimEnd('/'))
                val sync = launch(Dispatchers.IO) { trakt.sync.now() }
                try { assertTrue(removeStarted.await(5, TimeUnit.SECONDS)) } finally { sync.cancelAndJoin() }
                val acceptedAddStillPending = dao.progress(add.key)!!.pendingHistory
                assertTrue(dao.progress(remove.key)!!.pendingHistory)
                trakt.sync.now()
                assertFalse("Accepted history additions must be committed before starting removal", acceptedAddStillPending)
                assertEquals("Retry must not add accepted history again", 1, adds.get())
                assertEquals(2, removes.get())
            } finally { app.scope.cancel(); app.userDb.close(); app.db.close() }
        }
    }

    @Test fun knownAcceptedWatchlistPostCommitsLocallyWhenStopInterruptsUnusedResponseBody() = runBlocking {
        MockWebServer().use { server ->
            val headers = CountDownLatch(1)
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseHeadersEnd(call: Call, response: Response) { headers.countDown() }
            }).build()
            server.enqueue(MockResponse().setResponseCode(201).setBody("x".repeat(10000)).throttleBody(1, 100, TimeUnit.MILLISECONDS))
            val app = application()
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                withContext(Dispatchers.IO) { app.userDb.clearAllTables() }
                val dao = app.userDb.user()
                val favourite = FavouriteEntity(Kind.MOVIE, "synthetic", "42", "Synthetic", 1, pending = Pending.ADD)
                dao.upsertFavourite(favourite)
                val trakt = Trakt(app, client, server.url("/").toString().trimEnd('/'))
                val request = owner.launch {
                    trakt.post("/sync/watchlist", emptyMap<String, String>(), onAccepted = {
                        owner.cancel() // STOP after the application has observed 2xx headers.
                        delay(10)
                        dao.upsertFavourite(favourite.copy(pending = null, onTrakt = true))
                    })
                }
                assertTrue(headers.await(5, TimeUnit.SECONDS))
                delay(100)
                owner.cancel()
                request.join()
                assertNull("Known success must survive cancellation while discarding an unused POST body", dao.favourite(Kind.MOVIE, "synthetic", "42")!!.pending)
                assertTrue(dao.favourite(Kind.MOVIE, "synthetic", "42")!!.onTrakt)
                assertEquals(1, server.requestCount)
            } finally { owner.cancel(); app.scope.cancel(); app.userDb.close() }
        }
    }
}
