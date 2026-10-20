package app.familytv.ui

import app.familytv.weather.Forecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class GlassPanelTest {
    private val toronto = ZoneId.of("America/Toronto")
    private val october = Instant.parse("2026-10-20T16:00:00Z")

    @Test
    fun hourDifferences() {
        assertEquals("local", hourDifference(toronto, toronto, october))
        assertEquals("+9 h", hourDifference(ZoneId.of("Asia/Karachi"), toronto, october))
        assertEquals("+9½ h", hourDifference(ZoneId.of("Asia/Kolkata"), toronto, october))
        assertEquals("−3 h", hourDifference(ZoneId.of("America/Vancouver"), toronto, october))
        assertEquals("same time", hourDifference(ZoneId.of("America/New_York"), toronto, october))
    }

    @Test
    fun everyClockCountryHasRealZones() {
        CLOCK_COUNTRIES.forEach { c ->
            c.cities.forEach { (_, zone) -> ZoneId.of(zone) }
        }
        assertEquals(CLOCK_COUNTRIES.size, CLOCK_COUNTRIES.map { it.name }.distinct().size)
    }

    @Test
    fun nextSunIsSunsetByDaySunriseByNight() {
        val f = Forecast(10, 0, true, null, null, sunrises = listOf(100L, 1_000L), sunsets = listOf(500L, 1_500L), days = emptyList())
        assertEquals("Sunset" to 500L, nextSun(f, 200L))
        assertEquals("Sunrise" to 1_000L, nextSun(f, 600L))
        assertNull(nextSun(f, 2_000L))
    }
}
