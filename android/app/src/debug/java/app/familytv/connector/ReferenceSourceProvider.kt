package app.familytv.connector

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import app.familytv.connector.v1.ISourceCallback
import app.familytv.connector.v1.ISourceProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The connector's reference companion, in debug builds only: a provider that knows one public-domain
 * film, "Night of the Living Dead" (1968), and answers with synthetic releases whose uris point at
 * example.invalid, so nothing real is ever listed or played. It shows a companion's duties: check the
 * caller, answer every request exactly once and off the binder thread, honour cancel and closeSession.
 */
class ReferenceSourceProvider : Service() {
    private val work = Executors.newSingleThreadExecutor()
    private val sessions = ConcurrentHashMap<String, MutableSet<String>>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    private class Refused(val error: ConnectorError) : Exception()

    private val binder = object : ISourceProvider.Stub() {
        override fun negotiate(requestId: String?, hello: Bundle?, callback: ISourceCallback?) = answer(requestId, callback) {
            val h = hello ?: throw Refused(ConnectorError.UNSUPPORTED_VERSION)
            if (Protocol.VERSION !in h.getInt("protocolMin", 0)..h.getInt("protocolMax", 0)) throw Refused(ConnectorError.UNSUPPORTED_VERSION)
            Bundle().apply {
                putInt("protocol", Protocol.VERSION)
                putStringArrayList("capabilities", arrayListOf())
                putString("providerName", "Reference (test data)")
            }
        }

        override fun listSources(requestId: String?, sessionId: String?, identity: Bundle?, callback: ISourceCallback?) = answer(requestId, callback) {
            val session = sessionId?.takeIf(Protocol::isId) ?: throw Refused(ConnectorError.PROVIDER_ERROR)
            val id = identity?.let(MediaIdentity::from) ?: throw Refused(ConnectorError.NOT_FOUND)
            if (id.type != MediaIdentity.Type.MOVIE || id.imdb != IMDB && id.tmdb != TMDB) throw Refused(ConnectorError.NOT_FOUND)
            sessions.getOrPut(session) { ConcurrentHashMap.newKeySet() }.addAll(RELEASES.map { it.selectionId })
            Bundle().apply { putParcelableArrayList("sources", ArrayList(RELEASES.map { it.toBundle() })) }
        }

        override fun resolve(requestId: String?, sessionId: String?, selectionId: String?, callback: ISourceCallback?) = answer(requestId, callback) {
            if (sessionId == null || selectionId == null || sessions[sessionId]?.contains(selectionId) != true) throw Refused(ConnectorError.NOT_FOUND)
            ResolvedSource("https://example.invalid/reference/$selectionId.mp4", "video/mp4", emptyMap(), System.currentTimeMillis() + 3600_000).toBundle()
        }

        override fun cancel(requestId: String?) {
            requestId?.let(cancelled::add)
        }

        override fun closeSession(sessionId: String?) {
            sessionId?.let(sessions::remove)
        }
    }

    /** Answers [requestId] once, on the work thread; a cancelled request gets no answer. */
    private fun answer(requestId: String?, callback: ISourceCallback?, body: () -> Bundle) {
        // Only the calling binder thread knows who called. A real companion checks that this is
        // Family TV's package with Family TV's signing certificate (docs/CONNECTOR.md, "Provider duties").
        val caller = Binder.getCallingUid()
        if (requestId == null || callback == null) return
        work.execute {
            if (cancelled.remove(requestId)) return@execute
            try {
                if (caller != Process.myUid()) throw Refused(ConnectorError.NOT_ENTITLED)
                callback.onResult(requestId, body())
            } catch (e: Refused) {
                runCatching { callback.onError(requestId, e.error.code, null) }
            } catch (_: RemoteException) {
                // The host went away; nothing to tell.
            } catch (_: RuntimeException) {
                runCatching { callback.onError(requestId, ConnectorError.PROVIDER_ERROR.code, null) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        work.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val IMDB = "tt0063350"
        const val TMDB = "10331"
        private const val TITLE = "Night of the Living Dead (1968), reference test data"
        val RELEASES = listOf(
            Source("ref-1080", TITLE, "1080p", 2_147_483_648, "AAC 2.0", "en"),
            Source("ref-720", TITLE, "720p", 1_073_741_824, "AAC 2.0", "en"),
            Source("ref-480", TITLE, "480p", 524_288_000, "MP3 2.0", "en"),
        )
    }
}
