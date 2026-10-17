package app.familytv.user

import android.content.Context
import app.familytv.data.MovieEntity
import app.familytv.data.SeriesEntity
import app.familytv.iptv.QualityPref
import app.familytv.iptv.SeriesTitles
import app.familytv.iptv.StreamQuality
import app.familytv.iptv.groupKey
import app.familytv.iptv.language
import java.util.Locale

/**
 * The language the family last picked for each title, and on any title of the kind. A title is
 * remembered under each of its TMDB ids and title keys, so it is found again whichever copy lists it.
 */
abstract class TitleLanguages<T>(context: Context, file: String, fallbackFile: String?) {
    private val prefs = context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)
    private val fallback = fallbackFile?.let { context.applicationContext.getSharedPreferences(it, Context.MODE_PRIVATE) }

    protected abstract fun tmdb(copy: T): String?
    protected abstract fun titleKey(copy: T): String
    protected abstract fun lang(copy: T): String
    protected abstract fun name(copy: T): String

    private fun keys(copies: List<T>): List<String> =
        copies.mapNotNull(::tmdb).distinct().map { "t:$it" } + copies.map(::titleKey).filter { it.isNotEmpty() }.distinct().map { "k:$it" }

    fun remember(copies: List<T>, lang: String) {
        val e = prefs.edit()
        keys(copies).forEach { e.putString(it, lang) }
        e.putString(LAST, lang).apply()
    }

    /**
     * Index of the copy to open: this title's last choice, the last choice anywhere, then the device
     * language; then, among the copies in that language, the one nearest Settings' Stream quality.
     */
    fun choose(copies: List<T>, quality: QualityPref = QualityPref.AUTO): Int {
        val forTitle = keys(copies).firstNotNullOfOrNull { prefs.getString(it, null) }
        val i = SeriesTitles.chooseVariant(
            copies.map(::lang),
            listOf(forTitle, prefs.getString(LAST, null), fallback?.getString(LAST, null), Locale.getDefault().language),
        )
        if (i < 0 || quality == QualityPref.AUTO) return i
        val same = copies.indices.filter { lang(copies[it]) == lang(copies[i]) }
        return same[StreamQuality.pick(same.map { StreamQuality.of(name(copies[it])) }, quality)]
    }

    private companion object {
        const val LAST = "last"
    }
}

class SeriesLanguages(context: Context) : TitleLanguages<SeriesEntity>(context, "series_language", null) {
    override fun tmdb(copy: SeriesEntity) = copy.tmdb
    override fun titleKey(copy: SeriesEntity) = copy.titleKey
    override fun lang(copy: SeriesEntity) = copy.lang ?: SeriesTitles.ORIGINAL
    override fun name(copy: SeriesEntity) = copy.name
}

/** A film with no choice of its own yet follows the language last picked for a show. */
class MovieLanguages(context: Context) : TitleLanguages<MovieEntity>(context, "movie_language", "series_language") {
    override fun tmdb(copy: MovieEntity) = copy.tmdb
    override fun titleKey(copy: MovieEntity) = copy.groupKey
    override fun lang(copy: MovieEntity) = copy.language
    override fun name(copy: MovieEntity) = copy.name
}
