package app.familytv.portal

import android.content.Context
import android.os.Build
import app.familytv.BuildConfig
import app.familytv.core.Http
import app.familytv.user.SharedProgressPage
import app.familytv.discovery.DiscoveryHttp
import app.familytv.discovery.ProfileRdGateway
import app.familytv.discovery.ProfileRdKey
import app.familytv.discovery.ProfileRealDebrid
import app.familytv.discovery.SourceUnavailable
import app.familytv.user.SharedProgressRow
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class PortalException(val code: Int, message: String) : IOException(message)

/** Talks to the Family TV portal (Cloudflare Worker). All calls are suspend and run on IO. */
class PortalClient(context: Context, private val baseUrl: String = BuildConfig.PORTAL_URL.trimEnd('/'), private val allowLoopbackKeyHttp: Boolean = false) : ProfileRdGateway {
    private val prefs = context.getSharedPreferences("portal", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()
    private val keyHttp by lazy { DiscoveryHttp(Http.client) }

    override suspend fun rdKeyRead(profileId: String) = keyCall("GET", profileId)
    override suspend fun rdKeyWrite(profileId: String, revision: Long, apiKey: String) = keyCall("PUT", profileId, revision, apiKey)
    override suspend fun rdKeyRemove(profileId: String, revision: Long) = keyCall("DELETE", profileId, revision)

    private suspend fun keyCall(method: String, profileId: String, revision: Long? = null, apiKey: String? = null): ProfileRdKey {
        val base = baseUrl.toHttpUrl()
        if (base.encodedUsername.isNotEmpty() || base.encodedPassword.isNotEmpty() || base.fragment != null || base.query != null) throw SourceUnavailable("Profile key synchronization requires a portal URL without credentials, query or fragment.")
        val url = (baseUrl + "/api/device/realdebrid/api-key").toHttpUrl().newBuilder().apply {
            if (method == "GET") addQueryParameter("profileId", profileId)
        }.build()
        if (url.scheme != "https" && !(allowLoopbackKeyHttp && url.host in setOf("localhost", "127.0.0.1", "::1"))) throw SourceUnavailable("A secure HTTPS portal is required to synchronize API keys.")
        val body = if (method == "GET") null else gson.toJson(mapOf("profileId" to profileId, "revision" to revision, "apiKey" to apiKey)).toRequestBody(jsonType)
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${token ?: throw PortalException(401, "not registered")}")
            .header("Cache-Control", "no-store").method(method, body).build()
        val (status, text) = keyHttp.request(request, "Profile account settings")
        if (status !in 200..299) throw PortalException(status, when (status) {
            409 -> "The key changed on another device. Reload status and enter it again."
            403 -> "This device is no longer authorized for that profile."
            else -> "Profile account settings are unavailable (HTTP $status)."
        })
        return try { gson.fromJson(text, ProfileRdKey::class.java) ?: throw SourceUnavailable("Invalid account state.") }
        catch (_: Exception) { throw SourceUnavailable("The portal returned an invalid account state.") }
    }

    val deviceId: String? get() = prefs.getString("deviceId", null)
    private val token: String? get() = prefs.getString("token", null)

    /** Last config we got, so the TV keeps playing when the portal is unreachable. */
    var cachedConfig: DeviceConfig?
        get() = prefs.getString("config", null)?.let { runCatching { gson.fromJson(it, DeviceConfig::class.java) }.getOrNull() }
        set(v) = prefs.edit().putString("config", v?.let { gson.toJson(it.copy(commands = null)) }).apply()

    // The screen and the background worker start together; without this lock each registered
    // its own device and the TV kept a different one from the code it showed.
    suspend fun ensureRegistered() = registerLock.withLock {
        if (token != null) return@withLock
        val r = post(
            "/api/device/register",
            RegisterRequest(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, BuildConfig.VERSION_NAME),
            RegisterResponse::class.java,
            auth = false,
        )
        prefs.edit().putString("deviceId", r.deviceId).putString("token", r.deviceToken).apply()
    }

    /** Forget this device (portal removed it); the next call registers again and shows a new code. */
    fun reset() = prefs.edit().clear().apply()

    suspend fun config(): DeviceConfig {
        ensureRegistered()
        return try {
            get("/api/device/config", DeviceConfig::class.java).also { if (it.paired) cachedConfig = it }
        } catch (e: PortalException) {
            if (e.code == 401) {
                val stale = token
                // Only forget the device once, even if several callers saw the same 401.
                registerLock.withLock { if (token == stale) reset() }
                ensureRegistered()
                get("/api/device/config", DeviceConfig::class.java)
            } else throw e
        }
    }

    suspend fun heartbeat(status: Map<String, Any?>): HeartbeatResponse =
        post("/api/device/heartbeat", mapOf("appVersion" to BuildConfig.VERSION_NAME, "status" to status), HeartbeatResponse::class.java)

    suspend fun ack(commandId: String, result: Any?) {
        post("/api/device/commands/$commandId/ack", mapOf("result" to result), Map::class.java)
    }

    suspend fun uploadDiagnostics(verdict: String, report: Any) {
        post("/api/device/diagnostics", mapOf("verdict" to verdict, "report" to report), Map::class.java)
    }

    /** This TV's category names per kind, so the portal can arrange them for the profile. */
    suspend fun uploadCategories(names: Map<String, List<String>>) {
        post("/api/device/categories", names, Map::class.java)
    }

    suspend fun reachability(urls: List<String>): List<ReachResult> =
        post("/api/device/reachability", mapOf("urls" to urls), ReachResponse::class.java).results

    /**
     * Trakt sign-in calls that need the client secret: "code", "token", "refresh" or "revoke".
     * The portal adds the secret and answers with Trakt's own status.
     */
    suspend fun trakt(call: String, body: Map<String, String> = emptyMap()): TraktProxyResult =
        post("/api/device/trakt/$call", body, TraktProxyResult::class.java)

    /** Hands a new Real-Debrid sign-in to the portal, which shares it with the profile's other TVs. */
    suspend fun realDebridLink(credentials: Map<String, Any?>) {
        post("/api/device/realdebrid/link", credentials, Map::class.java)
    }

    /**
     * The profile's Real-Debrid token; [rejected] is one Real-Debrid refused, so the portal refreshes it.
     * Throws [PortalException] 404 when the profile has no account, 410 when it must sign in again.
     */
    suspend fun realDebridToken(rejected: String? = null): RealDebridToken =
        if (rejected == null) get("/api/device/realdebrid/token", RealDebridToken::class.java)
        else post("/api/device/realdebrid/token", mapOf("rejected" to rejected), RealDebridToken::class.java)

    /** Signs every TV in the profile out of Real-Debrid. */
    suspend fun realDebridUnlink() {
        post("/api/device/realdebrid/unlink", emptyMap<String, String>(), Map::class.java)
    }

    /** Watched marks and resume points other TVs in the profile changed after [since]. 404 from a portal without them. */
    suspend fun progressPull(since: Long): SharedProgressPage = get("/api/device/progress?since=$since", SharedProgressPage::class.java)

    /** This TV's changed rows (up to 500); the portal keeps whichever change is later. */
    suspend fun progressPush(rows: List<SharedProgressRow>) {
        post("/api/device/progress", mapOf("rows" to rows), Map::class.java)
    }

    private suspend fun <T> get(path: String, type: Class<T>): T = call(Request.Builder().url(baseUrl + path).get(), type, true)

    private suspend fun <T> post(path: String, body: Any, type: Class<T>, auth: Boolean = true): T =
        call(Request.Builder().url(baseUrl + path).post(gson.toJson(body).toRequestBody(jsonType)), type, auth)

    private suspend fun <T> call(b: Request.Builder, type: Class<T>, auth: Boolean): T = withContext(Dispatchers.IO) {
        if (auth) b.header("Authorization", "Bearer ${token ?: throw PortalException(401, "not registered")}")
        Http.client.newCall(b.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw PortalException(res.code, text.take(200))
            gson.fromJson(text, type)
        }
    }

    private companion object {
        val registerLock = Mutex()
    }
}
