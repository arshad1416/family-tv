package app.familytv.user

import android.content.Context
import app.familytv.data.Kind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The order this device's viewer chose in Settings for each kind's categories. It comes before
 * the profile's order from the portal; categories it doesn't name follow in that order.
 */
class MyCategoryOrder(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("my_category_order", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(KINDS.associateWith { load(it) }.filterValues { it.isNotEmpty() })

    /** Category names per kind, as arranged on this device; a kind left alone is missing. */
    val order: StateFlow<Map<String, List<String>>> = state

    fun set(kind: String, names: List<String>) {
        prefs.edit().putString(kind, names.joinToString(SEP)).apply()
        state.value = state.value + (kind to names)
    }

    fun reset(kind: String) {
        prefs.edit().remove(kind).apply()
        state.value = state.value - kind
    }

    private fun load(kind: String): List<String> = prefs.getString(kind, null)?.split(SEP)?.filter { it.isNotEmpty() }.orEmpty()

    private companion object {
        val KINDS = listOf(Kind.LIVE, Kind.MOVIE, Kind.SERIES)
        const val SEP = "\n"
    }
}
