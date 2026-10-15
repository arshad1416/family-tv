package app.familytv.user

import android.content.Context
import app.familytv.data.SeriesEntity
import app.familytv.iptv.SeriesTitles
import java.util.Locale

/**
 * The language the family last picked for each show, and on any show. A show is remembered
 * under each of its TMDB ids and title keys, so it is found again whichever copy lists it.
 */
class SeriesLanguages(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("series_language", Context.MODE_PRIVATE)

    private fun keys(copies: List<SeriesEntity>): List<String> =
        copies.mapNotNull { it.tmdb }.distinct().map { "t:$it" } + copies.map { it.titleKey }.filter { it.isNotEmpty() }.distinct().map { "k:$it" }

    fun remember(copies: List<SeriesEntity>, lang: String) {
        val e = prefs.edit()
        keys(copies).forEach { e.putString(it, lang) }
        e.putString(LAST, lang).apply()
    }

    /** Index of the copy to open: this show's last choice, the last choice anywhere, then the device language. */
    fun choose(copies: List<SeriesEntity>): Int {
        val forShow = keys(copies).firstNotNullOfOrNull { prefs.getString(it, null) }
        return SeriesTitles.chooseVariant(
            copies.map { it.lang ?: SeriesTitles.ORIGINAL },
            listOf(forShow, prefs.getString(LAST, null), Locale.getDefault().language),
        )
    }

    private companion object {
        const val LAST = "last"
    }
}
