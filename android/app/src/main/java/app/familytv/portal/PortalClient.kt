package app.familytv.portal

import android.content.Context
import android.os.Build
import app.familytv.BuildConfig
import app.familytv.core.Http
import app.familytv.start.OpenOnStart
import app.familytv.usage.Usage
import app.familytv.user.SharedProgressPage
import app.familytv.user.SharedProgressRow
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class PortalException(val code: Int, message: String) : IOException(message)

/** Talks to the Family TV portal (Cloudflare Worker). All calls are suspend and run on IO. */
class PortalClient(context: Context, private val baseUrl: String = BuildConfig.PORTAL_URL.trimEnd('/')) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("portal", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()

    val deviceId: String? get() = prefs.getString("deviceId", null)
    private val token: String? get() = prefs.getString("token", null)

    /** Last config we got, so the TV keeps playing when the portal is unreachable. */
    var cachedConfig: DeviceConfig?
        get() = prefs.getString("config", null)?.let { runCatching { gson.fromJson(it, DeviceConfig::class.java) }.getOrNull() }
        set(v) = prefs.edit().putString("config", v?.let { gson.toJson(it.copy(commands = null)) }).putLong("contactAt", if (v != null) System.currentTimeMillis() else 0).apply()

    /** When the portal last answered this TV (a config or a heartbeat), in ms; add-ons pause once it's too long ago. */
    val contactAt: Long get() = prefs.getLong("contactAt", 0)

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

    /**
     * The portal dropped this TV after it was set up (its token was rejected, or it was unpaired),
     * so the pairing screen says so until it pairs again. A network error never sets it.
     */
    var unpaired: Boolean
        get() = prefs.getBoolean("unpaired", false)
        private set(v) = prefs.edit().putBoolean("unpaired", v).apply()

    suspend fun config(): DeviceConfig {
        ensureRegistered()
        return try {
            get("/api/device/config", DeviceConfig::class.java).also {
                if (!it.paired) {
                    if (cachedConfig != null) unpaired = true
                } else {
                    unpaired = false
                    cachedConfig = it
                    OpenOnStart.sync(appContext, it.profile?.openOnStart == true)
                    Usage.enabled = it.profile?.usageStats == true
                }
            }
        } catch (e: PortalException) {
            if (e.code == 401) {
                val stale = token
                // Only forget the device once, even if several callers saw the same 401.
                registerLock.withLock {
                    if (token == stale) {
                        val wasSetUp = cachedConfig != null
                        reset()
                        if (wasSetUp) unpaired = true
                    }
                }
                ensureRegistered()
                get("/api/device/config", DeviceConfig::class.java)
            } else throw e
        }
    }

    suspend fun heartbeat(status: Map<String, Any?>): HeartbeatResponse =
        post("/api/device/heartbeat", mapOf("appVersion" to BuildConfig.VERSION_NAME, "status" to status), HeartbeatResponse::class.java).also {
            if (it.paired) prefs.edit().putLong("contactAt", System.currentTimeMillis()).apply()
        }

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

    /** Watched marks and resume points other TVs in the profile changed after [since]. 404 from a portal without them. */
    suspend fun progressPull(since: Long): SharedProgressPage = get("/api/device/progress?since=$since", SharedProgressPage::class.java)

    /** This TV's changed rows (up to 500); the portal keeps whichever change is later. */
    suspend fun progressPush(rows: List<SharedProgressRow>) {
        post("/api/device/progress", mapOf("rows" to rows), Map::class.java)
    }

    /** Anonymous usage events (up to 500); false when the profile has Usage stats off. */
    suspend fun usage(app: String, appVersion: String, events: List<Usage.Event>): Boolean =
        post("/api/device/usage", mapOf("app" to app, "appVersion" to appVersion, "events" to events), Map::class.java)["on"] != false

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
