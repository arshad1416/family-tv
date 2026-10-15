package app.familytv.trakt.model

import com.google.gson.annotations.SerializedName

// Trakt API shapes (https://trakt.docs.apiary.io). Every field has a default so Gson builds
// them through the no-arg constructor and missing fields never become nulls in non-null types.

data class TIds(
    val trakt: Long? = null,
    val slug: String? = null,
    val imdb: String? = null,
    val tmdb: Long? = null,
)

data class TMedia(val title: String? = null, val year: Int? = null, val ids: TIds? = null)

data class TEpisode(val season: Int? = null, val number: Int? = null)

/** sync/watchlist/movies|shows */
data class TListItem(val type: String? = null, val movie: TMedia? = null, val show: TMedia? = null)

/** sync/watched/movies */
data class TWatchedMovie(
    @SerializedName("last_watched_at") val lastWatchedAt: String? = null,
    val movie: TMedia? = null,
)

/** sync/watched/shows */
data class TWatchedShow(val show: TMedia? = null, val seasons: List<TWatchedSeason>? = null)
data class TWatchedSeason(val number: Int = 0, val episodes: List<TWatchedEpisode>? = null)
data class TWatchedEpisode(
    val number: Int = 0,
    @SerializedName("last_watched_at") val lastWatchedAt: String? = null,
)

/** sync/playback: resume points. */
data class TPlayback(
    val progress: Double? = null,
    @SerializedName("paused_at") val pausedAt: String? = null,
    val type: String? = null,
    val movie: TMedia? = null,
    val episode: TEpisode? = null,
    val show: TMedia? = null,
)

data class TSearchHit(val type: String? = null, val score: Double? = null, val movie: TMedia? = null, val show: TMedia? = null)

data class TUser(val username: String? = null, val name: String? = null)
data class TSettings(val user: TUser? = null)

data class TDeviceCode(
    @SerializedName("device_code") val deviceCode: String = "",
    @SerializedName("user_code") val userCode: String = "",
    @SerializedName("verification_url") val verificationUrl: String = "https://trakt.tv/activate",
    @SerializedName("expires_in") val expiresIn: Long = 600,
    val interval: Long = 5,
)

data class TToken(
    @SerializedName("access_token") val accessToken: String = "",
    @SerializedName("refresh_token") val refreshToken: String = "",
    @SerializedName("expires_in") val expiresIn: Long = 0,
    @SerializedName("created_at") val createdAt: Long = 0,
)
