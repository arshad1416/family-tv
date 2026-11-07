package app.familytv.connector

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import app.familytv.BuildConfig
import app.familytv.addons.Addons
import app.familytv.connector.v1.ISourceCallback
import app.familytv.connector.v1.ISourceProvider
import app.familytv.portal.PortalClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The host adapter: the only place the app touches a companion. Screens ask it for providers, open
 * a [SourceSession] for a title, list, resolve, and hand the [ResolvedSource] to the player; the
 * contract never sees screens or the player, so either can change without a new protocol version.
 * With no companion installed (or none approved, as in every release build so far) [available] is
 * empty and nothing else in the app changes.
 *
 * [gate] runs before a provider is listed, before every list and again before every resolve: it is
 * the add-on's [Addons.isActive] plus the caller's own profile and parental-PIN checks.
 */
class SourceConnector(
    private val context: Context,
    private val providers: List<ApprovedProvider>,
    private val gate: (addonId: String) -> Boolean,
    private val verify: (ApprovedProvider) -> Boolean = { p ->
        val pm = context.packageManager
        ApprovedProviders.matches(p, ApprovedProviders.signers(pm, p.packageName), ApprovedProviders.signers(pm, context.packageName))
    },
    private val timeouts: Timeouts = Timeouts(),
) {
    data class Timeouts(val bindMs: Long = 5_000, val negotiateMs: Long = 5_000, val listMs: Long = 20_000, val resolveMs: Long = 20_000)

    data class Available(val provider: ApprovedProvider, val component: ComponentName)

    /** Providers the host may call now: approved, installed with the pinned certificate, speaking this protocol, add-on on. */
    fun available(): List<Available> {
        if (providers.isEmpty()) return emptyList()
        @Suppress("DEPRECATION")
        val found = context.packageManager.queryIntentServices(Intent(Protocol.ACTION), PackageManager.GET_META_DATA)
        return found.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            val p = providers.firstOrNull { it.packageName == si.packageName } ?: return@mapNotNull null
            val min = si.metaData?.getInt(Protocol.META_MIN, 0) ?: 0
            val max = si.metaData?.getInt(Protocol.META_MAX, 0) ?: 0
            if (Protocol.VERSION !in min..max || !gate(p.addonId) || !verify(p)) return@mapNotNull null
            Available(p, ComponentName(si.packageName, si.name))
        }.distinctBy { it.provider }
    }

    /** Binds [a] and agrees on the protocol. Call from the main thread; replies come back there. */
    suspend fun open(a: Available): Reply<SourceSession> {
        if (!gate(a.provider.addonId) || !verify(a.provider)) return Reply.Failed(ConnectorError.NOT_ENTITLED)
        val bound = CompletableDeferred<IBinder?>()
        var session: SourceSession? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { bound.complete(binder) }
            override fun onServiceDisconnected(name: ComponentName) { session?.providerDied() }
            override fun onBindingDied(name: ComponentName) { bound.complete(null); session?.providerDied() }
            override fun onNullBinding(name: ComponentName) { bound.complete(null) }
        }
        val unbind = { runCatching { context.unbindService(conn) }; Unit }
        val ok = try {
            context.bindService(Intent(Protocol.ACTION).setComponent(a.component), conn, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) {
            false
        }
        if (!ok) { unbind(); return Reply.Failed(ConnectorError.PROVIDER_ERROR) }
        try {
            val binder = withTimeoutOrNull(timeouts.bindMs) { bound.await() }
            // Checked again now it's bound: the package may have been replaced in between.
            if (binder == null || !verify(a.provider)) { unbind(); return Reply.Failed(if (binder == null) ConnectorError.TIMEOUT else ConnectorError.NOT_ENTITLED) }
            val s = SourceSession(ISourceProvider.Stub.asInterface(binder), a.provider.addonId, gate, timeouts, unbind)
            session = s
            return when (val n = s.negotiate(BuildConfig.VERSION_NAME)) {
                is Reply.Ok -> Reply.Ok(s)
                is Reply.Failed -> { s.close(); n }
            }
        } catch (e: CancellationException) {
            session?.close() ?: unbind()
            throw e
        }
    }

    companion object {
        /** The app's connector: this build's approved providers, gated by their add-ons and by [allowed] (profile and PIN checks). */
        fun forApp(context: Context, portal: PortalClient, allowed: () -> Boolean): SourceConnector {
            val providers = ApprovedProviders.forBuild(BuildConfig.DEBUG, context.packageName)
            // An approved provider is this version's feature for its add-on.
            val known = Addons.KNOWN + providers.map { it.addonId }
            return SourceConnector(context, providers, gate = { id ->
                allowed() && Addons.isActive(id, portal.cachedConfig, portal.contactAt, System.currentTimeMillis(), known)
            })
        }
    }
}

/**
 * One title's selection session with one provider: list its sources, resolve the one picked, close.
 * Each call waits at most its timeout, cancels the request at the provider when the caller stops
 * waiting, and only resolves selection ids this session was given.
 */
class SourceSession internal constructor(
    private val provider: ISourceProvider,
    private val addonId: String,
    private val gate: (String) -> Boolean,
    private val timeouts: SourceConnector.Timeouts = SourceConnector.Timeouts(),
    private val onClose: () -> Unit = {},
) : Closeable {
    val id: String = UUID.randomUUID().toString()
    var negotiated: Negotiated? = null
        private set
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Reply<Bundle>>>()
    private val issued = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var closed = false

    private val callback = object : ISourceCallback.Stub() {
        override fun onResult(requestId: String?, result: Bundle?) {
            pending.remove(requestId ?: return)?.complete(if (result != null) Reply.Ok(result) else Reply.Failed(ConnectorError.PROVIDER_ERROR))
        }

        override fun onError(requestId: String?, code: Int, detail: String?) {
            // detail may carry anything the provider likes, so it is never shown or logged.
            pending.remove(requestId ?: return)?.complete(Reply.Failed(ConnectorError.of(code)))
        }
    }

    internal suspend fun negotiate(hostVersion: String): Reply<Negotiated> {
        // A provider that answers with a version the host didn't offer is as good as "unsupported".
        val r = call(timeouts.negotiateMs, ConnectorError.UNSUPPORTED_VERSION, { rid, cb -> provider.negotiate(rid, Protocol.hello(hostVersion), cb) }, Protocol::negotiated)
        if (r is Reply.Ok) negotiated = r.value
        return r
    }

    suspend fun listSources(identity: MediaIdentity): Reply<List<Source>> {
        refuse()?.let { return it }
        if (!identity.isValid) return Reply.Failed(ConnectorError.NOT_FOUND)
        val r = call(timeouts.listMs, ConnectorError.PROVIDER_ERROR, { rid, cb -> provider.listSources(rid, id, identity.toBundle(), cb) }, Protocol::sources)
        if (r is Reply.Ok) issued.addAll(r.value.map { it.selectionId })
        return r
    }

    /** The playback handoff for [selectionId]; the gate is checked again here, right before the player gets it. */
    suspend fun resolve(selectionId: String): Reply<ResolvedSource> {
        refuse()?.let { return it }
        if (selectionId !in issued) return Reply.Failed(ConnectorError.NOT_FOUND)
        return call(timeouts.resolveMs, ConnectorError.PROVIDER_ERROR, { rid, cb -> provider.resolve(rid, id, selectionId, cb) }, Protocol::resolved)
    }

    override fun close() {
        if (closed) return
        closed = true
        drain(ConnectorError.CANCELLED)
        runCatching { provider.closeSession(id) }
        onClose()
    }

    internal fun providerDied() {
        closed = true
        drain(ConnectorError.PROVIDER_ERROR)
        onClose()
    }

    private fun drain(error: ConnectorError) {
        pending.keys.toList().forEach { pending.remove(it)?.complete(Reply.Failed(error)) }
    }

    private fun refuse(): Reply.Failed? = when {
        closed -> Reply.Failed(ConnectorError.CANCELLED)
        negotiated == null -> Reply.Failed(ConnectorError.UNSUPPORTED_VERSION)
        !gate(addonId) -> Reply.Failed(ConnectorError.NOT_ENTITLED)
        else -> null
    }

    /** Sends one request and waits for its answer; [read] turns the result Bundle into a value, null when it is [malformed]. */
    private suspend fun <T : Any> call(timeoutMs: Long, malformed: ConnectorError, send: (String, ISourceCallback) -> Unit, read: (Bundle) -> T?): Reply<T> {
        if (closed) return Reply.Failed(ConnectorError.CANCELLED)
        val rid = UUID.randomUUID().toString()
        val answer = CompletableDeferred<Reply<Bundle>>()
        pending[rid] = answer
        try {
            try {
                send(rid, callback)
            } catch (_: RemoteException) {
                pending.remove(rid)
                return Reply.Failed(ConnectorError.PROVIDER_ERROR)
            }
            val r = withTimeoutOrNull(timeoutMs) { answer.await() }
            if (r == null) {
                pending.remove(rid)
                runCatching { provider.cancel(rid) }
                return Reply.Failed(ConnectorError.TIMEOUT)
            }
            if (r is Reply.Failed) return r
            // The Bundle unparcels here; a provider's own Parcelable class inside it throws, and counts as malformed.
            val v = try { read((r as Reply.Ok).value) } catch (_: RuntimeException) { null }
            return if (v != null) Reply.Ok(v) else Reply.Failed(malformed)
        } catch (e: CancellationException) {
            if (pending.remove(rid) != null) runCatching { provider.cancel(rid) }
            throw e
        }
    }
}
