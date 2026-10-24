package app.familytv.connector

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.familytv.connector.v1.ISourceCallback
import app.familytv.connector.v1.ISourceProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The host adapter against the debug reference companion and against providers that misbehave. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SourceConnectorTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val film = MediaIdentity(MediaIdentity.Type.MOVIE, tmdb = ReferenceSourceProvider.TMDB, imdb = ReferenceSourceProvider.IMDB)
    private var entitled = true
    private val gate = { _: String -> entitled }
    private val quick = SourceConnector.Timeouts(bindMs = 2_000, negotiateMs = 300, listMs = 300, resolveMs = 300)

    private fun reference(): ISourceProvider =
        ISourceProvider.Stub.asInterface(Robolectric.buildService(ReferenceSourceProvider::class.java).create().get().onBind(Intent()))

    private fun session(provider: ISourceProvider) = SourceSession(provider, ApprovedProviders.REFERENCE_ADDON, gate, quick)

    /** A provider that answers what [answer] returns (null: never answers) and records cancels. */
    private class Fake(val answer: (String, ISourceCallback) -> Unit = { rid, cb -> cb.onResult(rid, Bundle().apply { putInt("protocol", 1) }) }) : ISourceProvider.Stub() {
        val cancels = mutableListOf<String>()
        val closed = mutableListOf<String>()
        var next: ((String, ISourceCallback) -> Unit)? = null
        override fun negotiate(requestId: String, hello: Bundle, callback: ISourceCallback) = answer(requestId, callback)
        override fun listSources(requestId: String, sessionId: String, identity: Bundle, callback: ISourceCallback) { next?.invoke(requestId, callback) }
        override fun resolve(requestId: String, sessionId: String, selectionId: String, callback: ISourceCallback) { next?.invoke(requestId, callback) }
        override fun cancel(requestId: String) { cancels += requestId }
        override fun closeSession(sessionId: String) { closed += sessionId }
    }

    @Test fun theReferenceCompanionListsAndResolvesItsOneFilm() = runBlocking {
        val s = session(reference())
        assertEquals("Reference (test data)", (s.negotiate("test") as Reply.Ok).value.providerName)
        val sources = (s.listSources(film) as Reply.Ok).value
        assertEquals(listOf("ref-1080", "ref-720", "ref-480"), sources.map { it.selectionId })
        val played = (s.resolve("ref-720") as Reply.Ok).value
        assertEquals("https://example.invalid/reference/ref-720.mp4", played.uri)
        assertEquals("video/mp4", played.mimeType)
        // Anything else is unknown to it, and ids from no listing are refused before they're sent.
        assertEquals(Reply.Failed(ConnectorError.NOT_FOUND), s.listSources(MediaIdentity(MediaIdentity.Type.MOVIE, tmdb = "1")))
        assertEquals(Reply.Failed(ConnectorError.NOT_FOUND), s.resolve("ref-9999"))
        s.close()
        assertEquals(Reply.Failed(ConnectorError.CANCELLED), s.resolve("ref-720"))
    }

    @Test fun theGateIsCheckedBeforeListingAndAgainBeforePlayback() = runBlocking {
        val s = session(reference())
        s.negotiate("test")
        entitled = false
        assertEquals(Reply.Failed(ConnectorError.NOT_ENTITLED), s.listSources(film))
        entitled = true
        s.listSources(film)
        // Membership ends (or the PIN check fails) between choosing and playing.
        entitled = false
        assertEquals(Reply.Failed(ConnectorError.NOT_ENTITLED), s.resolve("ref-1080"))
    }

    @Test fun nothingIsAskedBeforeAVersionIsAgreed() = runBlocking {
        val newer = Fake { rid, cb -> cb.onResult(rid, Bundle().apply { putInt("protocol", 2) }) }
        val s = session(newer)
        assertEquals(Reply.Failed(ConnectorError.UNSUPPORTED_VERSION), s.negotiate("test"))
        assertEquals(Reply.Failed(ConnectorError.UNSUPPORTED_VERSION), s.listSources(film))
    }

    @Test fun aSilentProviderTimesOutAndIsToldToCancel() = runBlocking {
        val f = Fake()
        val s = session(f)
        s.negotiate("test")
        f.next = { _, _ -> }
        assertEquals(Reply.Failed(ConnectorError.TIMEOUT), s.listSources(film))
        assertEquals(1, f.cancels.size)
        s.close()
        assertEquals(listOf(s.id), f.closed)
    }

    @Test fun aCallerThatStopsWaitingCancelsAtTheProvider() = runBlocking {
        val f = Fake()
        val s = session(f)
        s.negotiate("test")
        f.next = { _, _ -> }
        val waiting = launch { s.listSources(film) }
        delay(50)
        waiting.cancel()
        waiting.join()
        assertEquals(1, f.cancels.size)
    }

    @Test fun malformedOrUnknownAnswersAreProviderErrors() = runBlocking {
        val f = Fake()
        val s = session(f)
        s.negotiate("test")
        f.next = { rid, cb -> cb.onResult(rid, Bundle().apply { putParcelableArrayList("sources", arrayListOf(Source("a", "A").toBundle())) }) }
        s.listSources(film)
        f.next = { rid, cb -> cb.onResult(rid, Bundle().apply { putString("uri", "file:///sdcard/a.mp4") }) }
        assertEquals(Reply.Failed(ConnectorError.PROVIDER_ERROR), s.resolve("a"))
        f.next = { rid, cb -> cb.onError(rid, 42, "https://example.invalid/secret") }
        assertEquals(Reply.Failed(ConnectorError.PROVIDER_ERROR), s.resolve("a"))
        f.next = { rid, cb -> cb.onError(rid, ConnectorError.NOT_ENTITLED.code, null) }
        assertEquals(Reply.Failed(ConnectorError.NOT_ENTITLED), s.resolve("a"))
    }

    @Test fun aProviderThatDiesFailsWhatIsWaiting() = runBlocking {
        val f = Fake()
        val s = session(f)
        s.negotiate("test")
        f.next = { _, _ -> }
        val waiting = async { s.listSources(film) }
        delay(50)
        s.providerDied()
        assertEquals(Reply.Failed(ConnectorError.PROVIDER_ERROR), waiting.await())
    }

    @Test fun onlyApprovedInstalledProvidersWithTheirAddOnOnAreOffered() {
        val approved = ApprovedProviders.forBuild(debug = true, hostPackage = context.packageName)
        val found = SourceConnector(context, approved, gate, verify = { true }).available()
        assertEquals(listOf(ComponentName(context.packageName, ApprovedProviders.REFERENCE_CLASS)), found.map { it.component })
        entitled = false
        assertTrue(SourceConnector(context, approved, gate, verify = { true }).available().isEmpty())
        entitled = true
        assertTrue("wrong certificate", SourceConnector(context, approved, gate, verify = { false }).available().isEmpty())
        assertTrue("release", SourceConnector(context, ApprovedProviders.forBuild(false, context.packageName), gate).available().isEmpty())
    }

    @Test fun openBindsNegotiatesAndCloseUnbinds() {
        val connector = SourceConnector(context, ApprovedProviders.forBuild(true, context.packageName), gate, verify = { true }, timeouts = quick)
        val a = connector.available().single()
        val binder = Robolectric.buildService(ReferenceSourceProvider::class.java).create().get().onBind(Intent())
        shadowOf(context).setComponentNameAndServiceForBindService(a.component, binder)
        val opening = CoroutineScope(Dispatchers.Unconfined).async { connector.open(a) }
        shadowOf(Looper.getMainLooper()).idle()
        val s = (runBlocking { opening.await() } as Reply.Ok).value
        assertEquals(1, s.negotiated?.protocol)
        assertEquals(1, shadowOf(context).boundServiceConnections.size)
        s.close()
        assertTrue(shadowOf(context).boundServiceConnections.isEmpty())
    }
}
