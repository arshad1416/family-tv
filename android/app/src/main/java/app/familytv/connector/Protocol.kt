package app.familytv.connector

import android.os.Bundle
import androidx.core.os.BundleCompat

/**
 * The external source connector's wire format, protocol 1 (docs/CONNECTOR.md). A companion app
 * lists sources for a title and resolves the one picked; everything crosses the binder as plain
 * Bundles of strings, ints, longs and nested Bundles, so the Rust host's Java shell and a
 * companion written without this code read the same keys. Unknown keys are ignored both ways.
 * The host enforces every cap below on what it receives, whatever the provider sent.
 */
object Protocol {
    const val VERSION = 1
    const val ACTION = "app.familytv.connector.SOURCE_PROVIDER_V1"
    /** Service meta-data: the protocol versions the provider speaks (ints, both inclusive). */
    const val META_MIN = "app.familytv.connector.PROTOCOL_MIN"
    const val META_MAX = "app.familytv.connector.PROTOCOL_MAX"

    const val MAX_SOURCES = 100
    const val MAX_ID = 128
    const val MAX_TITLE = 200
    const val MAX_QUALITY = 20
    const val MAX_AUDIO = 60
    const val MAX_LANGUAGE = 35
    const val MAX_PROVIDER_NAME = 60
    const val MAX_URI = 4096
    const val MAX_MIME = 100
    const val MAX_HEADERS = 10
    const val MAX_HEADER_NAME = 64
    const val MAX_HEADER_VALUE = 1024
    const val MAX_CAPABILITIES = 32
    const val MAX_CAPABILITY = 64

    /** Session, request and selection ids: opaque, 1 to [MAX_ID] of these characters. */
    private val ID = Regex("[A-Za-z0-9._~:-]{1,$MAX_ID}")
    private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,$MAX_HEADER_NAME}")

    fun isId(s: String?): Boolean = s != null && ID.matches(s)

    /** Trimmed, without control characters, at most [max] characters; null when nothing is left. */
    internal fun text(s: String?, max: Int): String? =
        s?.filterNot { it.isISOControl() }?.trim()?.take(max)?.takeIf { it.isNotEmpty() }

    /** What the host offers: [VERSION] only, and no optional capabilities yet. */
    fun hello(hostVersion: String, capabilities: List<String> = emptyList()) = Bundle().apply {
        putInt("protocolMin", VERSION)
        putInt("protocolMax", VERSION)
        putString("hostVersion", text(hostVersion, MAX_PROVIDER_NAME))
        putStringArrayList("capabilities", ArrayList(capabilities.take(MAX_CAPABILITIES)))
    }

    /** The provider's answer to [hello]: null unless it picked a version the host offered. */
    fun negotiated(b: Bundle): Negotiated? {
        val protocol = b.getInt("protocol", 0)
        if (protocol != VERSION) return null
        val caps = b.getStringArrayList("capabilities").orEmpty()
            .mapNotNull { text(it, MAX_CAPABILITY) }.distinct().take(MAX_CAPABILITIES)
        return Negotiated(protocol, caps, text(b.getString("providerName"), MAX_PROVIDER_NAME))
    }

    /** The provider's sources: invalid entries are dropped, text capped, at most [MAX_SOURCES]. */
    fun sources(b: Bundle): List<Source> =
        BundleCompat.getParcelableArrayList(b, "sources", Bundle::class.java).orEmpty().asSequence().mapNotNull { s ->
            val id = s.getString("selectionId")?.takeIf(::isId) ?: return@mapNotNull null
            Source(
                selectionId = id,
                title = text(s.getString("title"), MAX_TITLE) ?: return@mapNotNull null,
                quality = text(s.getString("quality"), MAX_QUALITY),
                sizeBytes = s.getLong("sizeBytes", -1).takeIf { it >= 0 },
                audio = text(s.getString("audio"), MAX_AUDIO),
                language = text(s.getString("language"), MAX_LANGUAGE),
            )
        }.distinctBy { it.selectionId }.take(MAX_SOURCES).toList()

    /** The provider's playback handoff: null unless the uri is http(s) and within the caps. */
    fun resolved(b: Bundle): ResolvedSource? {
        val uri = b.getString("uri")?.trim()?.takeIf { it.length <= MAX_URI } ?: return null
        val scheme = uri.substringBefore(':', "").lowercase()
        if (scheme != "https" && scheme != "http") return null
        if (!uri.startsWith("$scheme://", ignoreCase = true) || uri.any { it.isWhitespace() || it.isISOControl() }) return null
        val headers = BundleCompat.getParcelable(b, "headers", Bundle::class.java)
        val pairs = headers?.keySet().orEmpty().sorted().mapNotNull { k ->
            val v = headers?.getString(k) ?: return@mapNotNull null
            if (!TOKEN.matches(k) || v.length > MAX_HEADER_VALUE || v.any { it == '\r' || it == '\n' }) null else k to v
        }.take(MAX_HEADERS).toMap()
        return ResolvedSource(
            uri = uri,
            mimeType = text(b.getString("mimeType"), MAX_MIME),
            headers = pairs,
            expiresAtMs = b.getLong("expiresAtMs", 0).takeIf { it > 0 },
        )
    }
}

data class Negotiated(val protocol: Int, val capabilities: List<String>, val providerName: String?)

/** A movie or an episode the host has already matched; at least one of the ids is set. */
data class MediaIdentity(
    val type: Type,
    val tmdb: String? = null,
    val imdb: String? = null,
    val tvdb: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    enum class Type(val wire: String) { MOVIE("movie"), EPISODE("episode") }

    /** Ids in their wire shape (tmdb/tvdb digits, imdb "tt" and digits), and season/episode only for an episode. */
    val isValid: Boolean
        get() {
            val ids = listOf(tmdb, imdb, tvdb)
            if (ids.all { it == null }) return false
            if (tmdb != null && !NUMERIC.matches(tmdb) || tvdb != null && !NUMERIC.matches(tvdb) || imdb != null && !IMDB.matches(imdb)) return false
            return when (type) {
                Type.MOVIE -> season == null && episode == null
                Type.EPISODE -> season != null && season >= 0 && episode != null && episode >= 1
            }
        }

    fun toBundle() = Bundle().apply {
        putString("type", type.wire)
        tmdb?.let { putString("tmdb", it) }
        imdb?.let { putString("imdb", it) }
        tvdb?.let { putString("tvdb", it) }
        season?.let { putInt("season", it) }
        episode?.let { putInt("episode", it) }
    }

    companion object {
        private val IMDB = Regex("tt\\d{5,10}")
        private val NUMERIC = Regex("\\d{1,12}")

        /** What a provider reads back; null when the Bundle isn't a valid identity. */
        fun from(b: Bundle): MediaIdentity? {
            val type = Type.entries.firstOrNull { it.wire == b.getString("type") } ?: return null
            return MediaIdentity(
                type,
                b.getString("tmdb"),
                b.getString("imdb"),
                b.getString("tvdb"),
                if (b.containsKey("season")) b.getInt("season") else null,
                if (b.containsKey("episode")) b.getInt("episode") else null,
            ).takeIf { it.isValid }
        }
    }
}

/** One release the provider offers. [selectionId] is opaque and only means something to that provider, in that session. */
data class Source(
    val selectionId: String,
    val title: String,
    val quality: String? = null,
    val sizeBytes: Long? = null,
    val audio: String? = null,
    val language: String? = null,
) {
    fun toBundle() = Bundle().apply {
        putString("selectionId", selectionId)
        putString("title", title)
        quality?.let { putString("quality", it) }
        sizeBytes?.let { putLong("sizeBytes", it) }
        audio?.let { putString("audio", it) }
        language?.let { putString("language", it) }
    }

    override fun toString() = "Source($title, $quality)"
}

/** What the player gets. Never logged: [toString] leaves the uri and headers out. */
class ResolvedSource(val uri: String, val mimeType: String?, val headers: Map<String, String>, val expiresAtMs: Long?) {
    fun toBundle() = Bundle().apply {
        putString("uri", uri)
        mimeType?.let { putString("mimeType", it) }
        putBundle("headers", Bundle().apply { headers.forEach { (k, v) -> putString(k, v) } })
        expiresAtMs?.let { putLong("expiresAtMs", it) }
    }

    override fun toString() = "ResolvedSource(${mimeType ?: "?"}, ${headers.size} headers)"
}

/** The typed errors on the wire; any other code reads as [PROVIDER_ERROR]. */
enum class ConnectorError(val code: Int) {
    UNSUPPORTED_VERSION(1),
    NOT_ENTITLED(2),
    NOT_FOUND(3),
    TIMEOUT(4),
    PROVIDER_ERROR(5),
    CANCELLED(6);

    companion object {
        fun of(code: Int): ConnectorError = entries.firstOrNull { it.code == code } ?: PROVIDER_ERROR
    }
}

/** A call's outcome, as the host's screens see it. */
sealed interface Reply<out T> {
    data class Ok<T>(val value: T) : Reply<T>
    data class Failed(val error: ConnectorError) : Reply<Nothing>
}
