package app.familytv.user

import android.content.Context
import android.content.SharedPreferences
import app.familytv.portal.Profile
import app.familytv.portal.Viewer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Who is watching on this TV (backlog items 17 and 18): one of the profile's viewers, picked from
 * the top bar and kept through restarts, so a kids viewer stays one until the parental PIN lets
 * someone else in. Kept on this TV only, for each profile (told apart by its lines).
 */
class Viewers(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("viewers", Context.MODE_PRIVATE))

    private val _changes = MutableStateFlow(0)

    /** Bumps whenever the viewer changes, so the home re-reads [current]. */
    val changes: StateFlow<Int> = _changes

    /** The viewer picked on this TV, if the profile still has them. */
    fun current(profile: Profile?): Viewer? {
        val id = profile?.let { prefs.getString(key(it), null) } ?: return null
        return profile.viewers?.firstOrNull { it.id == id }
    }

    fun choose(profile: Profile, viewer: Viewer) {
        prefs.edit().putString(key(profile), viewer.id).apply()
        _changes.value++
    }

    /** [profile] as the current viewer sees it: kids' categories only while a kids viewer watches. */
    fun apply(profile: Profile): Profile = if (current(profile)?.kids == true) profile.copy(kidsOnly = true) else profile

    private fun key(profile: Profile) = profile.sources.map { it.id }.sorted().joinToString(",")
}
