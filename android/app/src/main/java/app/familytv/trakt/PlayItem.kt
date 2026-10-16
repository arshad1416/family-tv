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
    /** Explicit metadata identity for external playback; old catalog intents omit these. */
    val tmdb: String? = null,
    val imdb: String? = null,
) {
    val key: String
        get() = if (kind == ProgressKey.MOVIE) ProgressKey.movie(sourceId, itemId) else ProgressKey.episode(sourceId, itemId, season, episode)

    fun putInto(i: Intent): Intent = i
        .putExtra(KIND, kind).putExtra(SOURCE, sourceId).putExtra(ID, itemId).putExtra(NAME, name)
        .putExtra(SEASON, season).putExtra(EPISODE, episode).putExtra(TMDB, tmdb).putExtra(IMDB, imdb)

    companion object {
        private const val KIND = "playItem.kind"
        private const val SOURCE = "playItem.source"
        private const val ID = "playItem.id"
        private const val NAME = "playItem.name"
        private const val SEASON = "playItem.season"
        private const val EPISODE = "playItem.episode"
        private const val TMDB = "playItem.tmdb"
        private const val IMDB = "playItem.imdb"

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
                i.getStringExtra(TMDB),
                i.getStringExtra(IMDB),
            )
        }
    }
}
