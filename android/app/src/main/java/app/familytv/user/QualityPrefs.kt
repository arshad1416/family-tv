package app.familytv.user

import android.content.Context
import app.familytv.FamilyTvApp
import app.familytv.data.Kind
import app.familytv.iptv.QualityPref
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Settings' Stream quality, per kind ([Kind.LIVE], [Kind.MOVIE], [Kind.SERIES]). Kept on this TV, through sign-outs. */
class QualityPrefs(app: FamilyTvApp) {
    private val prefs = app.getSharedPreferences("stream_quality", Context.MODE_PRIVATE)
    private val flows = KINDS.associateWith { MutableStateFlow(read(it)) }

    private fun read(kind: String) = prefs.getString(kind, null)?.let { n -> QualityPref.entries.firstOrNull { it.name == n } } ?: QualityPref.AUTO

    fun flow(kind: String): StateFlow<QualityPref> = flows.getValue(kind)

    operator fun get(kind: String): QualityPref = flows.getValue(kind).value

    fun set(kind: String, pref: QualityPref) {
        prefs.edit().putString(kind, pref.name).apply()
        flows.getValue(kind).value = pref
    }

    companion object {
        val KINDS = listOf(Kind.LIVE, Kind.MOVIE, Kind.SERIES)
    }
}
