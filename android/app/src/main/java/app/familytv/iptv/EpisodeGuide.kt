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

    /** Every regular episode from the start up to and including [last] (specials only when [last] is one). */
    fun upTo(episodes: List<XtreamParser.Episode>, last: XtreamParser.Episode): List<XtreamParser.Episode> =
        if (last.season == 0) episodes.filter { it.season == 0 && it.episode <= last.episode }
        else episodes.filter { it.season in 1 until last.season || (it.season == last.season && it.episode <= last.episode) }

    /** The newest regular episode as (season, episode), or null when there are none. Specials don't count. */
    fun latest(episodes: List<XtreamParser.Episode>): Pair<Int, Int>? =
        episodes.filter { it.season > 0 }.maxWithOrNull(compareBy({ it.season }, { it.episode }))?.let { it.season to it.episode }

    /** How many regular episodes come after (season, episode) [since]. */
    fun countAfter(episodes: List<XtreamParser.Episode>, since: Pair<Int, Int>): Int = after(episodes, since).size

    /**
     * The episodes of a followed show waiting to be watched, oldest first: those already [listed],
     * those that arrived after [since] (the newest one at the last check; null on a first look),
     * and every one after the furthest the family has watched. None of them [watched], and only
     * ones the provider still has. A show nobody has started counts only arrivals.
     */
    fun waiting(
        episodes: List<XtreamParser.Episode>,
        listed: List<Pair<Int, Int>>,
        since: Pair<Int, Int>?,
        watched: Set<Pair<Int, Int>>,
    ): List<Pair<Int, Int>> {
        val exists = episodes.filter { it.season > 0 }.mapTo(HashSet()) { it.season to it.episode }
        val furthest = watched.filter { it in exists }.maxWithOrNull(compareBy({ it.first }, { it.second }))
        val behind = furthest?.let { after(episodes, it) }.orEmpty()
        val arrived = since?.let { after(episodes, it) }.orEmpty()
        return (listed + arrived + behind).distinct().filter { it in exists && it !in watched }
            .sortedWith(compareBy({ it.first }, { it.second }))
    }

    /** The regular episodes after (season, episode) [since], as (season, episode), oldest first. */
    fun after(episodes: List<XtreamParser.Episode>, since: Pair<Int, Int>): List<Pair<Int, Int>> =
        episodes.filter { it.season > 0 && (it.season > since.first || (it.season == since.first && it.episode > since.second)) }
            .map { it.season to it.episode }.distinct().sortedWith(compareBy({ it.first }, { it.second }))

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
