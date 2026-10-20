package app.familytv.user

import android.content.Context
import android.content.SharedPreferences
import app.familytv.portal.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A live channel as Left goes back to it: its line, the category it played from and its stream. */
data class ChannelRef(val sourceId: String, val categoryId: String, val streamId: String)

/**
 * The last [KEEP] live channels that actually played, newest first: Live TV's "Recently watched"
 * strip, and the one before the current one is Left's "previous channel" (like a remote's Last
 * button). Kept on this TV only, for each profile (told apart by its lines), through restarts.
 */
class LastChannels(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("last_channels", Context.MODE_PRIVATE))

    private val _changes = MutableStateFlow(0)

    /** Bumps whenever a channel is recorded, so an open Live TV page re-reads [recent]. */
    val changes: StateFlow<Int> = _changes

    /** [ch] started playing: it moves to the front. Playing the current one again changes nothing. */
    fun played(profile: Profile, ch: ChannelRef) {
        val key = key(profile)
        val list = all(key)
        if (list.firstOrNull() == ch) return
        prefs.edit()
            .putString("$key/recent", (listOf(ch) + (list - ch)).take(KEEP).joinToString(LIST_SEP) { write(it) })
            // The two-channel keys of older builds are folded into the list on first read.
            .remove("$key/now").remove("$key/before")
            .apply()
        _changes.value++
    }

    /** The channels that played, newest first, that are still on one of the profile's lines. */
    fun recent(profile: Profile): List<ChannelRef> =
        all(key(profile)).filter { ref -> profile.sources.any { it.id == ref.sourceId } }

    /** The channel that played before the current one, if it's still on one of the profile's lines. */
    fun previous(profile: Profile): ChannelRef? =
        all(key(profile)).getOrNull(1)?.takeIf { ref -> profile.sources.any { it.id == ref.sourceId } }

    private fun all(key: String): List<ChannelRef> =
        prefs.getString("$key/recent", null)?.split(LIST_SEP)?.mapNotNull(::read)
            ?: listOfNotNull(prefs.getString("$key/now", null)?.let(::read), prefs.getString("$key/before", null)?.let(::read))

    private fun key(profile: Profile) = profile.sources.map { it.id }.sorted().joinToString(",")

    private fun read(s: String) = s.split(SEP).takeIf { it.size == 3 }?.let { ChannelRef(it[0], it[1], it[2]) }

    private fun write(c: ChannelRef) = listOf(c.sourceId, c.categoryId, c.streamId).joinToString(SEP)

    companion object {
        const val KEEP = 8
        private const val SEP = "\u001f"
        private const val LIST_SEP = "\u001e"
    }
}
