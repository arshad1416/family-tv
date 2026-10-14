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
)

data class Profile(
    val sources: List<Source> = emptyList(),
    val parentalPin: String? = null,
    val hiddenCategories: List<String>? = null,
)

data class Command(val id: String, val type: String, val payload: Map<String, Any?>?)
data class LatestApp(val versionCode: Int, val url: String)

data class DeviceConfig(
    val paired: Boolean,
    val pairCode: String? = null,
    val pairExpires: Long? = null,
    val nickname: String? = null,
    val profile: Profile? = null,
    val commands: List<Command>? = null,
    val latestApp: LatestApp? = null,
)

data class HeartbeatResponse(val paired: Boolean, val commands: List<Command>?)

data class ReachResult(val url: String, val ok: Boolean, val status: Int?, val ms: Long?, val error: String?)
data class ReachResponse(val results: List<ReachResult>)
