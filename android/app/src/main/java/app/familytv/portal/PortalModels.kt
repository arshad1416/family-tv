package app.familytv.portal

data class RegisterRequest(val manufacturer: String, val model: String, val sdk: Int, val appVersion: String)
data class RegisterResponse(val deviceId: String, val deviceToken: String, val pairCode: String, val pairExpires: Long)

data class Source(
    val id: String,
    val type: String, // "xtream" | "m3u"
    val name: String,
    val serverUrls: List<String>? = null,
    val username: String? = null,
    val password: String? = null,
    val m3uUrl: String? = null,
    val epgUrl: String? = null,
    /** The provider's page where the subscriber arranges this line's channels; shown in Settings as a QR. */
    val sorterUrl: String? = null,
)

data class Profile(
    val sources: List<Source> = emptyList(),
    val parentalPin: String? = null,
    val hiddenCategories: List<String>? = null,
    /** Category names per kind ("live", "movie", "series") in the order to list them; others follow in the provider's order. */
    val categoryOrder: Map<String, List<String>>? = null,
    /** XMLTV guides (by priority) for channels the provider's own guide leaves thin, such as epgshare01's. */
    val extraGuides: List<String>? = null,
    /** Provider channel name to an extra guide's channel id, for channels the name rules can't link. */
    val guidePins: Map<String, String>? = null,
)

data class Command(val id: String, val type: String, val payload: Map<String, Any?>?)
data class LatestApp(val versionCode: Int, val url: String, val versionName: String? = null)

data class DeviceConfig(
    val paired: Boolean,
    val pairCode: String? = null,
    val pairExpires: Long? = null,
    val nickname: String? = null,
    val profile: Profile? = null,
    val commands: List<Command>? = null,
    val latestApp: LatestApp? = null,
    /** Set when the portal has Trakt credentials; null hides Trakt on the TV. */
    val trakt: TraktPortal? = null,
    /** OpenSubtitles API key from the portal's Worker secret; null hides online subtitles. */
    val subtitlesApiKey: String? = null,
    /** TMDB key (v3 key or v4 read token) from the portal's Worker secret; null skips TMDB trailers and episode details. */
    val tmdbApiKey: String? = null,
    /** TheTVDB v4 key from the portal's Worker secret; null skips the TVDB fallback. */
    val tvdbApiKey: String? = null,
)

/** The Trakt app's public client id. The secret stays in the portal. */
data class TraktPortal(val clientId: String? = null)

/** Trakt's answer to a token call made through the portal: Trakt's HTTP status and JSON body. */
data class TraktProxyResult(val status: Int = 0, val data: com.google.gson.JsonElement? = null)

data class HeartbeatResponse(
    val paired: Boolean,
    val commands: List<Command>?,
    val latestApp: LatestApp? = null,
    /** The profile's latest shared-progress change; past this TV's, another TV marked or paused something. */
    val progressSeq: Long? = null,
)

data class ReachResult(val url: String, val ok: Boolean, val status: Int?, val ms: Long?, val error: String?)
data class ReachResponse(val results: List<ReachResult>)
