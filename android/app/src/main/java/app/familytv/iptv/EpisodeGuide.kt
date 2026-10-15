package app.familytv.iptv

/** How a series page lays out its seasons and episodes. Pure, unit tested in SeriesTitlesTest. */
object EpisodeGuide {
    /** Seasons in order, with specials (season 0) last. */
    fun seasons(episodes: List<XtreamParser.Episode>): List<Int> =
        episodes.map { it.season }.distinct().sortedWith(compareBy({ it == 0 }, { it }))

    fun seasonLabel(season: Int) = if (season == 0) "Specials" else "Season $season"

    /** The season to open on: the one last watched in (by [lastPlayed]), else the first. */
    fun defaultSeason(seasons: List<Int>, lastPlayed: Int?): Int? =
        lastPlayed?.takeIf { it in seasons } ?: seasons.firstOrNull()

    /**
     * What "Play" starts: the episode in progress, else the one after the last watched one,
     * else the first. [watched] and [inProgress] look up the family's progress for an episode.
     */
    fun nextUp(
        episodes: List<XtreamParser.Episode>,
        watched: (XtreamParser.Episode) -> Boolean,
        inProgress: (XtreamParser.Episode) -> Boolean,
    ): XtreamParser.Episode? {
        val ordered = episodes.sortedWith(compareBy({ it.season == 0 }, { it.season }, { it.episode }))
        ordered.firstOrNull(inProgress)?.let { return it }
        val last = ordered.indexOfLast(watched)
        return ordered.getOrNull(last + 1) ?: ordered.firstOrNull()
    }

    private val SXE = Regex("(?i)^.*?\\bS\\d{1,3}\\s*E\\d{1,4}\\b\\s*[-:–|.]*\\s*")

    /** "Show - S01E03 - The Swarm" becomes "The Swarm"; an empty title becomes "Episode 3". */
    fun title(e: XtreamParser.Episode): String {
        val cleaned = e.title.replace(SXE, "").trim()
        return cleaned.ifEmpty { "Episode ${e.episode}" }
    }

    /** "00:42:10", "42:10" or "42" (minutes) to seconds. */
    fun seconds(duration: String?): Int? {
        val parts = duration?.trim()?.takeIf { it.isNotEmpty() }?.split(':')?.map { it.trim().toIntOrNull() ?: return null } ?: return null
        val secs = when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0] * 60
            else -> return null
        }
        return secs.takeIf { it > 0 }
    }

    /** "42 min" or "1 h 5 min". */
    fun runtime(secs: Int?): String? {
        val m = ((secs ?: return null) + 30) / 60
        if (m <= 0) return null
        return if (m < 60) "$m min" else if (m % 60 == 0) "${m / 60} h" else "${m / 60} h ${m % 60} min"
    }
}
