package app.familytv.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

class WeatherTest {
    private val toronto = ZoneId.of("America/Toronto")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, toronto).toInstant().toEpochMilli()

    private fun assertNear(expected: Long, actual: Long, minutes: Int) =
        assertTrue("off by ${(actual - expected) / 60_000} min", abs(actual - expected) <= minutes * 60_000L)

    @Test
    fun torontoSunTimesAreWithinMinutes() {
        val (rise, set) = Sun.times(LocalDate.of(2026, 10, 20), 43.65, -79.38)!!
        assertNear(at(2026, 10, 20, 7, 36), rise, 6)
        assertNear(at(2026, 10, 20, 18, 23), set, 6)
        val (summerRise, summerSet) = Sun.times(LocalDate.of(2026, 6, 21), 43.65, -79.38)!!
        assertNear(at(2026, 6, 21, 5, 36), summerRise, 6)
        assertNear(at(2026, 6, 21, 21, 3), summerSet, 6)
    }

    @Test
    fun noSunsetInThePolarSummer() {
        assertNull(Sun.times(LocalDate.of(2026, 6, 21), 78.2, 15.6))
    }

    @Test
    fun autoIsDarkFromSunsetToSunrise() {
        assertFalse(Sun.isDark(at(2026, 10, 20, 12, 0), toronto, 43.65, -79.38))
        assertTrue(Sun.isDark(at(2026, 10, 20, 19, 0), toronto, 43.65, -79.38))
        assertTrue(Sun.isDark(at(2026, 10, 20, 6, 0), toronto, 43.65, -79.38))
    }

    @Test
    fun theForecastsOwnTimesWinWhileTheyCoverNow() {
        val rises = listOf(at(2026, 10, 20, 7, 0), at(2026, 10, 21, 7, 0))
        val sets = listOf(at(2026, 10, 20, 20, 0), at(2026, 10, 21, 20, 0))
        // 19:00 is after the worked-out sunset but before the forecast's.
        assertFalse(Sun.isDark(at(2026, 10, 20, 19, 0), toronto, 43.65, -79.38, rises, sets))
        assertTrue(Sun.isDark(at(2026, 10, 20, 21, 0), toronto, 43.65, -79.38, rises, sets))
        // A week-old forecast no longer counts.
        assertTrue(Sun.isDark(at(2026, 10, 28, 19, 0), toronto, 43.65, -79.38, rises, sets))
    }

    @Test
    fun readsTheForecast() {
        val f = WeatherParse.forecast(SAMPLE)!!
        assertEquals(12, f.temp)
        assertEquals(2, f.code)
        assertTrue(f.isDay)
        assertEquals(15, f.high)
        assertEquals(7, f.low)
        assertEquals(-4 * 3600, f.offsetSeconds)
        assertEquals(LocalDateTime.of(2026, 10, 20, 18, 22).atZone(toronto).toInstant().toEpochMilli(), f.sunsets.first())
        assertEquals(5, f.days.size)
        assertEquals(ForecastDay(LocalDate.of(2026, 10, 21), 0, 16, 8), f.days.first())
        assertNull(WeatherParse.forecast("not json"))
    }

    @Test
    fun readsPlaceSearch() {
        val hits = WeatherParse.places("""{"results":[{"name":"London","latitude":42.98,"longitude":-81.23,"country":"Canada","admin1":"Ontario"},{"name":"Nowhere"}]}""")
        assertEquals(1, hits.size)
        assertEquals("London", hits[0].place.name)
        assertEquals("Ontario", hits[0].region)
        assertEquals(emptyList<PlaceHit>(), WeatherParse.places("{}"))
    }

    @Test
    fun weatherCodesHaveWordsAndIcons() {
        assertEquals("Partly cloudy", WeatherParse.condition(2))
        assertEquals("🌙", WeatherParse.icon(0, day = false))
        assertNotNull(WeatherParse.icon(95))
    }

    companion object {
        val SAMPLE = """
            {"latitude":43.65,"longitude":-79.38,"utc_offset_seconds":-14400,"timezone":"America/Toronto",
             "current":{"time":"2026-10-20T14:00","temperature_2m":12.4,"weather_code":2,"is_day":1},
             "daily":{"time":["2026-10-20","2026-10-21","2026-10-22","2026-10-23","2026-10-24","2026-10-25"],
               "weather_code":[2,0,61,2,0,3],
               "temperature_2m_max":[15.1,16.0,11.2,13.0,14.4,12.0],
               "temperature_2m_min":[7.0,8.2,6.0,5.1,6.3,7.0],
               "sunrise":["2026-10-20T07:36","2026-10-21T07:37","2026-10-22T07:38","2026-10-23T07:40","2026-10-24T07:41","2026-10-25T07:42"],
               "sunset":["2026-10-20T18:22","2026-10-21T18:21","2026-10-22T18:19","2026-10-23T18:18","2026-10-24T18:16","2026-10-25T18:15"]}}
        """.trimIndent()
    }
}
