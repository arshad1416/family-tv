package app.familytv.discovery

import app.familytv.core.Http
import com.google.gson.JsonParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.FormBody
import okhttp3.Request

/** Secret-bearing response exists only in the current request/playback; never config or telemetry. */
data class ProfileRdKey(val profileId: String, val revision: Long, val configured: Boolean, val apiKey: String? = null) {
    override fun toString() = "ProfileRdKey(profileId=$profileId, revision=$revision, configured=$configured, apiKey=redacted)"
}
data class ProfileRdStatus(val profileId: String, val revision: Long, val configured: Boolean, val maskedPreview: String? = null)

interface ProfileRdGateway {
    suspend fun rdKeyRead(profileId: String): ProfileRdKey
    suspend fun rdKeyWrite(profileId: String, revision: Long, apiKey: String): ProfileRdKey
    suspend fun rdKeyRemove(profileId: String, revision: Long): ProfileRdKey
}

/** API key synchronized only to the owner's authenticated profile devices. No local key persistence. */
class ProfileRealDebrid internal constructor(
    private val gateway: ProfileRdGateway,
    private val http: DiscoveryHttp,
    private val apiBase: String = "https://api.real-debrid.com/rest/1.0",
) {
    constructor(gateway: ProfileRdGateway) : this(gateway, DiscoveryHttp(Http.client))
    @Volatile private var cooldownUntil = 0L

    suspend fun status(session: DiscoverySession): ProfileRdStatus {
        val key = read(session)
        return ProfileRdStatus(key.profileId, key.revision, key.configured, key.apiKey?.let(::maskedPreview))
    }

    suspend fun replace(session: DiscoverySession, expected: ProfileRdStatus, apiKey: String): ProfileRdStatus {
        if (!validKey(apiKey)) throw SourceUnavailable("Enter a 16–512 character Real-Debrid API key without spaces.")
        val nextRevision = nextRevision(session, expected)
        val before = read(session)
        if (before.profileId != expected.profileId || before.revision != expected.revision) throw SourceUnavailable("The profile key changed on another device. Reload the account status and enter the key again.")
        val result = gateway.rdKeyWrite(session.profileId, expected.revision, apiKey)
        session.verify()
        if (result.profileId != session.profileId || result.revision != nextRevision || !result.configured) throw SourceUnavailable("Could not verify the saved profile key. Reload account status.")
        return ProfileRdStatus(result.profileId, result.revision, result.configured, maskedPreview(apiKey))
    }

    suspend fun remove(session: DiscoverySession, expected: ProfileRdStatus): ProfileRdStatus {
        val nextRevision = nextRevision(session, expected)
        val before = read(session)
        if (before.profileId != expected.profileId || before.revision != expected.revision) throw SourceUnavailable("The profile key changed on another device. Reload account status before removing it.")
        val result = gateway.rdKeyRemove(session.profileId, expected.revision)
        session.verify()
        if (result.profileId != session.profileId || result.revision != nextRevision || result.configured) throw SourceUnavailable("Could not verify removal. Reload account status.")
        return ProfileRdStatus(result.profileId, result.revision, result.configured)
    }

    private fun nextRevision(session: DiscoverySession, expected: ProfileRdStatus): Long {
        if (expected.profileId != session.profileId || expected.revision < 0 || expected.revision == Long.MAX_VALUE) {
            throw SourceUnavailable("The profile key state is invalid. Reload account status before changing it.")
        }
        return expected.revision + 1
    }

    private suspend fun read(session: DiscoverySession): ProfileRdKey {
        session.verify()
        val current = gateway.rdKeyRead(session.profileId)
        session.verify()
        validate(session, current)
        return current
    }

    private fun validate(session: DiscoverySession, current: ProfileRdKey) {
        if (current.profileId != session.profileId || current.revision < 0 || current.configured != (current.apiKey != null) || current.apiKey?.let { !validKey(it) } == true) throw SourceUnavailable("The portal returned an invalid account state.")
    }

    /** Verify revocation/replacement at every network boundary and immediately before launch. */
    suspend fun prepare(session: DiscoverySession, source: SourceCandidate, target: DiscoveryTarget, status: (String) -> Unit, progress: (PreparationProgress) -> Unit = {}, beforeOpen: suspend () -> Unit = {}, open: (String) -> Unit) {
        val account = read(session)
        val token = account.apiKey ?: throw SourceUnavailable("Enter the account owner’s Real-Debrid API key in Settings first.")
        suspend fun verify() {
            currentCoroutineContext().ensureActive()
            session.verify()
            // This authenticated GET checks current device/profile membership. Recheck the
            // session after it returns because the read itself may suspend during a switch.
            val now = gateway.rdKeyRead(session.profileId)
            validate(session, now)
            if (now.revision != account.revision || now.apiKey != token) throw SourceUnavailable("The profile’s Real-Debrid key was replaced or removed. Choose a source again.")
            session.verify()
            currentCoroutineContext().ensureActive()
        }
        val url = RdSourceResolver({ path, form ->
            verify()
            if (System.currentTimeMillis() < cooldownUntil) throw SourceUnavailable("Real-Debrid is busy. Wait a minute before trying again.")
            val req = Request.Builder().url(apiBase + path).header("Authorization", "Bearer $token").apply {
                if (form != null) post(FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build())
            }.build()
            val (code, text) = http.request(req, "Real-Debrid")
            if (code == 429) cooldownUntil = System.currentTimeMillis() + 60000
            if (code !in 200..299) throw SourceUnavailable((when (code) {
                401 -> "The profile’s Real-Debrid API key is invalid. Replace it in Settings."
                403 -> "Real-Debrid denied this request (HTTP 403). Check account access or premium status."
                451 -> {
                    val stage = when {
                        path == "/torrents?limit=20" -> "checking existing account sources"
                        path == "/torrents/addMagnet" -> "adding the selected torrent"
                        path.startsWith("/torrents/info/") -> "checking torrent status"
                        path.startsWith("/torrents/selectFiles/") -> "selecting the video file"
                        else -> "resolving the video link"
                    }
                    "The Real-Debrid request was blocked (HTTP 451: unavailable for legal reasons). Check the provider’s notice or contact Real-Debrid support. No automatic retry. Stage: $stage."
                }
                429 -> "Real-Debrid is busy. Wait a minute before trying again."
                else -> "Real-Debrid could not prepare this source (HTTP $code). Try another source."
            }) + providerReason(text)?.let { " $it" }.orEmpty())
            if (path == "/torrents?limit=20" && code == 204 && text.isEmpty()) "[]" else text
        }, ::verify).prepare(source, target, progress, status, beforeReturn = {
            beforeOpen()
            verify()
            currentCoroutineContext().ensureActive()
        })
        currentCoroutineContext().ensureActive()
        open(url)
    }

    companion object {
        /** Only fixed reasons for documented numeric codes; never reflect provider strings. */
        private fun providerReason(body: String): String? {
            if (body.length > 4096) return null
            val code = runCatching {
                val root = JsonParser.parseString(body)
                if (!root.isJsonObject) return null
                val value = root.asJsonObject.get("error_code") ?: return null
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
                value.asString
            }.getOrNull() ?: return null
            val reason = when (code) {
                "22" -> "IP address not allowed"
                "28" -> "File not allowed"
                "35" -> "Infringing file"
                "37" -> "Disabled endpoint"
                else -> return null
            }
            return "Provider code $code: $reason."
        }

        internal fun maskedPreview(key: String): String? = if (validKey(key)) key.take(4) + "********" + key.takeLast(4) else null
        fun validKey(key: String) = key.length in 16..512 && key.all { it.code in 33..126 }
    }
}
