package app.familytv.user

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * What the family chose rather than what the provider sent: favourites, resume points and
 * watched marks. It lives in its own database so reloading channels (which wipes the catalog)
 * never loses it. Rows are keyed by the provider's own stream or series id, which stays the
 * same across list reloads.
 */
@Entity(tableName = "favourites", primaryKeys = ["kind", "sourceId", "itemId"])
data class FavouriteEntity(
    /** [app.familytv.data.Kind]: live, movie or series. */
    val kind: String,
    val sourceId: String,
    /** streamId for channels and movies, seriesId for series. */
    val itemId: String,
    val name: String,
    val addedAt: Long,
    /** Change not yet sent to Trakt: [Pending.ADD], [Pending.REMOVE] (row kept as a tombstone), [Pending.HIDDEN] or null. */
    val pending: String? = null,
    /** On the Trakt watchlist as of the last sync; if it disappears there, it was removed elsewhere. */
    val onTrakt: Boolean = false,
    /**
     * Added because the show is in the family's Trakt history, not by hand. It stays on this
     * device (never pushed to the watchlist); removing it leaves a [Pending.HIDDEN] row so the
     * next sync doesn't add it back.
     */
    @ColumnInfo(defaultValue = "0") val fromHistory: Boolean = false,
)

object Pending {
    const val ADD = "add"
    const val REMOVE = "remove"
    /** A [FavouriteEntity.fromHistory] favourite the family removed. */
    const val HIDDEN = "hidden"
}

/** Where playback stopped, and whether it was watched. One row per movie or per episode. */
@Entity(tableName = "progress", primaryKeys = ["key"])
data class ProgressEntity(
    /** [ProgressKey.movie] or [ProgressKey.episode]. */
    val key: String,
    val kind: String,
    val sourceId: String,
    /** streamId for a movie, seriesId for an episode. */
    val itemId: String,
    val season: Int = 0,
    val episode: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** 0..100, as Trakt reports it. */
    val percent: Float = 0f,
    val watched: Boolean = false,
    val updatedAt: Long = 0,
    /** Watched here but not yet on Trakt (the stop scrobble failed); pushed through sync/history. */
    val pendingHistory: Boolean = false,
)

object ProgressKey {
    const val MOVIE = "movie"
    const val EPISODE = "episode"
    fun movie(sourceId: String, streamId: String) = "m/$sourceId/$streamId"
    fun episode(sourceId: String, seriesId: String, season: Int, episode: Int) = "e/$sourceId/$seriesId/$season/$episode"
}

/** TMDB/IMDb/Trakt ids found for a catalog item, so each item is looked up once. */
@Entity(tableName = "media_ids", primaryKeys = ["kind", "sourceId", "itemId"])
data class MediaIdsEntity(
    val kind: String,
    val sourceId: String,
    val itemId: String,
    val tmdb: String?,
    val imdb: String?,
    val trakt: Long?,
    /** When the lookup ran; a failed lookup (all ids null) is retried after a week. */
    val checkedAt: Long,
)

/**
 * The newest episode seen for a favourite show, so the background check can tell when another
 * one arrives. [newEpisodes] lists the episodes waiting to be watched (see
 * [app.familytv.iptv.EpisodeGuide.waiting]); the show's counter is how many are still unwatched.
 */
@Entity(tableName = "episode_watch", primaryKeys = ["sourceId", "seriesId"])
data class EpisodeWatchEntity(
    val sourceId: String,
    val seriesId: String,
    val name: String,
    val latestSeason: Int,
    val latestEpisode: Int,
    val checkedAt: Long,
    /** [newEpisodes]' size; rows from before that list existed keep their old count until the page is opened. */
    val newCount: Int = 0,
    /** When the unwatched new episodes appeared; 0 when there are none. */
    val newSince: Long = 0,
    /** The new episodes not yet watched, as [NewEpisodeList] text ("1:5,1:6"). */
    @ColumnInfo(defaultValue = "") val newEpisodes: String = "",
)

/** The new episodes of a show, stored as "season:episode" pairs separated by commas. */
object NewEpisodeList {
    fun encode(eps: List<Pair<Int, Int>>): String = eps.joinToString(",") { "${it.first}:${it.second}" }

    fun decode(s: String): List<Pair<Int, Int>> = s.split(',').mapNotNull { p ->
        val (a, b) = p.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        a.trim().toIntOrNull()?.let { season -> b.trim().toIntOrNull()?.let { season to it } }
    }

    /** Episodes in [w]'s list the family hasn't watched; rows from before the list keep their old count. */
    fun unwatched(w: EpisodeWatchEntity, watched: (season: Int, episode: Int) -> Boolean): Int =
        if (w.newEpisodes.isEmpty()) w.newCount else decode(w.newEpisodes).count { (s, e) -> !watched(s, e) }
}

@Dao
interface UserDao {
    @Query("SELECT * FROM favourites WHERE pending IS NULL OR pending NOT IN ('remove', 'hidden') ORDER BY addedAt DESC")
    fun favourites(): Flow<List<FavouriteEntity>>
    @Query("SELECT * FROM favourites") suspend fun allFavouriteRows(): List<FavouriteEntity>
    @Query("SELECT * FROM favourites WHERE kind = :kind AND sourceId = :sourceId AND itemId = :itemId")
    suspend fun favourite(kind: String, sourceId: String, itemId: String): FavouriteEntity?
    @Upsert suspend fun upsertFavourite(f: FavouriteEntity)
    @Upsert suspend fun upsertFavourites(f: List<FavouriteEntity>)
    @Query("DELETE FROM favourites WHERE kind = :kind AND sourceId = :sourceId AND itemId = :itemId")
    suspend fun deleteFavourite(kind: String, sourceId: String, itemId: String)
    @Query("DELETE FROM favourites WHERE pending = 'remove'") suspend fun dropTombstones()
    @Query("UPDATE favourites SET onTrakt = 0, pending = 'add' WHERE kind <> 'live' AND fromHistory = 0") suspend fun markAllUnsynced()

    @Query("SELECT * FROM progress") fun progress(): Flow<List<ProgressEntity>>
    @Query("SELECT * FROM progress WHERE `key` = :key") suspend fun progress(key: String): ProgressEntity?
    @Query("SELECT * FROM progress WHERE `key` IN (:keys)") suspend fun progressFor(keys: List<String>): List<ProgressEntity>
    @Query("SELECT * FROM progress WHERE pendingHistory = 1") suspend fun pendingHistory(): List<ProgressEntity>
    @Query("SELECT * FROM progress WHERE kind = 'episode' AND sourceId = :sourceId AND itemId = :seriesId AND watched = 1")
    suspend fun watchedEpisodes(sourceId: String, seriesId: String): List<ProgressEntity>
    @Upsert suspend fun upsertProgress(p: ProgressEntity)
    @Upsert suspend fun upsertProgressRows(p: List<ProgressEntity>)
    @Query("UPDATE progress SET pendingHistory = 0 WHERE `key` IN (:keys)") suspend fun clearPendingHistory(keys: List<String>)

    @Query("SELECT * FROM episode_watch") suspend fun episodeWatches(): List<EpisodeWatchEntity>
    @Query("SELECT * FROM episode_watch WHERE newSince > 0 ORDER BY newSince DESC") fun newEpisodes(): Flow<List<EpisodeWatchEntity>>
    @Upsert suspend fun upsertEpisodeWatch(w: EpisodeWatchEntity)
    /** Opening the page clears only a count from before the episode list existed. */
    @Query("UPDATE episode_watch SET newCount = 0, newSince = 0 WHERE sourceId = :sourceId AND seriesId = :seriesId AND newEpisodes = ''")
    suspend fun clearOldNewCount(sourceId: String, seriesId: String)
    @Query("DELETE FROM episode_watch WHERE sourceId = :sourceId AND seriesId = :seriesId") suspend fun deleteEpisodeWatch(sourceId: String, seriesId: String)

    @Query("SELECT * FROM media_ids WHERE kind = :kind AND sourceId = :sourceId AND itemId = :itemId")
    suspend fun ids(kind: String, sourceId: String, itemId: String): MediaIdsEntity?
    @Query("SELECT * FROM media_ids WHERE kind = :kind AND (tmdb IN (:tmdb) OR imdb IN (:imdb) OR trakt IN (:trakt))")
    suspend fun idsMatching(kind: String, tmdb: List<String>, imdb: List<String>, trakt: List<Long>): List<MediaIdsEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putIds(e: MediaIdsEntity)
}

@Database(
    entities = [FavouriteEntity::class, ProgressEntity::class, MediaIdsEntity::class, EpisodeWatchEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class UserDb : RoomDatabase() {
    abstract fun user(): UserDao

    companion object {
        fun create(context: Context): UserDb = Room.databaseBuilder(context, UserDb::class.java, "user.db").build()
    }
}
