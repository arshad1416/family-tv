package app.familytv.user

import android.content.Context
import app.familytv.FamilyTvApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The TV's Appearance switch, in the order Select steps through it. */
enum class Appearance(val label: String) { LIGHT("Light"), AUTO("Auto"), DARK("Dark") }

/** A place for the weather: Open-Meteo is sent only its coordinates. */
data class WeatherPlace(val name: String, val country: String, val lat: Double, val lon: Double)

/** One world clock: the city shown, its country and its time zone. */
data class WorldClock(val city: String, val country: String, val zone: String)

/**
 * The TV home's own choices: Appearance, the weather's city and the world clocks (off until
 * chosen, at most [MAX_CLOCKS]). Kept on this TV, through sign-outs, like Stream quality.
 */
class GlassPrefs(app: FamilyTvApp) {
    private val prefs = app.getSharedPreferences("glass", Context.MODE_PRIVATE)

    private val _appearance = MutableStateFlow(prefs.getString(APPEARANCE, null)?.let { n -> Appearance.entries.firstOrNull { it.name == n } } ?: Appearance.AUTO)
    val appearance: StateFlow<Appearance> = _appearance

    fun setAppearance(a: Appearance) {
        prefs.edit().putString(APPEARANCE, a.name).apply()
        _appearance.value = a
    }

    private val _place = MutableStateFlow(readPlace())
    val place: StateFlow<WeatherPlace> = _place

    private fun readPlace(): WeatherPlace {
        val parts = prefs.getString(PLACE, null)?.split(SEP)
        val lat = parts?.getOrNull(2)?.toDoubleOrNull()
        val lon = parts?.getOrNull(3)?.toDoubleOrNull()
        return if (parts == null || lat == null || lon == null) TORONTO else WeatherPlace(parts[0], parts[1], lat, lon)
    }

    fun setPlace(p: WeatherPlace) {
        prefs.edit().putString(PLACE, listOf(p.name, p.country, p.lat, p.lon).joinToString(SEP)).remove(WEATHER).apply()
        _place.value = p
    }

    // Whether "Show weather for where you are?" has been answered; a TV that already chose a city has.
    private val _placeAsked = MutableStateFlow(prefs.getBoolean(PLACE_ASKED, prefs.contains(PLACE)))
    val placeAsked: StateFlow<Boolean> = _placeAsked

    fun setPlaceAsked() {
        prefs.edit().putBoolean(PLACE_ASKED, true).apply()
        _placeAsked.value = true
    }

    private val _clocksOn = MutableStateFlow(prefs.getBoolean(CLOCKS_ON, false))
    val clocksOn: StateFlow<Boolean> = _clocksOn

    fun setClocksOn(on: Boolean) {
        prefs.edit().putBoolean(CLOCKS_ON, on).apply()
        _clocksOn.value = on
    }

    private val _clocks = MutableStateFlow(readClocks())
    val clocks: StateFlow<List<WorldClock>> = _clocks

    private fun readClocks(): List<WorldClock> = prefs.getString(CLOCKS, null)?.split(ROW)?.mapNotNull { row ->
        row.split(SEP).takeIf { it.size == 3 }?.let { WorldClock(it[0], it[1], it[2]) }
    } ?: DEFAULT_CLOCKS

    fun setClocks(list: List<WorldClock>) {
        val kept = list.take(MAX_CLOCKS)
        prefs.edit().putString(CLOCKS, kept.joinToString(ROW) { listOf(it.city, it.country, it.zone).joinToString(SEP) }).apply()
        _clocks.value = kept
    }

    /** The last Open-Meteo answer for [place] and when it came, so the panel opens at once and asks at most every 30 minutes. */
    fun cachedWeather(): Pair<Long, String>? {
        val body = prefs.getString(WEATHER, null) ?: return null
        return prefs.getLong(WEATHER_AT, 0) to body
    }

    fun saveWeather(at: Long, body: String) {
        prefs.edit().putString(WEATHER, body).putLong(WEATHER_AT, at).apply()
    }

    companion object {
        const val MAX_CLOCKS = 4
        val TORONTO = WeatherPlace("Toronto", "Canada", 43.65, -79.38)
        /** Ticked when the chooser first opens: the family's own countries, as in the mockup. */
        val DEFAULT_CLOCKS = listOf(
            WorldClock("Toronto", "Canada", "America/Toronto"),
            WorldClock("Karachi", "Pakistan", "Asia/Karachi"),
            WorldClock("Dubai", "United Arab Emirates", "Asia/Dubai"),
            WorldClock("London", "United Kingdom", "Europe/London"),
        )
        private const val APPEARANCE = "appearance"
        private const val PLACE = "weather_place"
        private const val PLACE_ASKED = "weather_place_asked"
        private const val CLOCKS_ON = "clocks_on"
        private const val CLOCKS = "clocks"
        private const val WEATHER = "weather_body"
        private const val WEATHER_AT = "weather_at"
        private const val SEP = "\u001f"
        private const val ROW = "\u001e"
    }
}
