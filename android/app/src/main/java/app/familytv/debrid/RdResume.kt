package app.familytv.debrid

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import app.familytv.trakt.TitleMatch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Resume spots for Real-Debrid titles the catalog doesn't have (played from Search). They stay on
 * this TV: no Trakt, no portal. Keyed by title and episode ([key]), never by the Real-Debrid link.
 */
class RdResume private constructor(private val activity: ComponentActivity, private val key: String, private val player: Player) : Player.Listener {
    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var ready = false
    private var finished = false
    private val ticker: Job

    init {
        player.addListener(this)
        ticker = activity.lifecycleScope.launch {
            while (isActive) {
                delay(30_000)
                if (player.isPlaying) save()
            }
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_READY && !ready) {
            ready = true
            val at = prefs.getLong(key, 0)
            if (at > 10_000 && player.currentPosition < 10_000) {
                player.seekTo(at)
                Toast.makeText(activity, "Resuming", Toast.LENGTH_SHORT).show()
            }
        }
        if (state == Player.STATE_ENDED) { prefs.edit().remove(key).apply(); finish() }
    }

    fun finish() {
        if (finished) return
        if (ready) save()
        finished = true
        ticker.cancel()
        player.removeListener(this)
    }

    private fun save() {
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        val pos = player.currentPosition
        // Near the end counts as finished: start over next time.
        if (player.playbackState == Player.STATE_ENDED || dur > 0 && pos > dur * 0.92) prefs.edit().remove(key).apply()
        else if (pos > 0) prefs.edit().putLong(key, pos).apply()
    }

    companion object {
        private const val PREFS = "realdebrid_resume"
        const val EXTRA_KEY = "rdResumeKey"

        /** "film:<title key>:<year>" or "show:<title key>:<season>:<episode>". */
        fun key(title: RdTitle, episode: RdEpisode? = null): String =
            if (episode == null) "film:${TitleMatch.key(title.title)}:${title.year ?: ""}"
            else "show:${TitleMatch.key(title.title)}:${episode.season}:${episode.episode}"

        fun attach(activity: ComponentActivity, intent: Intent, player: Player): RdResume? =
            intent.getStringExtra(EXTRA_KEY)?.let { RdResume(activity, it, player) }
    }
}
