package app.familytv.usage

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import app.familytv.BuildConfig
import app.familytv.portal.PortalClient

/**
 * Anonymous usage stats, for profiles with "Usage stats (anonymous)" on in the portal: which screens
 * and buttons get used, so the app can be improved from how people really use it. Never what was
 * watched or searched: events carry only the fixed names below and numbers, no channel, title,
 * category, account or search text. The names match the Rust app's (parity checklist, "Spec:
 * Anonymous usage stats") so both apps' rows line up; the portal drops any other name.
 */
object Usage {
    enum class Screen { HOME, LIVE, GUIDE, MOVIES, SERIES, SERIES_PAGE, CATEGORIES, SPORTS, SEARCH, SETTINGS, PLAYER, PLAYER_MENU, PAIRING, UPDATE }

    enum class Action {
        OPEN, LEAVE, BACK, SELECT, HOLD_OK, FAVOURITE_ADD, FAVOURITE_REMOVE, MARK_WATCHED, ZAP_UP, ZAP_DOWN,
        PLAY_START, PLAY_FAIL, PLAY_SWITCH_COPY, SEARCH_SUBMIT, SEARCH_PICK, SEARCH_EMPTY, SETTING_CHANGE,
        UPDATE_INSTALL, EXIT_PROMPT, EXIT, ERROR,
    }

    /** The only words a value may be besides a number: setting keys, player panels, what started playing, error kinds, a server switch. */
    enum class Word {
        STREAM_QUALITY_LIVE, STREAM_QUALITY_MOVIES, STREAM_QUALITY_SERIES, PICTURE_SIZE, AUDIO_TRACK, SUBTITLES,
        RUN_CHECK, RELOAD_CHANNELS, PARENTAL_UNLOCK, CATEGORY_ORDER, TRAKT, OPEN_ON_START,
        INFO, MENU,
        LIVE, MOVIE, EPISODE,
        NETWORK, DECODER, TIMEOUT, SOURCE, OTHER,
        SERVER_SWITCH,
    }

    /** As the portal takes it: [value] is a number, one [Word] in lower case, or null. */
    data class Event(val at: Long, val screen: String, val action: String, val value: Any?)

    const val BATCH = 500
    private const val MAX_QUEUED = 2_000

    /** From the profile's setting; off empties the queue and stops collecting. */
    @Volatile var enabled = false
        set(on) {
            field = on
            if (!on) synchronized(queue) { queue.clear() }
        }

    private val queue = ArrayDeque<Event>()
    // Screens open now, innermost last; actions with no screen of their own belong to the top one.
    private val open = ArrayList<Screen>()

    val current: Screen get() = synchronized(open) { open.lastOrNull() ?: Screen.HOME }

    fun log(action: Action, value: Number? = null) = add(current, action, value)
    fun log(action: Action, word: Word) = add(current, action, word.wire)
    fun log(screen: Screen, action: Action, value: Number? = null) = add(screen, action, value)
    fun log(screen: Screen, action: Action, word: Word) = add(screen, action, word.wire)

    fun opened(screen: Screen) {
        synchronized(open) { open.add(screen) }
        add(screen, Action.OPEN, null)
    }

    /** [screen] closed after [seconds] on it. */
    fun left(screen: Screen, seconds: Long) {
        synchronized(open) { open.lastIndexOf(screen).takeIf { it >= 0 }?.let(open::removeAt) }
        add(screen, Action.LEAVE, seconds)
    }

    private fun add(screen: Screen, action: Action, value: Any?) {
        if (!enabled) return
        synchronized(queue) {
            if (queue.size >= MAX_QUEUED) queue.removeFirst()
            queue.addLast(Event(System.currentTimeMillis(), screen.wire, action.wire, value))
        }
    }

    /** Up to [max] queued events, oldest first, taken off the queue. */
    fun take(max: Int = BATCH): List<Event> = synchronized(queue) { List(minOf(max, queue.size)) { queue.removeFirst() } }

    /** Sends what's queued. A failed batch is dropped: these are rough counts, not records. */
    suspend fun send(portal: PortalClient) {
        while (enabled) {
            val batch = take().ifEmpty { return }
            val on = runCatching { portal.usage("kotlin", BuildConfig.VERSION_NAME, batch) }.getOrNull() ?: return
            if (!on) enabled = false
        }
    }

    internal val Enum<*>.wire: String get() = name.lowercase()
}

/** Logs [screen] opening while this is composed, and its time on screen when it goes. */
@Composable
fun TrackScreen(screen: Usage.Screen) {
    DisposableEffect(screen) {
        val since = SystemClock.elapsedRealtime()
        Usage.opened(screen)
        onDispose { Usage.left(screen, (SystemClock.elapsedRealtime() - since) / 1000) }
    }
}
