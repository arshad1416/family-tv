package app.familytv.iptv

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.PlaybackException
import app.familytv.core.Http
import app.familytv.data.AppDb
import app.familytv.portal.PortalClient
import app.familytv.portal.Profile
import app.familytv.portal.Source
import app.familytv.sync.PlaybackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Moves a line to its backup server when the one in use keeps failing (parity checklist, "Spec:
 * Switch server automatically when one keeps failing"): 3 server-side stream failures in a row
 * within 10 minutes, and another of the profile's servers answers the same stream within 5 s.
 *
 * Only for a server that is down for everyone: an HTTP 5xx comes from the server itself, but a
 * connection or DNS failure could be this TV's network or an ISP block, so that only counts once
 * the portal (Cloudflare's edge) can't reach the server either. Then nothing switches and the
 * player's usual "Can't play this right now" check explains why instead.
 *
 * The choice is kept on this TV, never in the portal. The portal's first server is tried again at
 * app start and every 6 hours, and comes back as soon as a stream check on it passes.
 */
object ServerSwitch {
    const val FAILURES = 3
    const val WINDOW_MS = 10 * 60_000L
    const val CHECK_MS = 5_000L
    const val RETRY_FIRST_MS = 6 * 3600_000L
    const val NOTICE = "Server not responding, switched to backup server."

    /**
     * Why a stream failed, as the heartbeat reports it (same words as the Rust app): "HTTP 503" for
     * a server error (Cloudflare's 520-526 included), "connection failed" for refused, reset or
     * DNS, "timeout". [network] failures only count once the portal can't reach the server either.
     */
    data class Reason(val label: String, val network: Boolean) {
        companion object {
            fun http(status: Int) = Reason("HTTP $status", network = false)
            val CONNECTION = Reason("connection failed", network = true)
            val TIMEOUT = Reason("timeout", network = true)
            /** The portal's first server passed its stream check again. */
            val FIRST_SERVER_BACK = Reason("first server back", network = false)
        }
    }

    private var prefs: SharedPreferences? = null
    private const val PATH = "path."
    private val failures = Failures()
    @Volatile private var lastRetry = 0L

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("server_switch", Context.MODE_PRIVATE)
        PlaybackStatus.serverSwitch = lastSwitch()
    }

    /** The server this TV moved [s] to, while the profile still lists it. */
    fun chosen(s: Source): String? =
        prefs?.getString(s.id, null)?.takeIf { c -> s.serverUrls.orEmpty().any { it.trimEnd('/') == c } }

    /** The server to use for [s]: the one switched to, else the one the last refresh signed in on, else the first. */
    fun server(s: Source, active: String?): String? = chosen(s) ?: active ?: s.serverUrls?.firstOrNull()?.trimEnd('/')

    /** Which of [s]'s servers [url] was built on. */
    fun serverOf(s: Source, url: String): String? =
        s.serverUrls.orEmpty().map { it.trimEnd('/') }.firstOrNull { url.startsWith("$it/") }

    /** [url] moved from server [from] to server [to]. */
    fun swap(url: String, from: String, to: String): String = to.trimEnd('/') + url.removePrefix(from.trimEnd('/'))

    /**
     * Whether a playback error counts towards a switch: server errors and network failures do;
     * 401/403/404/451/511/513 and other 4xx (line, channel or local network problems), decoder and format errors never do.
     */
    fun classify(errorCode: Int, httpStatus: Int?, cause: Throwable?): Reason? {
        if (httpStatus != null) return if (httpStatus in 500..599 && httpStatus !in NOT_SERVER) Reason.http(httpStatus) else null
        if (errorCode !in 2000..2999) return null
        if (errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) return Reason.TIMEOUT
        return generateSequence(cause) { it.cause }.take(8).firstNotNullOfOrNull {
            when (it) {
                is SocketTimeoutException -> Reason.TIMEOUT
                is UnknownHostException, is ConnectException, is SocketException -> Reason.CONNECTION
                else -> null
            }
        }
    }

    // 511 is a captive portal or network sign-in page on this TV's network; 513 is a provider refusing the line.
    private val NOT_SERVER = setOf(511, 513)

    /** One more failure on [server]; true when that makes [FAILURES] in a row inside [WINDOW_MS]. */
    fun failed(sourceId: String, server: String, now: Long = System.currentTimeMillis()) = failures.add(sourceId, server, now)

    /** Something played on [sourceId]: its count starts again. */
    fun played(sourceId: String) = failures.clear(sourceId)

    /**
     * Looks for a server that plays [url] (built on [from]); null when none does, or when [reason]
     * is a network failure the portal doesn't see too. The first that passes becomes [s]'s server.
     */
    suspend fun switch(s: Source, url: String, from: String, reason: Reason, portal: PortalClient): String? {
        val others = s.serverUrls.orEmpty().map { it.trimEnd('/') }.filter { it != from }.distinct()
        if (others.isEmpty()) return null
        if (reason.network) {
            val edge = runCatching { portal.reachability(listOf(from)) }.getOrNull()?.firstOrNull() ?: return null
            if (edge.ok) return null // Cloudflare reaches it: this TV's network, not the server.
        }
        val to = others.firstOrNull { streamAnswers(swap(url, from, it)) } ?: return null
        choose(s, from, to, reason, url.removePrefix(from))
        return to
    }

    /**
     * App start, then every 6 h: back to the portal's first server for lines that moved off it,
     * once the stream that failed plays there again. Waits while something plays, since a second
     * stream can cost a one-connection line the one on screen.
     */
    suspend fun retryFirst(profile: Profile, db: AppDb, now: Long = System.currentTimeMillis()) {
        if (now - lastRetry < RETRY_FIRST_MS || PlaybackStatus.title != null) return
        lastRetry = now
        for (s in profile.sources) {
            val current = chosen(s) ?: continue
            val first = s.serverUrls?.firstOrNull()?.trimEnd('/') ?: continue
            if (current == first) continue
            // The stream that failed; any channel if the TV lost it.
            val path = prefs?.getString("$PATH${s.id}", null)
                ?: db.catalog().anyChannel(s.id)?.let { XtreamUrls.live(first, s.username.orEmpty(), s.password.orEmpty(), it.streamId).removePrefix(first) }
                ?: continue
            if (streamAnswers(first + path)) {
                choose(s, current, first, Reason.FIRST_SERVER_BACK, null)
            }
        }
    }

    /** [path]: the failed stream's URL after the server, kept on this TV to retry the first server with. */
    private fun choose(s: Source, from: String, to: String, reason: Reason, path: String?) {
        val first = s.serverUrls?.firstOrNull()?.trimEnd('/')
        val at = System.currentTimeMillis()
        prefs?.edit()?.apply {
            if (to == first) remove(s.id).remove("$PATH${s.id}") else putString(s.id, to)
            if (path != null) putString("$PATH${s.id}", path)
            putString("last.from", hostOf(from)).putString("last.to", hostOf(to)).putLong("last.at", at).putString("last.reason", reason.label)
        }?.apply()
        failures.clear(s.id)
        PlaybackStatus.serverSwitch = event(hostOf(from), hostOf(to), at, reason.label)
    }

    private fun lastSwitch(): Map<String, Any?>? = prefs?.takeIf { it.contains("last.at") }?.let {
        event(it.getString("last.from", null), it.getString("last.to", null), it.getLong("last.at", 0), it.getString("last.reason", null))
    }

    // Host names only: the URLs carry the line's username and password.
    private fun event(from: String?, to: String?, at: Long, reason: String?) = mapOf("from" to from, "to" to to, "at" to at, "reason" to reason)

    private fun hostOf(url: String) = url.toHttpUrlOrNull()?.host ?: ""

    /** The same stream on another server answers 2xx/3xx inside [CHECK_MS]. Only the headers are read. */
    private suspend fun streamAnswers(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { checkClient.newCall(Request.Builder().url(url).build()).execute().use { it.code in 200..399 } }.getOrDefault(false)
    }

    private val checkClient by lazy { Http.probeClient.newBuilder().callTimeout(CHECK_MS, TimeUnit.MILLISECONDS).build() }

    /** Failures in a row per line, on the server they happened on. */
    internal class Failures {
        private val seen = HashMap<String, Pair<String, ArrayDeque<Long>>>()

        @Synchronized fun add(sourceId: String, server: String, now: Long): Boolean {
            val times = seen[sourceId]?.takeIf { it.first == server }?.second ?: ArrayDeque<Long>().also { seen[sourceId] = server to it }
            times.addLast(now)
            while (times.isNotEmpty() && now - times.first() > WINDOW_MS) times.removeFirst()
            if (times.size < FAILURES) return false
            times.clear()
            return true
        }

        @Synchronized fun clear(sourceId: String) { seen.remove(sourceId) }
    }
}
