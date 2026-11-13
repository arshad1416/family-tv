package app.familytv.ui

import app.familytv.data.OnAir
import app.familytv.scores.ScoreGame

/** The tag beside a channel in the Sports tab: the game is on as it happens, or is a recording. */
enum class AirTag(val label: String) { Live("Live"), Replay("Replay") }

/** Where the Sports tab puts a channel right now, and its tag. */
data class SportSpot(val sport: Sport, val tag: AirTag? = null)

/**
 * Sorting the Sports tab by what each channel is showing now. The guide's programme is read first
 * with the same word lists as channel names (free, on the TV); what they can't settle goes to Jev on
 * the portal ([SportNowLabels]); with neither, the channel keeps its name-based sport ([Sport.of]).
 * Pure, unit tested.
 */
object SportNow {
    /** "Leafs at Canadiens", "Arsenal v Chelsea", "Lakers @ Celtics": a game, whatever else the title says. */
    private val MATCHUP = Regex("""\S\s+(VS?\.?|AT|@)\s+\S""", RegexOption.IGNORE_CASE)

    /** Studio, talk, news and highlights programmes: they go under Shows & Highlights. */
    private val SHOW = Regex(
        """\b(HIGHLIGHTS?|SPORTS ?CENT(RE|ER)|TONIGHT|DAILY|PRE-?GAME|POST-?GAME|PRE-?MATCH|POST-?MATCH|PRE-?RACE|POST-?RACE|""" +
            """MAGAZINE|TALK|REVIEW|PREVIEW|ANALYSIS|INSIDER?|PODCAST|NEWS|RECAP|COUNTDOWN|REPORT|BULLETIN|BREAKDOWN|""" +
            """OFF THE RECORD|THE PANEL|DOCUMENTARY|30 FOR 30)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Guide genres that mean a studio or talk programme rather than an event. */
    private val SHOW_GENRE = Regex("""\b(SPORTS? (TALK|MAGAZINE|NON-EVENT|NEWS)|TALK|MAGAZINE|NEWS|DOCUMENTARY)\b""", RegexOption.IGNORE_CASE)

    /** A genre of just "Football" is the NFL in North American guides and soccer elsewhere: let Jev decide. */
    private val SOCCER_SURE = Regex("""SOCCER|ASSOCIATION|F[UÚ]TBOL|FUTEBOL|CALCIO""", RegexOption.IGNORE_CASE)

    private val LIVE = Regex("""\bLIVE\b|\bEN DIRECT\b|\bEN VIVO\b|\bAO VIVO\b""", RegexOption.IGNORE_CASE)
    private val REPLAY = Regex(
        """\b(REPLAY|ENCORE|CLASSICS?|RE-?AIR|RERUN|REWIND|FROM THE VAULT|DELAYED|TAPE DELAY)\b|\(R\)""",
        RegexOption.IGNORE_CASE,
    )

    /** Titles the guide uses when it knows nothing; not worth asking anyone about. */
    private val NO_INFO = Regex("""^\s*(NO INFORMATION|NO EPG|TO BE ANNOUNCED|TBA|OFF ?AIR|CLOSED)\s*$""", RegexOption.IGNORE_CASE)

    /**
     * What the word lists make of a programme: a sport, [Sport.Shows] for studio programmes, or null
     * when they can't tell (then Jev is asked).
     */
    fun byRules(title: String, genres: String?): Sport? {
        val g = genres.orEmpty()
        val game = MATCHUP.containsMatchIn(title)
        if (!game && (SHOW.containsMatchIn(title) || SHOW_GENRE.containsMatchIn(g))) return Sport.Shows
        Sport.match(title)?.let { return it }
        val fromGenre = Sport.match(g) ?: return null
        return fromGenre.takeUnless { it == Sport.Soccer && !SOCCER_SURE.containsMatchIn(g) }
    }

    /** Whether a programme with this title is worth sending to Jev. */
    fun askable(title: String): Boolean = title.trim().length >= 3 && !NO_INFO.matches(title)

    /** The sport a portal label names: a sport, Shows, or null for NotSport / Unsure / unknown. */
    fun fromLabel(label: String?): Sport? = when (label) {
        null, "NotSport", "Unsure", "More" -> null
        "Show" -> Sport.Shows
        else -> Sport.entries.firstOrNull { it.name == label && it.regex != null }
    }

    /** The same key the portal caches by: squashed lower-case title and genres. */
    fun key(title: String, genres: String?): String =
        "${squash(title)}|${squash(genres)}".take(300)

    private fun squash(s: String?) = s.orEmpty().lowercase().replace(Regex("""\s+"""), " ").trim()

    /** Live or Replay for a game: the guide's own marks, the title's words, then today's scoreboard. */
    fun tag(p: OnAir, game: ScoreGame?): AirTag? = when {
        p.isLive || LIVE.containsMatchIn(p.title) || game?.state == "in" -> AirTag.Live
        p.isRepeat || REPLAY.containsMatchIn(p.title) || game?.state == "post" -> AirTag.Replay
        else -> null
    }

    /**
     * Where a channel goes: the sport of what it's showing ([onAir] by [byRules], else Jev's [label]),
     * else its name-based sport. Null when it isn't sport at all. [tag] is applied only to games.
     */
    fun place(channel: String, category: String, onAir: OnAir?, label: String?, game: ScoreGame? = null): SportSpot? {
        val named = Sport.of(channel, category)
        val now = onAir?.takeIf { askable(it.title) }?.let { byRules(it.title, it.category) ?: fromLabel(label) }
        val sport = now ?: named ?: return null
        val tagged = onAir?.takeIf { sport != Sport.Shows }?.let { tag(it, game) }
        return SportSpot(sport, tagged)
    }
}
