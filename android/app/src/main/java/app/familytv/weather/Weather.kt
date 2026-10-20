package app.familytv.weather

import app.familytv.user.WeatherPlace
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToInt

/** One day in the 5-day row. */
data class ForecastDay(val date: LocalDate, val code: Int, val high: Int, val low: Int)

/**
 * The weather panel's numbers, in °C. [sunrises] and [sunsets] are today's and the next days'
 * (epoch millis), which also drive Appearance's Auto once loaded.
 */
data class Forecast(
    val temp: Int,
    val code: Int,
    val isDay: Boolean,
    val high: Int?,
    val low: Int?,
    val sunrises: List<Long>,
    val sunsets: List<Long>,
    val days: List<ForecastDay>,
    /** The city's offset from UTC, to show its sunrise and sunset on its own clock. */
    val offsetSeconds: Int = 0,
)

/** A place found by "Change city", with its region to tell same-named towns apart. */
data class PlaceHit(val place: WeatherPlace, val region: String?)

/**
 * Open-Meteo (free, no key): the forecast is asked with coordinates only, and "Change city"
 * looks names up in its place search. Nothing about the family or the TV is sent.
 */
class OpenMeteo(private val client: OkHttpClient, private val forecastBase: String = FORECAST_URL, private val searchBase: String = SEARCH_URL) {

    /** The raw answer for [place], cached as it came so the panel can show it again offline. */
    suspend fun forecastBody(place: WeatherPlace): String = get(
        forecastBase,
        listOf(
            "latitude" to "%.4f".format(Locale.ROOT, place.lat),
            "longitude" to "%.4f".format(Locale.ROOT, place.lon),
            "current" to "temperature_2m,weather_code,is_day",
            "daily" to "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset",
            "timezone" to "auto",
            "forecast_days" to "6",
        ),
    )

    suspend fun search(name: String): List<PlaceHit> =
        WeatherParse.places(get(searchBase, listOf("name" to name, "count" to "8", "language" to "en", "format" to "json")))

    private suspend fun get(base: String, params: List<Pair<String, String>>): String = withContext(Dispatchers.IO) {
        val url = base.toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Open-Meteo HTTP ${res.code}")
            res.body?.string().orEmpty()
        }
    }

    companion object {
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        const val SEARCH_URL = "https://geocoding-api.open-meteo.com/v1/search"
    }
}

/** Reads Open-Meteo answers. Pure, unit tested. */
object WeatherParse {
    fun forecast(body: String): Forecast? = runCatching {
        val o = JsonParser.parseString(body).asJsonObject
        val offset = ZoneOffset.ofTotalSeconds(o.get("utc_offset_seconds")?.asInt ?: 0)
        val now = o.getAsJsonObject("current")
        val daily = o.getAsJsonObject("daily")
        val dates = daily.array("time").map { LocalDate.parse(it.asString) }
        val codes = daily.array("weather_code").map { it.int() }
        val highs = daily.array("temperature_2m_max").map { it.int() }
        val lows = daily.array("temperature_2m_min").map { it.int() }
        fun times(key: String) = daily.array(key).mapNotNull { t ->
            t.takeIf { it.isJsonPrimitive }?.asString?.let { LocalDateTime.parse(it).toInstant(offset).toEpochMilli() }
        }
        val days = dates.indices.drop(1).take(5).mapNotNull { i ->
            val c = codes.getOrNull(i) ?: return@mapNotNull null
            val h = highs.getOrNull(i) ?: return@mapNotNull null
            val l = lows.getOrNull(i) ?: return@mapNotNull null
            ForecastDay(dates[i], c, h, l)
        }
        Forecast(
            temp = now.get("temperature_2m").asDouble.roundToInt(),
            code = now.get("weather_code").asInt,
            isDay = now.get("is_day")?.asInt != 0,
            high = highs.firstOrNull(),
            low = lows.firstOrNull(),
            sunrises = times("sunrise"),
            sunsets = times("sunset"),
            days = days,
            offsetSeconds = offset.totalSeconds,
        )
    }.getOrNull()

    fun places(body: String): List<PlaceHit> = runCatching {
        val results = JsonParser.parseString(body).asJsonObject.get("results")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        results.mapNotNull { r ->
            val o = r.asJsonObject
            val name = o.get("name")?.asString ?: return@mapNotNull null
            val lat = o.get("latitude")?.asDouble ?: return@mapNotNull null
            val lon = o.get("longitude")?.asDouble ?: return@mapNotNull null
            PlaceHit(WeatherPlace(name, o.get("country")?.asString.orEmpty(), lat, lon), o.get("admin1")?.asString)
        }
    }.getOrDefault(emptyList())

    /** A WMO weather code in words, for the panel. */
    fun condition(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, hail"
        else -> "—"
    }

    /** A WMO weather code as an emoji; clear skies show the moon at night. */
    fun icon(code: Int, day: Boolean = true): String = when (code) {
        0 -> if (day) "☀️" else "🌙"
        1 -> if (day) "🌤️" else "🌙"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 80, 81, 82 -> "🌦️"
        56, 57, 61, 63, 65, 66, 67 -> "🌧️"
        71, 73, 75, 85, 86 -> "🌨️"
        77 -> "❄️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }

    private fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

    private fun JsonElement.int(): Int? = takeIf { it.isJsonPrimitive }?.asDouble?.roundToInt()
}
