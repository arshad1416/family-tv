package app.familytv.trakt

import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import app.familytv.app
import app.familytv.user.ProgressEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Follows one movie or episode in the player: jumps to the saved resume point (this device's
 * or another's via Trakt), keeps the resume point saved, and scrobbles start/pause/stop.
 * The player only has to [attach] it and call [finish] when it closes.
 */
class PlaybackTracker private constructor(
    private val activity: ComponentActivity,
    private val item: PlayItem,
    private val player: Player,
) : Player.Listener {
    private val sync = activity.app.trakt.sync
    private var saved: ProgressEntity? = null
    private var loaded = false
    private var ready = false
    private var finished = false
    private val ticker: Job

    init {
        player.addListener(this)
        activity.lifecycleScope.launch {
            saved = activity.app.userDb.user().progress(item.key)
            loaded = true
            if (ready) resume()
        }
        // Saves the spot every half minute, so a crash or power cut loses little.
        ticker = activity.lifecycleScope.launch {
            while (isActive) {
                delay(30_000)
                if (player.isPlaying) report(TraktSync.TICK)
            }
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_READY && !ready) {
            ready = true
            if (loaded) resume()
            if (player.playWhenReady) report(TraktSync.START)
        }
        if (state == Player.STATE_ENDED) finish()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (ready && !finished) report(if (playWhenReady) TraktSync.START else TraktSync.PAUSE)
    }

    private fun resume() {
        if (player.currentPosition > 10_000) return // already moved on
        val at = ProgressRules.resumeAt(saved, duration()) ?: return
        player.seekTo(at)
        Toast.makeText(activity, "Resuming from ${clock(at)}", Toast.LENGTH_SHORT).show()
    }

    /** Call when playback ends or the player closes: saves the spot and sends the stop scrobble. */
    fun finish() {
        if (finished) return
        if (ready) report(TraktSync.STOP)
        finished = true
        ticker.cancel()
        player.removeListener(this)
    }

    private fun report(action: String) {
        val dur = duration()
        if (dur <= 0) {
            // Some streams never report a length: keep the spot on this device (resumeAt falls back
            // to the position), but send Trakt nothing, since it only takes a percentage.
            if (!player.isCurrentMediaItemLive && player.currentPosition > 0) sync.record(item, TraktSync.TICK, player.currentPosition, 0, 0f)
            return
        }
        val ended = player.playbackState == Player.STATE_ENDED
        val pos = if (ended) dur else player.currentPosition.coerceIn(0, dur)
        sync.record(item, action, pos, dur, if (ended) 100f else ProgressRules.percent(pos, dur))
    }

    private fun duration(): Long = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0

    companion object {
        /** Starts tracking when the intent names a catalog movie or episode (see [PlayItem.putInto]). */
        fun attach(activity: ComponentActivity, intent: Intent, player: Player): PlaybackTracker? =
            PlayItem.from(intent)?.let { PlaybackTracker(activity, it, player) }

        private fun clock(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}
