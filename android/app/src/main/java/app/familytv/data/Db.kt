package app.familytv.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One row per provider line in the profile. [activeServer] is the Xtream URL that answered last. */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val activeServer: String?,
    val lastRefresh: Long,
    val gen: Long,
)

object Kind {
    const val LIVE = "live"
    const val MOVIE = "movie"
    const val SERIES = "series"
}

@Entity(tableName = "categories", primaryKeys = ["sourceId", "kind", "categoryId"])
data class CategoryEntity(
    val sourceId: String,
    val kind: String,
    val categoryId: String,
    val name: String,
    val sort: Int,
    val gen: Long,
)

@Entity(
    tableName = "channels",
    primaryKeys = ["sourceId", "streamId"],
    indices = [Index("categoryId"), Index("epgId"), Index("num")],
)
data class ChannelEntity(
    val sourceId: String,
    val streamId: String,
    val name: String,
    val num: Int,
    val logo: String?,
    val categoryId: String,
    val epgId: String?,
    /** Full URL for M3U sources; null for Xtream, where it is built from the active server. */
    val url: String?,
    val catchupDays: Int,
    val gen: Long,
)

@Entity(tableName = "movies", primaryKeys = ["sourceId", "streamId"], indices = [Index("categoryId"), Index("tmdb")])
data class MovieEntity(
    val sourceId: String,
    val streamId: String,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val rating: String?,
    val ext: String?,
    val url: String?,
    val added: Long,
    val gen: Long,
    /** From the provider's list when it has one; used to match Trakt. */
    val tmdb: String? = null,
    val year: Int? = null,
)

@Entity(tableName = "series", primaryKeys = ["sourceId", "seriesId"], indices = [Index("categoryId"), Index("tmdb"), Index("titleKey")])
data class SeriesEntity(
    val sourceId: String,
    val seriesId: String,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val plot: String?,
    val rating: String?,
    val gen: Long,
    val tmdb: String? = null,
    val year: Int? = null,
    /** [app.familytv.iptv.SeriesTitles.key] of the cleaned title: copies of one show in other languages share it. */
    val titleKey: String = "",
    /** Language of this copy, from its title or category ([app.familytv.iptv.SeriesTitles.ORIGINAL] when neither says). */
    val lang: String? = null,
)

@Entity(tableName = "programmes", indices = [Index(value = ["epgId", "start"])])
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epgId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
    // What the guide header shows beyond the title (added in v4; older rows have none).
    val subTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** Up to three genres, comma separated. */
    val category: String? = null,
    val icon: String? = null,
    val rating: String? = null,
    val year: Int? = null,
    @ColumnInfo(defaultValue = "0") val isNew: Boolean = false,
    /** Up to five actors, comma separated. */
    val cast: String? = null,
)

data class NowNext(val epgId: String, val title: String, val start: Long, val stop: Long)

@Dao
interface CatalogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSource(s: SourceEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCategories(c: List<CategoryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertChannels(c: List<ChannelEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMovies(c: List<MovieEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSeries(c: List<SeriesEntity>)
    @Insert suspend fun insertProgrammes(p: List<ProgrammeEntity>)

    // Rows not touched by the latest refresh were removed by the provider.
    @Query("DELETE FROM categories WHERE sourceId = :sourceId AND kind = :kind AND gen <> :gen") suspend fun pruneCategories(sourceId: String, kind: String, gen: Long)
    @Query("DELETE FROM channels WHERE sourceId = :sourceId AND gen <> :gen") suspend fun pruneChannels(sourceId: String, gen: Long)
    @Query("DELETE FROM movies WHERE sourceId = :sourceId AND gen <> :gen") suspend fun pruneMovies(sourceId: String, gen: Long)
    @Query("DELETE FROM series WHERE sourceId = :sourceId AND gen <> :gen") suspend fun pruneSeries(sourceId: String, gen: Long)
    @Query("DELETE FROM programmes WHERE stop < :before OR start > :after") suspend fun pruneProgrammes(before: Long, after: Long)
    @Query("DELETE FROM programmes WHERE epgId IN (SELECT epgId FROM channels WHERE sourceId = :sourceId)") suspend fun clearProgrammesFor(sourceId: String)

    @Query("SELECT * FROM sources") suspend fun sources(): List<SourceEntity>
    @Query("SELECT * FROM sources WHERE id = :id") suspend fun source(id: String): SourceEntity?
    @Query("DELETE FROM sources WHERE id NOT IN (:keep)") suspend fun dropSourcesExcept(keep: List<String>)
    @Query("DELETE FROM channels WHERE sourceId NOT IN (SELECT id FROM sources)") suspend fun dropOrphanChannels()
    @Query("DELETE FROM movies WHERE sourceId NOT IN (SELECT id FROM sources)") suspend fun dropOrphanMovies()
    @Query("DELETE FROM series WHERE sourceId NOT IN (SELECT id FROM sources)") suspend fun dropOrphanSeries()
    @Query("DELETE FROM categories WHERE sourceId NOT IN (SELECT id FROM sources)") suspend fun dropOrphanCategories()

    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY sourceId, sort, name") fun categories(kind: String): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM channels WHERE categoryId = :categoryId AND sourceId = :sourceId ORDER BY num, name") fun channels(sourceId: String, categoryId: String): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels ORDER BY sourceId, num, name LIMIT :limit OFFSET :offset") suspend fun allChannels(limit: Int, offset: Int): List<ChannelEntity>
    @Query("SELECT * FROM channels WHERE sourceId = :sourceId LIMIT 1") suspend fun anyChannel(sourceId: String): ChannelEntity?
    @Query("SELECT * FROM channels WHERE name LIKE '%' || :q || '%' ORDER BY num, name LIMIT :limit") suspend fun searchChannels(q: String, limit: Int): List<ChannelEntity>
    @Query("SELECT * FROM movies WHERE name LIKE '%' || :q || '%' ORDER BY name LIMIT :limit") suspend fun searchMovies(q: String, limit: Int): List<MovieEntity>
    @Query("SELECT * FROM series WHERE name LIKE '%' || :q || '%' ORDER BY name LIMIT :limit") suspend fun searchSeries(q: String, limit: Int): List<SeriesEntity>
    @Query("SELECT * FROM movies ORDER BY added DESC LIMIT :limit") suspend fun recentMovies(limit: Int): List<MovieEntity>
    /** Best rated first; providers rate out of 10, so anything above that is junk and left out. */
    @Query("SELECT * FROM movies WHERE CAST(rating AS REAL) BETWEEN :min AND 10 ORDER BY CAST(rating AS REAL) DESC, added DESC LIMIT :limit")
    suspend fun topMovies(min: Double, limit: Int): List<MovieEntity>
    @Query("SELECT * FROM series WHERE CAST(rating AS REAL) BETWEEN :min AND 10 ORDER BY CAST(rating AS REAL) DESC LIMIT :limit")
    suspend fun topSeries(min: Double, limit: Int): List<SeriesEntity>
    @Query("SELECT * FROM movies WHERE categoryId = :categoryId AND sourceId = :sourceId ORDER BY added DESC") fun movies(sourceId: String, categoryId: String): Flow<List<MovieEntity>>
    @Query("SELECT * FROM series WHERE categoryId = :categoryId AND sourceId = :sourceId ORDER BY name") fun series(sourceId: String, categoryId: String): Flow<List<SeriesEntity>>

    @Query(
        """SELECT epgId, title, start, stop FROM programmes
           WHERE epgId IN (:epgIds) AND stop > :now AND start < :until ORDER BY epgId, start""",
    )
    suspend fun programmes(epgIds: List<String>, now: Long, until: Long): List<NowNext>

    @Query("SELECT * FROM programmes WHERE epgId = :epgId AND start = :start LIMIT 1") suspend fun programme(epgId: String, start: Long): ProgrammeEntity?

    @Query("SELECT COUNT(*) FROM channels") suspend fun channelCount(): Int

    // Single rows, for favourites and Trakt matching.
    @Query("SELECT * FROM channels WHERE sourceId = :sourceId AND streamId = :streamId") suspend fun channel(sourceId: String, streamId: String): ChannelEntity?
    @Query("SELECT * FROM movies WHERE sourceId = :sourceId AND streamId = :streamId") suspend fun movie(sourceId: String, streamId: String): MovieEntity?
    @Query("SELECT * FROM series WHERE sourceId = :sourceId AND seriesId = :seriesId") suspend fun seriesItem(sourceId: String, seriesId: String): SeriesEntity?
    @Query("SELECT * FROM movies WHERE tmdb IN (:tmdb)") suspend fun moviesByTmdb(tmdb: List<String>): List<MovieEntity>
    @Query("SELECT * FROM series WHERE tmdb IN (:tmdb)") suspend fun seriesByTmdb(tmdb: List<String>): List<SeriesEntity>
    /** Every copy of a show, in any language: same TMDB id or same cleaned title. */
    @Query("SELECT * FROM series WHERE titleKey IN (:titleKeys) OR tmdb IN (:tmdb) ORDER BY sourceId, name")
    suspend fun seriesCopies(titleKeys: List<String>, tmdb: List<String>): List<SeriesEntity>
}

@Database(
    entities = [SourceEntity::class, CategoryEntity::class, ChannelEntity::class, MovieEntity::class, SeriesEntity::class, ProgrammeEntity::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 3, to = 4)],
)
abstract class AppDb : RoomDatabase() {
    abstract fun catalog(): CatalogDao

    companion object {
        fun create(context: Context): AppDb =
            Room.databaseBuilder(context, AppDb::class.java, "catalog.db")
                // The catalog is a cache of the provider's lists; on schema change just rebuild it.
                .fallbackToDestructiveMigration()
                .build()
    }
}
