package app.familytv.trakt

import android.content.Intent
import app.familytv.user.ProgressKey

/**
 * The catalog item behind a VOD playback, passed to the player so it can resume and scrobble.
 * [itemId] is the movie's streamId, or the seriesId for an episode.
 */
data class PlayItem(
    val kind: String,
    val sourceId: String,
    val itemId: String,
    val name: String,
    val season: Int = 0,
    val episode: Int = 0,
    /** Start over: play from the beginning, ignoring the resume point (which keeps being saved). */
    val fromStart: Boolean = false,
) {
    val key: String
        get() = if (kind == ProgressKey.MOVIE) ProgressKey.movie(sourceId, itemId) else ProgressKey.episode(sourceId, itemId, season, episode)

    fun putInto(i: Intent): Intent = i
        .putExtra(KIND, kind).putExtra(SOURCE, sourceId).putExtra(ID, itemId).putExtra(NAME, name)
        .putExtra(SEASON, season).putExtra(EPISODE, episode).putExtra(FROM_START, fromStart)

    companion object {
        private const val KIND = "playItem.kind"
        private const val SOURCE = "playItem.source"
        private const val ID = "playItem.id"
        private const val NAME = "playItem.name"
        private const val SEASON = "playItem.season"
        private const val EPISODE = "playItem.episode"
        private const val FROM_START = "playItem.fromStart"

        fun movie(sourceId: String, streamId: String, name: String) = PlayItem(ProgressKey.MOVIE, sourceId, streamId, name)
        fun episode(sourceId: String, seriesId: String, seriesName: String, season: Int, episode: Int) =
            PlayItem(ProgressKey.EPISODE, sourceId, seriesId, seriesName, season, episode)

        fun from(i: Intent): PlayItem? {
            val kind = i.getStringExtra(KIND) ?: return null
            return PlayItem(
                kind,
                i.getStringExtra(SOURCE) ?: return null,
                i.getStringExtra(ID) ?: return null,
                i.getStringExtra(NAME).orEmpty(),
                i.getIntExtra(SEASON, 0),
                i.getIntExtra(EPISODE, 0),
                i.getBooleanExtra(FROM_START, false),
            )
        }
    }
}
