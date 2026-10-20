package app.familytv.ui

import androidx.compose.ui.graphics.Color

/**
 * The badge on a Sports channel's preview (backlog item 22): what the programme on now says it is.
 * Event channels keep a game's name and art long after it ends, or show the next one early, so a
 * title like "ENDED: …", "Replay" or "Coming up" decides; any other programme on now is LIVE.
 * Pure, unit tested.
 */
enum class EventStatus(val label: String, val color: Color) {
    Live("LIVE", Glass.Live),
    Replay("REPLAY", Color(0xFFB7791F)),
    Upcoming("UPCOMING", Color(0xFF2563EB)),
    Ended("ENDED", Color(0xFF4B5563)),
    ;

    companion object {
        private val ENDED = Regex("""(?i)\b(ended|full[ -]time|final score)\b|\bFT\b""")
        private val REPLAY = Regex("""(?i)\b(replay|re-?run|encore|highlights)\b|\(R\)""")
        private val UPCOMING = Regex("""(?i)\b(upcoming|coming up|starts? at|starting at|kick-?offs? at)\b""")

        /** The badge for [title], the programme on now; none when nothing is on. */
        fun of(title: String?): EventStatus? = when {
            title == null -> null
            ENDED.containsMatchIn(title) -> Ended
            REPLAY.containsMatchIn(title) -> Replay
            UPCOMING.containsMatchIn(title) -> Upcoming
            else -> Live
        }
    }
}
