package app.familytv.scores

import app.familytv.portal.PortalClient
import app.familytv.ui.Sport
import java.text.Normalizer

/** One side of a game, as the portal's /api/device/scores gives it. */
data class ScoreTeam(val name: String = "", val short: String = "", val full: String = "", val abbr: String = "", val score: String = "")

/** A game today: [state] is pre, in or post; [detail] is ESPN's short line ("2nd - 12:34", "Final"). */
data class ScoreGame(val state: String = "", val detail: String = "", val home: ScoreTeam? = null, val away: ScoreTeam? = null)

data class ScoreBoard(val league: String = "", val games: List<ScoreGame>? = null)

/**
 * Which game a live programme is (backlog item 4), for the info bar's score line. Pure, unit tested.
 * A game counts only when both teams are named in the programme's title and no other game fits
 * too: nothing is shown when unsure.
 */
object ScoreMatch {
    /** The leagues worth asking about for a channel of [sport]; none for anything else, so other channels never ask. */
    fun leagues(sport: Sport?): List<String> = when (sport) {
        Sport.Hockey -> listOf("nhl")
        Sport.Basketball -> listOf("nba", "wnba")
        Sport.AmericanFootball -> listOf("nfl")
        Sport.Baseball -> listOf("mlb")
        Sport.Soccer -> listOf("epl", "mls", "ucl", "laliga")
        else -> emptyList()
    }

    /** The one game in [games] whose two teams [title] names, else null. */
    fun find(title: String, games: List<ScoreGame>): ScoreGame? {
        val words = " ${plain(title)} "
        val fits = games.filter { g -> named(words, g.home) && named(words, g.away) }
        return fits.singleOrNull()
    }

    /** "Maple Leafs 2 – 1 Canadiens · 2nd - 12:34", away side first; null before the game starts. */
    fun line(g: ScoreGame): String? {
        val home = g.home ?: return null
        val away = g.away ?: return null
        if (g.state != "in" && g.state != "post") return null
        val score = "${away.short.ifBlank { away.name }} ${away.score} – ${home.score} ${home.short.ifBlank { home.name }}"
        return if (g.detail.isBlank()) score else "$score · ${g.detail}"
    }

    private fun named(words: String, t: ScoreTeam?): Boolean =
        t != null && listOf(t.name, t.short, t.full).map(::plain).any { it.length >= 3 && " $it " in words }

    /** Upper case, accents off, punctuation as spaces: "Montréal" and "MONTREAL" match. */
    private fun plain(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").uppercase().replace(NON_WORD, " ").trim().replace(SPACES, " ")

    private val MARKS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex(" +")
}

/** The portal's scoreboards, kept a minute per league like the portal does, so zapping makes no extra calls. */
class LiveScores(private val portal: PortalClient, private val clock: () -> Long = System::currentTimeMillis) {
    private val boards = HashMap<String, Pair<Long, List<ScoreGame>>>()

    /** The score line for a programme called [title] on a channel of [sport], or null. */
    suspend fun lineFor(title: String, sport: Sport?): String? {
        for (league in ScoreMatch.leagues(sport)) {
            ScoreMatch.find(title, games(league))?.let { return ScoreMatch.line(it) }
        }
        return null
    }

    private suspend fun games(league: String): List<ScoreGame> {
        val now = clock()
        boards[league]?.takeIf { now - it.first < CACHE_MS }?.let { return it.second }
        // A failed call (or a portal without scores yet) counts as no games for the minute.
        val games = runCatching { portal.scores(league).games.orEmpty() }.getOrDefault(emptyList())
        boards[league] = now to games
        return games
    }

    private companion object {
        const val CACHE_MS = 60_000L
    }
}
