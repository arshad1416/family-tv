package app.familytv.iptv

import app.familytv.data.MovieEntity

/**
 * Providers list one film several times too: once per language category ("EN - Dune",
 * "FR - Dune"), or in 4K beside HD. Copies group like a show's ([SeriesTitles.group]), except
 * that two films sharing a title but released years apart (remakes) stay apart. Unit tested in
 * MovieTitlesTest.
 */
object MovieTitles {
    private val YEAR_IN_NAME = Regex("(?:[\\[(]\\s*|\\s)((?:19|20)\\d{2})\\s*[\\])]?\\s*(?:[\\[(][^\\[\\]()]*[\\])]\\s*)*$")

    /** A release year written at the end of a title ("Dune (2021)", "Dune 2021 [4K]"), or null. */
    fun yearIn(name: String): Int? = YEAR_IN_NAME.find(name.trim())?.groupValues?.get(1)?.toIntOrNull()

    fun <T> group(items: List<T>, tmdb: (T) -> String?, key: (T) -> String, year: (T) -> Int?): List<Pair<String, List<T>>> =
        SeriesTitles.group(items, tmdb, key).flatMap { (k, copies) ->
            byYear(copies, year).mapIndexed { i, part -> (if (i == 0) k else "$k#${part.firstNotNullOfOrNull(year)}") to part }
        }

    /** Copies more than a year apart are different films; one with no year joins the first. */
    private fun <T> byYear(copies: List<T>, year: (T) -> Int?): List<List<T>> {
        if (copies.size < 2) return listOf(copies)
        val parts = ArrayList<Pair<Int?, MutableList<T>>>()
        val unknown = ArrayList<T>()
        for (c in copies) {
            val y = year(c) ?: run { unknown += c; null } ?: continue
            val part = parts.firstOrNull { (py, _) -> py != null && kotlin.math.abs(py - y) <= 1 }
            if (part != null) part.second += c else parts += y to mutableListOf(c)
        }
        if (parts.isEmpty()) return listOf(copies)
        parts.first().second.addAll(unknown)
        return parts.map { it.second }
    }
}

/** The title key this copy groups under (rows saved before titleKey existed fall back to parsing). */
val MovieEntity.groupKey get() = titleKey.ifEmpty { SeriesTitles.parse(name).key }

/** This copy's language (rows saved before lang existed fall back to the title). */
val MovieEntity.language get() = lang ?: SeriesTitles.parse(name).lang ?: SeriesTitles.ORIGINAL

val MovieEntity.releaseYear get() = year ?: MovieTitles.yearIn(name)
