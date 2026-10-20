package app.familytv.ui

/**
 * The player's sleep timer (backlog item 21): a [choice] made in the Options panel and the moment
 * [at] it runs out, when playback stops and the player closes. Pure, unit tested.
 */
data class SleepTimer(val choice: Choice, val at: Long) {
    enum class Choice(val label: String, val minutes: Int) {
        M15("15 minutes", 15),
        M30("30 minutes", 30),
        M60("1 hour", 60),
        M90("1 hour 30 minutes", 90),
        /** When the programme on now ends (live), or the video does. */
        END("End of programme", 0),
    }

    companion object {
        /** A minute before it runs out, the player says so, so a viewer still awake can turn it off. */
        const val WARN_MS = 60_000L

        /**
         * When [c], chosen at [now], runs out. [end] is when the programme or video ends; END with
         * no end in the future (no guide, or a stream of unknown length) has none, so null.
         */
        fun at(c: Choice, now: Long, end: Long?): Long? =
            if (c == Choice.END) end?.takeIf { it > now } else now + c.minutes * 60_000L

        /** A video's end from where it is, so pausing pushes it back; null while its length is unknown. */
        fun videoEnd(now: Long, positionMs: Long, durationMs: Long): Long? =
            if (durationMs <= 0) null else now + (durationMs - positionMs).coerceAtLeast(0)
    }
}

/** How far one Left or Right moves the seek bar: 10 s a press, 30 s once held, then a minute (backlog item 24). */
object SeekSteps {
    fun stepMs(repeatCount: Int): Long = when {
        repeatCount < 6 -> 10_000L
        repeatCount < 16 -> 30_000L
        else -> 60_000L
    }
}
