package app.familytv.user

import android.content.Context
import android.content.SharedPreferences
import app.familytv.portal.Profile

/** A live channel as Left goes back to it: its line, the category it played from and its stream. */
data class ChannelRef(val sourceId: String, val categoryId: String, val streamId: String)

/**
 * The last two live channels that actually played, for Left's "previous channel" (like a remote's
 * Last button). Kept on this TV for each profile (told apart by its lines), through restarts.
 */
class LastChannels(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("last_channels", Context.MODE_PRIVATE))

    /** [ch] started playing: the one before it becomes the previous channel. Playing the same one again changes nothing. */
    fun played(profile: Profile, ch: ChannelRef) {
        val key = key(profile)
        val now = read("$key/now")
        if (now == ch) return
        prefs.edit().apply { now?.let { putString("$key/before", write(it)) } }.putString("$key/now", write(ch)).apply()
    }

    /** The channel that played before the current one, if it's still on one of the profile's lines. */
    fun previous(profile: Profile): ChannelRef? =
        read("${key(profile)}/before")?.takeIf { ref -> profile.sources.any { it.id == ref.sourceId } }

    private fun key(profile: Profile) = profile.sources.map { it.id }.sorted().joinToString(",")

    private fun read(k: String) = prefs.getString(k, null)?.split(SEP)?.takeIf { it.size == 3 }?.let { ChannelRef(it[0], it[1], it[2]) }

    private fun write(c: ChannelRef) = listOf(c.sourceId, c.categoryId, c.streamId).joinToString(SEP)

    private companion object {
        const val SEP = "\u001f"
    }
}
