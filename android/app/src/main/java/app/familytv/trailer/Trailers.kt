package app.familytv.trailer

import java.util.Locale

/** Picking and reading YouTube trailers. Pure, unit tested in TrailersTest. */
object Trailers {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val IN_URL = Regex("(?:[?&]v=|[?&]videoid=|youtu\\.be/|/embed/|/shorts/|/v/|/live/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])")

    /**
     * The YouTube video id in a provider field: a bare id ("dQw4w9WgXcQ"), a watch/youtu.be/embed
     * URL, or a Kodi plugin URL (…plugin.video.youtube/?action=play_video&videoid=…). Null otherwise.
     */
    fun youtubeId(v: String?): String? {
        val s = v?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        if (ID.matches(s)) return s
        return IN_URL.find(s)?.groupValues?.get(1)
    }

    /** One entry of TMDB's /videos list. */
    data class Video(
        val key: String,
        val site: String?,
        val type: String?,
        val official: Boolean = false,
        /** ISO 639-1, e.g. "en". */
        val language: String? = null,
    )

    /**
     * The best YouTube trailer: in the most preferred of [languages] (any other language after,
     * in TMDB's order), then a Trailer before a Teaser, then official before fan uploads.
     * Clips, featurettes and other sites are never picked.
     */
    fun pick(videos: List<Video>, languages: List<String>): String? {
        val prefs = languages.map { it.lowercase(Locale.ROOT).substringBefore('-') }.distinct()
        fun langRank(v: Video) = prefs.indexOf(v.language?.lowercase(Locale.ROOT)).let { if (it < 0) prefs.size else it }
        fun typeRank(v: Video) = if (v.type.equals("Trailer", true)) 0 else 1
        return videos
            .filter { it.site.equals("YouTube", true) && (it.type.equals("Trailer", true) || it.type.equals("Teaser", true)) && ID.matches(it.key) }
            .sortedWith(compareBy({ langRank(it) }, { typeRank(it) }, { if (it.official) 0 else 1 }))
            .firstOrNull()?.key
    }

    fun appUri(id: String) = "vnd.youtube:$id"
    fun webUrl(id: String) = "https://www.youtube.com/watch?v=$id"
}
