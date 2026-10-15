package app.familytv.user

import android.content.Context
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
    /** Change not yet sent to Trakt: [Pending.ADD], [Pending.REMOVE] (row kept as a tombstone) or null. */
    val pending: String? = null,
    /** On the Trakt watchlist as of the last sync; if it disappears there, it was removed elsewhere. */
    val onTrakt: Boolean = false,
)

object Pending {
    const val ADD = "add"
    const val REMOVE = "remove"
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

@Dao
interface UserDao {
    @Query("SELECT * FROM favourites WHERE pending IS NULL OR pending <> 'remove' ORDER BY addedAt DESC")
    fun favourites(): Flow<List<FavouriteEntity>>
    @Query("SELECT * FROM favourites") suspend fun allFavouriteRows(): List<FavouriteEntity>
    @Query("SELECT * FROM favourites WHERE kind = :kind AND sourceId = :sourceId AND itemId = :itemId")
    suspend fun favourite(kind: String, sourceId: String, itemId: String): FavouriteEntity?
    @Upsert suspend fun upsertFavourite(f: FavouriteEntity)
    @Upsert suspend fun upsertFavourites(f: List<FavouriteEntity>)
    @Query("DELETE FROM favourites WHERE kind = :kind AND sourceId = :sourceId AND itemId = :itemId")
    suspend fun deleteFavourite(kind: String, sourceId: String, itemId: String)
    @Query("DELETE FROM favourites WHERE pending = 'remove'") suspend fun dropTombstones()
    @Query("UPDATE favourites SET onTrakt = 0, pending = 'add' WHERE kind <> 'live'") suspend fun markAllUnsynced()

    @Query("SELECT * FROM progress") fun progress(): Flow<List<ProgressEntity>>
    @Query("SELECT * FROM progress WHERE `key` = :key") suspend fun progress(key: String): ProgressEntity?
    @Query("SELECT * FROM progress WHERE `key` IN (:keys)") suspend fun progressFor(keys: List<String>): List<ProgressEntity>
    @Query("SELECT * FROM progress WHERE pendingHistory = 1") suspend fun pendingHistory(): List<ProgressEntity>
    @Upsert suspend fun upsertProgress(p: ProgressEntity)
    @Upsert suspend fun upsertProgressRows(p: List<ProgressEntity>)
    @Query("UPDATE progress SET pendingHistory = 0 WHERE `key` IN (:keys)") suspend fun clearPendingHistory(keys: List<String>)

    @Query("SELECT * FROM media_ids WHERE kind = :kind AND sourceId = :sourceId AND itemId = :itemId")
    suspend fun ids(kind: String, sourceId: String, itemId: String): MediaIdsEntity?
    @Query("SELECT * FROM media_ids WHERE kind = :kind AND (tmdb IN (:tmdb) OR imdb IN (:imdb) OR trakt IN (:trakt))")
    suspend fun idsMatching(kind: String, tmdb: List<String>, imdb: List<String>, trakt: List<Long>): List<MediaIdsEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putIds(e: MediaIdsEntity)
}

@Database(entities = [FavouriteEntity::class, ProgressEntity::class, MediaIdsEntity::class], version = 1, exportSchema = true)
abstract class UserDb : RoomDatabase() {
    abstract fun user(): UserDao

    companion object {
        fun create(context: Context): UserDb = Room.databaseBuilder(context, UserDb::class.java, "user.db").build()
    }
}
