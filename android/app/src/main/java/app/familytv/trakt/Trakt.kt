package app.familytv.trakt

import android.content.Context
import app.familytv.FamilyTvApp
import app.familytv.core.Http
import app.familytv.trakt.model.TDeviceCode
import app.familytv.trakt.model.TSettings
import app.familytv.trakt.model.TToken
import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.lang.reflect.Type

class TraktException(val code: Int, message: String) : IOException(message)

/**
 * The Trakt account on this device: device-code sign-in, tokens (refreshed through the portal,
 * which holds the client secret), and authenticated calls to api.trakt.tv.
 * Syncing itself is in [TraktSync].
 */
class Trakt(private val app: FamilyTvApp) {
    sealed interface State {
        /** The portal has no Trakt credentials: the Settings section stays hidden. */
        data object Off : State
        data class SignedOut(val message: String? = null) : State
        data class Linking(val userCode: String, val url: String, val expiresAt: Long) : State
        data class SignedIn(val name: String, val lastSync: Long, val syncing: Boolean, val error: String?) : State
    }

    private val prefs = app.getSharedPreferences("trakt", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()
    private val refreshLock = Mutex()
    private var linkJob: Job? = null
    private var syncing = false

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state

    val sync by lazy { TraktSync(app, this) }

    /** From the portal's config; null when Trakt isn't set up there. */
    val clientId: String? get() = app.portal.cachedConfig?.trakt?.clientId?.takeIf { it.isNotBlank() }
    val signedIn: Boolean get() = clientId != null && prefs.getString(ACCESS, null) != null

    fun refreshState() {
        if (linkJob?.isActive == true) return
        _state.value = when {
            clientId == null -> State.Off
            !signedIn -> State.SignedOut(prefs.getString(MESSAGE, null))
            else -> State.SignedIn(
                prefs.getString(NAME, null) ?: "your Trakt account",
                prefs.getLong(LAST_SYNC, 0),
                syncing,
                prefs.getString(MESSAGE, null),
            )
        }
    }

    // ---- sign-in ----

    fun startLink() {
        linkJob?.cancel()
        linkJob = app.scope.launch { link() }
    }

    fun cancelLink() {
        linkJob?.cancel()
        linkJob = null
        refreshState()
    }

    private suspend fun link() {
        val message: String? = try {
            linkOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Couldn't reach Trakt. Check the internet connection and try again."
        }
        prefs.edit().putString(MESSAGE, message).apply()
        linkJob = null
        refreshState()
        if (message == null) sync.soon(0)
    }

    /** Shows the code, then polls until the person approves it on trakt.tv. Returns an error message or null. */
    private suspend fun linkOnce(): String? {
        val r = app.portal.trakt("code")
        val code = parse<TDeviceCode>(r.data)
        if (r.status != 200 || code == null) return "Trakt didn't give a sign-in code (error ${r.status}). Try again later."
        val expires = System.currentTimeMillis() + code.expiresIn * 1000
        _state.value = State.Linking(code.userCode, code.verificationUrl, expires)
        var interval = code.interval.coerceAtLeast(1) * 1000
        while (System.currentTimeMillis() < expires) {
            delay(interval)
            val t = app.portal.trakt("token", mapOf("code" to code.deviceCode))
            when (t.status) {
                200 -> {
                    saveToken(parse<TToken>(t.data) ?: return "Trakt sign-in failed. Try again.")
                    prefs.edit().putString(NAME, runCatching { accountName() }.getOrNull()).apply()
                    return null
                }
                400 -> Unit // not approved yet
                429 -> interval += 1000 // polling too fast
                404, 409 -> return "That code didn't work. Try again."
                410 -> return "The code ran out. Try again."
                418 -> return "Sign-in was declined on trakt.tv."
                else -> return "Trakt sign-in failed (error ${t.status}). Try again."
            }
        }
        return "The code ran out. Try again."
    }

    private suspend fun accountName(): String? {
        val s: TSettings = get("/users/settings", TSettings::class.java) ?: return null
        return s.user?.name?.takeIf { it.isNotBlank() } ?: s.user?.username
    }

    /** Signs out here and revokes the token on Trakt. Favourites stay on this device. */
    suspend fun disconnect() {
        linkJob?.cancel()
        val token = prefs.getString(ACCESS, null)
        if (token != null && clientId != null) runCatching { app.portal.trakt("revoke", mapOf("token" to token)) }
        signOut(null)
    }

    private suspend fun signOut(message: String?) {
        prefs.edit().clear().putString(MESSAGE, message).apply()
        // Everything goes up again if this TV connects to an account later.
        withContext(Dispatchers.IO) {
            val dao = app.userDb.user()
            dao.dropTombstones()
            dao.markAllUnsynced()
        }
        refreshState()
    }

    // ---- sync status, set by TraktSync ----

    internal fun syncStarted() { syncing = true; refreshState() }

    internal fun syncFinished(error: String?) {
        syncing = false
        // Signed out mid-sync (dead refresh token): keep the sign-out message.
        if (!signedIn) return refreshState()
        prefs.edit().apply { if (error == null) putLong(LAST_SYNC, System.currentTimeMillis()) }.putString(MESSAGE, error).apply()
        refreshState()
    }

    // ---- tokens ----

    private fun saveToken(t: TToken) {
        val created = if (t.createdAt > 0) t.createdAt * 1000 else System.currentTimeMillis()
        prefs.edit()
            .putString(ACCESS, t.accessToken)
            .putString(REFRESH, t.refreshToken)
            .putLong(EXPIRES, created + t.expiresIn * 1000)
            .remove(MESSAGE)
            .apply()
    }

    /**
     * A usable access token, refreshed through the portal when it is close to expiry or after a
     * 401 ([rejected] is the token Trakt refused). Signs out when the refresh token is dead too.
     */
    private suspend fun token(rejected: String? = null): String? = refreshLock.withLock {
        val access = prefs.getString(ACCESS, null) ?: return@withLock null
        val fresh = prefs.getLong(EXPIRES, 0) - System.currentTimeMillis() > REFRESH_MARGIN_MS
        // Another caller may have refreshed while we waited for the lock.
        if (rejected == null && fresh || rejected != null && rejected != access) return@withLock access
        val refresh = prefs.getString(REFRESH, null) ?: return@withLock null.also { signOut(EXPIRED) }
        val r = app.portal.trakt("refresh", mapOf("refreshToken" to refresh))
        when (r.status) {
            200 -> parse<TToken>(r.data)?.let { saveToken(it); it.accessToken } ?: throw TraktException(r.status, "empty token")
            400, 401, 403 -> { signOut(EXPIRED); null }
            // Trakt or the portal is down: keep the old token for now; it may still work.
            else -> if (rejected == null) access else throw TraktException(r.status, "token refresh failed")
        }
    }

    private inline fun <reified T> parse(e: JsonElement?): T? = e?.takeIf { it.isJsonObject }?.let { gson.fromJson(it, T::class.java) }

    // ---- API ----

    suspend fun <T> get(path: String, type: Type): T? = call("GET", path, null, type)

    suspend fun post(path: String, body: Any) { call<Any>("POST", path, body, null) }

    private suspend fun <T> call(method: String, path: String, body: Any?, type: Type?): T? = withContext(Dispatchers.IO) {
        val id = clientId ?: throw TraktException(0, "Trakt is not set up")
        var token = token() ?: throw TraktException(401, "not signed in")
        var (code, text) = exec(method, path, body, id, token)
        if (code == 401) {
            token = token(rejected = token) ?: throw TraktException(401, "signed out")
            val retry = exec(method, path, body, id, token)
            code = retry.first; text = retry.second
        }
        when {
            code in 200..299 -> if (type == null || text.isBlank()) null else gson.fromJson<T>(text, type)
            code == 409 && path.startsWith("/scrobble") -> null // the same scrobble just went through
            else -> throw TraktException(code, "Trakt $method $path failed: HTTP $code")
        }
    }

    private fun exec(method: String, path: String, body: Any?, clientId: String, token: String): Pair<Int, String> {
        val req = Request.Builder()
            .url(API + path)
            .header("Content-Type", "application/json")
            .header("trakt-api-version", "2")
            .header("trakt-api-key", clientId)
            .header("Authorization", "Bearer $token")
            .method(method, body?.let { gson.toJson(it).toRequestBody(jsonType) })
            .build()
        return Http.client.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
    }

    private companion object {
        const val API = "https://api.trakt.tv"
        const val ACCESS = "access"
        const val REFRESH = "refresh"
        const val EXPIRES = "expiresAt"
        const val NAME = "name"
        const val LAST_SYNC = "lastSync"
        const val MESSAGE = "message"
        const val EXPIRED = "Trakt sign-in ran out. Connect again to keep syncing."
        const val REFRESH_MARGIN_MS = 24 * 3_600_000L
    }
}
