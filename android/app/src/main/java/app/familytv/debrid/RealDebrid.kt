package app.familytv.debrid

import android.content.Context
import app.familytv.FamilyTvApp
import app.familytv.core.Http
import app.familytv.data.Kind
import app.familytv.portal.PortalException
import app.familytv.portal.RealDebridPortal
import app.familytv.trakt.PlayItem
import app.familytv.user.ProgressKey
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
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
import okhttp3.FormBody
import okhttp3.Request
import java.io.File
import java.io.IOException

class RealDebridException(val code: Int, message: String) : IOException(message)

/**
 * The profile's Real-Debrid account, a backup source for movies and episodes already in the catalog:
 * device-code sign-in (the portal then shares it with every TV in the profile and keeps the token
 * fresh), the account's video files ([index]), and turning one into a playable link.
 * It never searches for anything: only what the person added to their own account.
 */
class RealDebrid(private val app: FamilyTvApp) {
    sealed interface State {
        data class SignedOut(val message: String? = null) : State
        data class Linking(val userCode: String, val url: String, val qrUrl: String, val expiresAt: Long) : State
        data class SignedIn(val name: String, val premiumUntil: String?, val files: Int, val lastRefresh: Long, val refreshing: Boolean, val error: String?) : State
    }

    private val prefs = app.getSharedPreferences("realdebrid", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val libraryFile = File(app.filesDir, "realdebrid-library.json")
    private val tokenLock = Mutex()
    private val refreshLock = Mutex()
    private var token: Pair<String, Long>? = null
    private var linkJob: Job? = null
    private var refreshing = false

    private val _state = MutableStateFlow<State>(State.SignedOut())
    val state: StateFlow<State> = _state

    private val _index = MutableStateFlow(RdIndex.EMPTY)
    /** The account's files by title; empty when the profile has no account. */
    val index: StateFlow<RdIndex> = _index

    // This TV's own choice, kept when the account signs out (unlike [prefs]).
    private val settings = app.getSharedPreferences("realdebrid_settings", Context.MODE_PRIVATE)
    private val _playFirst = MutableStateFlow(settings.getBoolean(PLAY_FIRST, false))
    /** "Play from Real-Debrid first": the account's best copy plays, and the TV stream is the backup. */
    val playFirst: StateFlow<Boolean> = _playFirst

    fun setPlayFirst(on: Boolean) {
        settings.edit().putBoolean(PLAY_FIRST, on).apply()
        _playFirst.value = on
        reindex()
    }

    /** Ranks the copies again after a setting that orders them changed. */
    fun reindex() { _index.value = index(_index.value.files) }

    private fun index(files: List<RdFile>) =
        if (files.isEmpty()) RdIndex.EMPTY else RdIndex(files, _playFirst.value, app.quality[Kind.MOVIE], app.quality[Kind.SERIES])

    private val linked: Boolean get() = prefs.getBoolean(LINKED, false)

    init {
        // The portal's config says whether another TV in the profile already signed in.
        if (app.portal.cachedConfig?.realDebrid != null) prefs.edit().putBoolean(LINKED, true).apply()
        app.scope.launch { if (linked) loadCached() }
        refreshState()
    }

    fun refreshState() {
        if (linkJob?.isActive == true) return
        _state.value = if (!linked) State.SignedOut(prefs.getString(MESSAGE, null)) else State.SignedIn(
            prefs.getString(NAME, null) ?: "your Real-Debrid account",
            prefs.getString(PREMIUM, null),
            _index.value.fileCount,
            prefs.getLong(LAST_REFRESH, 0),
            refreshing,
            prefs.getString(MESSAGE, null),
        )
    }

    /** After each config from the portal: picks up a sign-in or sign-out made on another TV in the profile. */
    fun onConfig(account: RealDebridPortal?) {
        when {
            account != null && !linked -> { prefs.edit().putBoolean(LINKED, true).apply(); app.scope.launch { refresh(force = true) } }
            account == null && linked -> app.scope.launch { token = null; refresh(force = true) }
            else -> refreshSoon()
        }
    }

    // ---- sign-in ----

    fun startLink() {
        linkJob?.cancel()
        linkJob = app.scope.launch {
            val message = try {
                linkOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't reach Real-Debrid. Check the internet connection and try again."
            }
            prefs.edit().putString(MESSAGE, message).apply()
            linkJob = null
            refreshState()
            if (message == null) refresh(force = true)
        }
    }

    fun cancelLink() {
        linkJob?.cancel()
        linkJob = null
        refreshState()
    }

    /** Shows the code, waits for the person to approve it, then hands the account to the portal. Returns an error message or null. */
    private suspend fun linkOnce(): String? {
        val code = gson.fromJson(send(Request.Builder().url("$OAUTH/device/code?client_id=$OPEN_CLIENT&new_credentials=yes")).second, DeviceCode::class.java)
            ?: return "Real-Debrid didn't give a sign-in code. Try again later."
        val expires = System.currentTimeMillis() + code.expiresIn * 1000
        _state.value = State.Linking(code.userCode, code.verificationUrl, code.directUrl ?: code.verificationUrl, expires)
        var creds: Credentials? = null
        while (creds == null) {
            if (System.currentTimeMillis() > expires) return "The code ran out. Try again."
            delay(code.interval.coerceAtLeast(1) * 1000L)
            val (status, text) = send(Request.Builder().url("$OAUTH/device/credentials?client_id=$OPEN_CLIENT&code=${code.deviceCode}"))
            if (status == 200) creds = gson.fromJson(text, Credentials::class.java)?.takeIf { it.clientId != null && it.clientSecret != null }
        }
        val form = FormBody.Builder()
            .add("client_id", creds.clientId!!).add("client_secret", creds.clientSecret!!)
            .add("code", code.deviceCode).add("grant_type", GRANT).build()
        val (status, text) = send(Request.Builder().url("$OAUTH/token").post(form))
        val t = gson.fromJson(text, Token::class.java)?.takeIf { status == 200 && it.accessToken != null }
            ?: return "Real-Debrid sign-in failed (error $status). Try again."
        val user = runCatching { gson.fromJson(api("/user", t.accessToken!!).second, User::class.java) }.getOrNull()
        app.portal.realDebridLink(
            mapOf(
                "clientId" to creds.clientId, "clientSecret" to creds.clientSecret,
                "accessToken" to t.accessToken, "refreshToken" to t.refreshToken, "expiresIn" to t.expiresIn,
                "username" to user?.username,
            ),
        )
        token = t.accessToken!! to System.currentTimeMillis() + t.expiresIn * 1000
        prefs.edit().putBoolean(LINKED, true).putString(NAME, user?.username).putString(PREMIUM, user?.premiumUntil()).remove(MESSAGE).apply()
        return null
    }

    /** Signs the whole profile out (every TV in it) and forgets the files here. */
    /** Signs every TV in the profile out; this one only once the portal has dropped the account, or it would come back with the next config. */
    suspend fun disconnect() {
        linkJob?.cancel()
        if (runCatching { app.portal.realDebridUnlink() }.isSuccess) return signedOut(null)
        prefs.edit().putString(MESSAGE, "Couldn't reach the portal to disconnect. Try again.").apply()
        refreshState()
    }

    private suspend fun signedOut(message: String?) {
        token = null
        prefs.edit().clear().putString(MESSAGE, message).apply()
        _index.value = RdIndex.EMPTY
        withContext(Dispatchers.IO) { libraryFile.delete() }
        refreshState()
    }

    // ---- the account's files ----

    /** Fetches the file list again when it is older than six hours (or [force]), and notices a sign-in on another TV. */
    fun refreshSoon() {
        if (System.currentTimeMillis() - prefs.getLong(LAST_REFRESH, 0) > REFRESH_EVERY_MS) app.scope.launch { refresh(force = false) }
    }

    suspend fun refresh(force: Boolean) {
        if (!refreshLock.tryLock()) return
        try {
            if (!force && System.currentTimeMillis() - prefs.getLong(LAST_REFRESH, 0) < REFRESH_EVERY_MS) return
            // Another TV may have signed in or out since this one last asked.
            val access = try {
                accessToken()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return // no account, or the portal is out of reach: try again next time
            }
            prefs.edit().putBoolean(LINKED, true).apply()
            refreshing = true
            refreshState()
            val error = try {
                gson.fromJson(api("/user", access).second, User::class.java)?.let { u ->
                    prefs.edit().putString(NAME, u.username).putString(PREMIUM, u.premiumUntil()).apply()
                }
                val files = fetchFiles()
                _index.value = index(files)
                prefs.edit().putLong(LAST_REFRESH, System.currentTimeMillis()).apply()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't load your Real-Debrid files: ${e.message}"
            }
            refreshing = false
            if (linked) prefs.edit().putString(MESSAGE, error).apply()
            refreshState()
        } finally {
            refreshLock.unlock()
        }
    }

    /**
     * Every finished torrent's video files with their hoster links. A torrent's files don't change
     * once it has finished, so only new ones cost a call.
     */
    private suspend fun fetchFiles(): List<RdFile> {
        val cached = readCache().associateBy { it.id }
        val torrents = mutableListOf<Torrent>()
        for (page in 1..MAX_PAGES) {
            val batch: List<Torrent> = gson.fromJson(call("/torrents?limit=$PAGE&page=$page").ifBlank { "[]" }, object : TypeToken<List<Torrent>>() {}.type) ?: emptyList()
            torrents += batch
            if (batch.size < PAGE) break
        }
        val out = torrents.filter { it.status == "downloaded" && it.links.isNotEmpty() }.map { t ->
            cached[t.id]?.takeIf { it.links == t.links } ?: run {
                delay(250) // Real-Debrid allows 250 calls a minute
                val info = gson.fromJson(call("/torrents/info/${t.id}"), TorrentInfo::class.java)
                CachedTorrent(t.id, t.links, files(t, info))
            }
        }
        withContext(Dispatchers.IO) { libraryFile.writeText(gson.toJson(out)) }
        val fromTorrents = out.flatMap { it.files }
        val known = fromTorrents.mapTo(HashSet()) { it.link }
        return fromTorrents + fetchDownloads().filter { known.add(it.link) }
    }

    /** Files unrestricted from other hosters (Downloads). Playing a torrent file adds it there too, hence the de-duplication above. */
    private suspend fun fetchDownloads(): List<RdFile> {
        val out = mutableListOf<RdFile>()
        for (page in 1..MAX_PAGES) {
            val batch: List<Download> = gson.fromJson(call("/downloads?limit=$PAGE&page=$page").ifBlank { "[]" }, object : TypeToken<List<Download>>() {}.type) ?: emptyList()
            batch.mapNotNullTo(out) { d -> d.link?.let { RdFile(d.filename, "", d.filesize, it) } }
            if (batch.size < PAGE) break
        }
        return out.filter { RdNames.isVideo(it.name) && !RdNames.isExtra(it.name) && it.bytes >= MIN_BYTES }
    }

    /** A torrent's chosen files pair up with its links in file order; a torrent packed into one archive has nothing playable. */
    private fun files(t: Torrent, info: TorrentInfo?): List<RdFile> {
        val chosen = info?.files.orEmpty().filter { it.selected == 1 }.sortedBy { it.id }
        if (chosen.size != t.links.size) return emptyList()
        return chosen.zip(t.links).map { (f, link) -> RdFile(f.path.substringAfterLast('/'), t.filename, f.bytes, link) }
            .filter { RdNames.isVideo(it.name) && !RdNames.isExtra(it.name) && it.bytes >= MIN_BYTES }
    }

    private suspend fun loadCached() {
        val files = readCache().flatMap { it.files }
        if (files.isNotEmpty()) { _index.value = index(files); refreshState() }
    }

    private suspend fun readCache(): List<CachedTorrent> = withContext(Dispatchers.IO) {
        runCatching { gson.fromJson<List<CachedTorrent>>(libraryFile.readText(), object : TypeToken<List<CachedTorrent>>() {}.type) }.getOrNull().orEmpty()
    }

    // ---- playing ----

    /** The account's copy of a catalog movie or episode, or null. */
    suspend fun copyFor(item: PlayItem): RdCopy? {
        val idx = _index.value
        if (idx.fileCount == 0) return null
        val dao = app.db.catalog()
        return if (item.kind == ProgressKey.MOVIE) {
            idx.movie(item.name, runCatching { dao.movie(item.sourceId, item.itemId)?.year }.getOrNull())
        } else {
            idx.episode(item.name, runCatching { dao.seriesItem(item.sourceId, item.itemId)?.year }.getOrNull(), item.season, item.episode)
        }
    }

    /** A direct, playable address for [copy]. Throws [RealDebridException] with a message to show. */
    suspend fun streamUrl(copy: RdCopy): String {
        val text = call("/unrestrict/link", FormBody.Builder().add("link", copy.file.link).build())
        return gson.fromJson(text, Unrestricted::class.java)?.download ?: throw RealDebridException(0, "Real-Debrid gave no link")
    }

    // ---- API ----

    /** The profile's token from the portal, cached until it runs out; [rejected] asks for a new one. */
    private suspend fun accessToken(rejected: String? = null): String = tokenLock.withLock {
        token?.let { (t, until) -> if (rejected == null && until - System.currentTimeMillis() > 60_000 || rejected != null && rejected != t) return@withLock t }
        try {
            val t = app.portal.realDebridToken(rejected)
            token = t.accessToken to t.expiresAt
            t.accessToken
        } catch (e: PortalException) {
            when (e.code) {
                404 -> if (linked) signedOut(null) else Unit
                410 -> signedOut("Real-Debrid signed this profile out. Connect again to use it.")
            }
            throw RealDebridException(e.code, "not signed in to Real-Debrid")
        }
    }

    /** A call with the profile's token, asking the portal for a fresh one once if Real-Debrid refuses it. */
    private suspend fun call(path: String, form: FormBody? = null): String {
        var access = accessToken()
        var (status, text) = api(path, access, form)
        if (status == 401) {
            access = accessToken(rejected = access)
            val retry = api(path, access, form)
            status = retry.first; text = retry.second
        }
        if (status == 429) { delay(5_000); val retry = api(path, access, form); status = retry.first; text = retry.second }
        if (status !in 200..299) throw RealDebridException(status, errorMessage(status, text))
        return text
    }

    private suspend fun api(path: String, access: String, form: FormBody? = null): Pair<Int, String> =
        send(Request.Builder().url(API + path).header("Authorization", "Bearer $access").apply { if (form != null) post(form) })

    private suspend fun send(b: Request.Builder): Pair<Int, String> = withContext(Dispatchers.IO) {
        Http.client.newCall(b.build()).execute().use { it.code to it.body?.string().orEmpty() }
    }

    private fun errorMessage(status: Int, text: String): String {
        val code = runCatching { gson.fromJson(text, ApiError::class.java)?.errorCode }.getOrNull()
        return when {
            status == 403 && code == 20 -> "Real-Debrid needs a premium account to play this."
            status == 503 || code == 19 || code == 23 -> "Real-Debrid can't reach that file right now."
            code == 24 || status == 404 -> "That file is no longer in your Real-Debrid account."
            else -> "Real-Debrid error $status"
        }
    }

    private data class DeviceCode(
        @SerializedName("device_code") val deviceCode: String,
        @SerializedName("user_code") val userCode: String,
        val interval: Int = 5,
        @SerializedName("expires_in") val expiresIn: Long = 600,
        @SerializedName("verification_url") val verificationUrl: String,
        @SerializedName("direct_verification_url") val directUrl: String? = null,
    )

    private data class Credentials(@SerializedName("client_id") val clientId: String?, @SerializedName("client_secret") val clientSecret: String?)

    private data class Token(
        @SerializedName("access_token") val accessToken: String?,
        @SerializedName("refresh_token") val refreshToken: String?,
        @SerializedName("expires_in") val expiresIn: Long = 3600,
    )

    private data class User(val username: String?, val type: String?, val expiration: String?) {
        fun premiumUntil(): String? = if (type == "premium") expiration?.take(10) else null
    }

    private data class Torrent(val id: String, val filename: String = "", val status: String = "", val links: List<String> = emptyList())
    private data class TorrentFile(val id: Int, val path: String = "", val bytes: Long = 0, val selected: Int = 0)
    private data class TorrentInfo(val files: List<TorrentFile>? = null)
    private data class CachedTorrent(val id: String, val links: List<String>, val files: List<RdFile>)
    private data class Download(val filename: String = "", val filesize: Long = 0, val link: String? = null)
    private data class Unrestricted(val download: String?)
    private data class ApiError(@SerializedName("error_code") val errorCode: Int?)

    private companion object {
        const val API = "https://api.real-debrid.com/rest/1.0"
        const val OAUTH = "https://api.real-debrid.com/oauth/v2"
        /** Real-Debrid's client id for open-source apps; device sign-in turns it into this account's own credentials. */
        const val OPEN_CLIENT = "X245A4XAIBGVM"
        const val GRANT = "http://oauth.net/grant_type/device/1.0"
        const val PAGE = 100
        const val MAX_PAGES = 20
        const val MIN_BYTES = 50_000_000L
        const val REFRESH_EVERY_MS = 6 * 3_600_000L
        const val LINKED = "linked"
        const val NAME = "name"
        const val PREMIUM = "premiumUntil"
        const val LAST_REFRESH = "lastRefresh"
        const val MESSAGE = "message"
        const val PLAY_FIRST = "playFirst"
    }
}
