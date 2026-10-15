package app.familytv.user

import app.familytv.FamilyTvApp
import app.familytv.data.Kind
import kotlinx.coroutines.flow.Flow

/**
 * Favourite channels, movies and series. Movies and series follow the Trakt watchlist when an
 * account is connected, and shows in the Trakt history are added too; channels stay on this
 * device (Trakt has no live TV).
 */
class Favourites(private val app: FamilyTvApp) {
    private val dao get() = app.userDb.user()

    /** Every favourite, newest first (removals waiting for Trakt are left out). */
    val all: Flow<List<FavouriteEntity>> get() = dao.favourites()

    /** Flips the favourite and returns true when it is now a favourite. */
    suspend fun toggle(kind: String, sourceId: String, itemId: String, name: String): Boolean {
        val row = dao.favourite(kind, sourceId, itemId)
        val wasFavourite = row != null && row.pending != Pending.REMOVE && row.pending != Pending.HIDDEN
        when {
            // From Trakt history: hide it, so the next sync doesn't bring it back.
            wasFavourite && row!!.fromHistory -> dao.upsertFavourite(row.copy(pending = Pending.HIDDEN))
            // Added back by hand: an ordinary favourite from now on.
            row != null && row.pending == Pending.HIDDEN -> dao.upsertFavourite(
                row.copy(name = name, addedAt = System.currentTimeMillis(), fromHistory = false, pending = if (kind == Kind.LIVE) null else Pending.ADD),
            )
            // Never on Trakt (or a channel): just forget it.
            wasFavourite && (kind == Kind.LIVE || !row!!.onTrakt) -> dao.deleteFavourite(kind, sourceId, itemId)
            // Keep a tombstone until the removal reaches the watchlist.
            wasFavourite -> dao.upsertFavourite(row!!.copy(pending = Pending.REMOVE))
            // Taken back before the removal went out: it is still on the watchlist.
            row != null -> dao.upsertFavourite(row.copy(pending = null, name = name))
            else -> dao.upsertFavourite(
                FavouriteEntity(kind, sourceId, itemId, name, System.currentTimeMillis(), pending = if (kind == Kind.LIVE) null else Pending.ADD),
            )
        }
        if (kind != Kind.LIVE) app.trakt.sync.soon()
        return !wasFavourite
    }

    companion object {
        fun key(kind: String, sourceId: String, itemId: String) = "$kind/$sourceId/$itemId"
    }
}
