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
    /** Startup video: "the-nook" on the Airbnb guest profile; unset plays Family TV's. */
    val splash: String? = null,
    /** Open the app by itself when the TV starts or wakes (the Airbnb guest TV); see [app.familytv.start.OpenOnStart]. */
    val openOnStart: Boolean? = null,
    /** Send anonymous feature use (screens and buttons, never what's watched or searched); see [app.familytv.usage.Usage]. */
    val usageStats: Boolean? = null,
    /** The people who watch on this profile's TVs, picked from the top bar; see [app.familytv.user.Viewers]. */
    val viewers: List<Viewer>? = null,
    /** Set on this TV only (never from the portal) while a kids viewer watches: kids' categories only, no adult ones. */
    @Transient val kidsOnly: Boolean = false,
)

/** A person on the profile. A [kids] viewer sees kids' categories only, and leaving them needs the parental PIN. */
data class Viewer(val id: String = "", val name: String = "", val kids: Boolean? = null)

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
    /** Paid add-ons for this TV's profile; see [app.familytv.addons.Addons]. Missing means none. */
    val addons: List<AddonInfo>? = null,
    /** When the profile's membership ends (ms); null means no end. Past it, owned add-ons pause. */
    val membershipUntil: Long? = null,
)

/** An add-on as the portal reports it: state is active, paused, available or not_offered. */
data class AddonInfo(val id: String? = null, val name: String? = null, val state: String? = null)

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
