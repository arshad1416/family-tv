package app.familytv.sync

import app.familytv.FamilyTvApp
import app.familytv.data.Kind
import kotlinx.coroutines.flow.first

/** Sends the portal this TV's category names whenever they change, so the profile's order can be arranged there. */
object CategoryReport {
    suspend fun sendIfChanged(app: FamilyTvApp) {
        val dao = app.db.catalog()
        val names = listOf(Kind.LIVE, Kind.MOVIE, Kind.SERIES).associateWith { k -> dao.categories(k).first().map { it.name }.distinct() }
        if (names.values.all { it.isEmpty() }) return
        val sent = names.hashCode().toString()
        val prefs = app.getSharedPreferences("category_report", 0)
        if (prefs.getString("sent", null) == sent) return
        app.portal.uploadCategories(names)
        prefs.edit().putString("sent", sent).apply()
    }
}
