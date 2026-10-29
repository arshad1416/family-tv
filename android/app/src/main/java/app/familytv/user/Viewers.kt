package app.familytv.user

import android.content.Context
import android.content.SharedPreferences
import app.familytv.portal.Profile
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** A person who watches on this TV. A [kids] viewer sees kids' categories only, and leaving them needs the parental PIN. */
data class Viewer(val id: String = "", val name: String = "", val kids: Boolean = false)

/**
 * Who is watching on this TV (backlog items 17 and 18). The viewers are added and removed in
 * Settings on the TV itself, and one is picked from the top bar and kept through restarts, so a
 * kids viewer stays one until the parental PIN lets someone else in. Each viewer has their own
 * favourites and the titles they played here, for their own Continue watching and For you.
 * All of it stays on this TV; resume points and watched marks are still the profile's.
 */
class Viewers(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("viewers", Context.MODE_PRIVATE))

    private val gson = Gson()
    private val _changes = MutableStateFlow(0)

    /** Bumps whenever the viewers, the one watching, or their favourites or titles change. */
    val changes: StateFlow<Int> = _changes

    init {
        // Builds before viewers lived on the TV kept the picked one per profile, under a key of its lines.
        val stale = prefs.all.keys.filter { it != LIST && it != CURRENT && !it.startsWith(FAVS) && !it.startsWith(SEEN) }
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach(::remove) }.apply()
    }

    fun all(): List<Viewer> = read(prefs.getString(LIST, null), Array<Viewer>::class.java)

    /** The viewer picked on this TV, if they're still on it. */
    fun current(): Viewer? = prefs.getString(CURRENT, null)?.let { id -> all().firstOrNull { it.id == id } }

    fun choose(viewer: Viewer) = edit { putString(CURRENT, viewer.id) }

    /** Adds a viewer and returns them; a kids viewer needs [profile] to have a parental PIN, so leaving them can ask for it. */
    fun add(profile: Profile, name: String, kids: Boolean): Viewer? {
        val clean = name.trim().take(MAX_NAME)
        val list = all()
        if (clean.isEmpty() || list.size >= MAX || (kids && profile.parentalPin.isNullOrEmpty())) return null
        val v = Viewer(UUID.randomUUID().toString(), clean, kids)
        edit { putString(LIST, gson.toJson(list + v)) }
        return v
    }

    /**
     * Viewers brought over from the Rust app on this TV, with their favourites ([app.familytv.transfer.Transfer]), and
     * the one watching there. Merged in: a viewer this TV already has (the same id, or the same name) only gains the
     * favourites they lack, others are added while there's room, and the one watching here is kept if anyone is.
     */
    fun restore(viewers: List<Pair<Viewer, List<FavouriteEntity>>>, current: String?) {
        if (viewers.isEmpty()) return
        val list = all().toMutableList()
        val favs = LinkedHashMap<String, List<FavouriteEntity>>()
        var pick: String? = null
        for ((v, incoming) in viewers) {
            val into = list.firstOrNull { it.id == v.id || it.name.equals(v.name, ignoreCase = true) }
                ?: v.takeIf { list.size < MAX }?.also { list += it }
                ?: continue
            val have = favs[into.id] ?: favouritesOf(into)
            val add = incoming.filter { f -> have.none { it.kind == f.kind && it.sourceId == f.sourceId && it.itemId == f.itemId } }
            favs[into.id] = (have + add).take(MAX_FAVOURITES)
            if (v.id == current) pick = into.id
        }
        edit {
            putString(LIST, gson.toJson(list))
            favs.forEach { (id, f) -> putString(FAVS + id, gson.toJson(f)) }
            if (pick != null && current() == null) putString(CURRENT, pick)
        }
    }

    /** Removes [viewer] with their favourites and titles; when they were watching, nobody is picked. */
    fun remove(viewer: Viewer) = edit {
        putString(LIST, gson.toJson(all().filter { it.id != viewer.id }))
        remove(FAVS + viewer.id).remove(SEEN + viewer.id)
        if (prefs.getString(CURRENT, null) == viewer.id) remove(CURRENT)
    }

    /** [profile] as the current viewer sees it: kids' categories only while a kids viewer watches. */
    fun apply(profile: Profile): Profile = if (current()?.kids == true) profile.copy(kidsOnly = true) else profile

    /** The current viewer's favourites, newest first, or null when nobody is picked (the TV's own favourites then). */
    fun favourites(): List<FavouriteEntity>? = current()?.let { favouritesOf(it) }

    /** Every viewer's favourites, for the new-episode check. */
    fun everyonesFavourites(): List<FavouriteEntity> = all().flatMap { favouritesOf(it) }

    /** Flips a favourite for the current viewer; true when it is now a favourite. Never sent to Trakt. */
    fun toggleFavourite(viewer: Viewer, kind: String, sourceId: String, itemId: String, name: String): Boolean {
        val list = favouritesOf(viewer)
        val on = list.none { it.kind == kind && it.sourceId == sourceId && it.itemId == itemId }
        val next = if (on) listOf(FavouriteEntity(kind, sourceId, itemId, name, System.currentTimeMillis())) + list
        else list.filterNot { it.kind == kind && it.sourceId == sourceId && it.itemId == itemId }
        edit { putString(FAVS + viewer.id, gson.toJson(next.take(MAX_FAVOURITES))) }
        return on
    }

    /** The current viewer played [progressKey] (a [ProgressKey] movie or episode) on this TV. */
    fun played(progressKey: String) {
        val viewer = current() ?: return
        val seen = seenOf(viewer)
        if (seen.firstOrNull() == progressKey) return
        edit { putString(SEEN + viewer.id, gson.toJson((listOf(progressKey) + (seen - progressKey)).take(MAX_SEEN))) }
    }

    /** The [ProgressKey]s the current viewer played here, newest first, or null when nobody is picked. */
    fun seen(): List<String>? = current()?.let { seenOf(it) }

    private fun favouritesOf(v: Viewer): List<FavouriteEntity> = read(prefs.getString(FAVS + v.id, null), Array<FavouriteEntity>::class.java)

    private fun seenOf(v: Viewer): List<String> = read(prefs.getString(SEEN + v.id, null), Array<String>::class.java)

    private fun <T> read(json: String?, type: Class<Array<T>>): List<T> = json?.let { runCatching { gson.fromJson(it, type)?.toList() }.getOrNull() }.orEmpty()

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _changes.value++
    }

    companion object {
        const val MAX = 8
        const val MAX_NAME = 24
        private const val MAX_FAVOURITES = 200
        private const val MAX_SEEN = 300
        private const val LIST = "list"
        private const val CURRENT = "current"
        private const val FAVS = "favs/"
        private const val SEEN = "seen/"
    }
}
