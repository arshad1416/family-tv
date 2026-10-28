package app.familytv.transfer

import app.familytv.FamilyTvApp
import app.familytv.data.Kind
import app.familytv.iptv.ServerSwitch
import app.familytv.portal.Profile
import app.familytv.user.FavouriteEntity
import app.familytv.user.GlassPrefs
import app.familytv.user.Pending
import app.familytv.user.SaverSource
import app.familytv.user.Viewer
import app.familytv.user.Viewers
import com.google.gson.Gson
import com.google.gson.JsonElement
import java.util.UUID

/**
 * The one-button move from the Rust app (Arshad, Oct 28): the Rust app on a paired TV gets a one-time code from
 * the portal and opens this app with it in [EXTRA]. Not yet paired, this app claims the code, takes over that TV's
 * device row (same name, profile and lock; the Rust app's token stops working) and goes straight to the home with
 * the Rust app's favourites, viewers, screensaver and server choices ([Local]). Already paired, the code is ignored.
 * Codes and tokens are never logged.
 */
object Transfer {
    const val EXTRA = "app.familytv.TRANSFER_CODE"
    private val CODE = Regex("^[a-f0-9]{32}$")
    @Volatile private var code: String? = null
    /** Settings claimed with the code, kept until the profile is known. */
    @Volatile var claimed: Local? = null

    /** A code from the intent that opened the app; false when there is none or it isn't one. */
    fun offer(raw: String?): Boolean {
        code = raw?.trim()?.lowercase()?.takeIf(CODE::matches) ?: return false
        return true
    }

    /** The code waiting to be claimed, once. */
    fun take(): String? = code.also { code = null }

    /** The Rust app's settings as it sent them, or null when they don't read as [Local]. */
    fun parse(json: JsonElement?): Local? = json?.takeIf { it.isJsonObject }?.let { runCatching { Gson().fromJson(it, Local::class.java) }.getOrNull() }

    /** Brings [local] onto this TV for [profile], filling in only what this TV doesn't have yet. */
    suspend fun apply(app: FamilyTvApp, local: Local, profile: Profile) {
        val dao = app.userDb.user()
        val fresh = local.favourites.orEmpty().mapNotNull { it.entity() }.filter { dao.favourite(it.kind, it.sourceId, it.itemId) == null }
        if (fresh.isNotEmpty()) dao.upsertFavourites(fresh)
        app.viewers.restore(
            local.viewers.orEmpty().mapNotNull { v ->
                val name = v.name?.trim()?.take(Viewers.MAX_NAME)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Viewer(v.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(), name, v.kids) to v.favourites.orEmpty().mapNotNull { it.entity() }
            },
            local.currentViewer,
        )
        local.screensaver?.let { s ->
            s.minutes?.takeIf { it in GlassPrefs.SAVER_CHOICES }?.let(app.glassPrefs::setSaverMinutes)
            SaverSource.entries.firstOrNull { it != SaverSource.PHOTOS && it.name.equals(s.source, ignoreCase = true) }?.let(app.glassPrefs::setSaverSource)
        }
        local.servers?.let { ServerSwitch.restore(profile, it) }
    }

    /** What the Rust app hands over (parity checklist, "Spec: One-button move from Rust"). Every field is optional. */
    data class Local(
        val v: Int = 0,
        val favourites: List<Favourite>? = null,
        val viewers: List<RustViewer>? = null,
        val currentViewer: String? = null,
        val screensaver: Screensaver? = null,
        /** Per source id, the place in its server list of the server the TV switched to. */
        val servers: Map<String, Int>? = null,
    )

    data class Favourite(val kind: String? = null, val sourceId: String? = null, val itemId: String? = null, val name: String? = null, val addedAt: Long? = null) {
        fun entity(): FavouriteEntity? {
            val k = kind?.takeIf { it in KINDS } ?: return null
            val source = sourceId?.takeIf { it.isNotBlank() } ?: return null
            val item = itemId?.takeIf { it.isNotBlank() } ?: return null
            // As a favourite added here: movies and shows go to Trakt when it's connected.
            return FavouriteEntity(k, source, item, name.orEmpty().take(200), addedAt ?: System.currentTimeMillis(), pending = if (k == Kind.LIVE) null else Pending.ADD)
        }
    }

    data class RustViewer(val id: String? = null, val name: String? = null, val kids: Boolean = false, val favourites: List<Favourite>? = null)

    data class Screensaver(val minutes: Int? = null, val source: String? = null)

    private val KINDS = setOf(Kind.LIVE, Kind.MOVIE, Kind.SERIES)
}
