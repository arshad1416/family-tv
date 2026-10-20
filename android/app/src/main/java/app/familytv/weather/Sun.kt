package app.familytv.weather

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/** Sunrise and sunset, for Appearance's Auto (dark from sunset to sunrise). Pure, unit tested. */
object Sun {
    private const val J2000_EPOCH_DAY = 10_957L
    private const val UNIX_JULIAN = 2_440_587.5

    /**
     * [date]'s sunrise and sunset (epoch millis) at [lat]/[lon] (east positive), by the sunrise
     * equation: good to a few minutes. Null where the sun doesn't rise or set that day.
     */
    fun times(date: LocalDate, lat: Double, lon: Double): Pair<Long, Long>? {
        val n = (date.toEpochDay() - J2000_EPOCH_DAY).toDouble()
        val j = n - lon / 360
        val m = (357.5291 + 0.98560028 * j).mod(360.0)
        val c = 1.9148 * sin(rad(m)) + 0.02 * sin(rad(2 * m)) + 0.0003 * sin(rad(3 * m))
        val lambda = (m + c + 180 + 102.9372).mod(360.0)
        val transit = 2_451_545.0 + j + 0.0053 * sin(rad(m)) - 0.0069 * sin(rad(2 * lambda))
        val sinDec = sin(rad(lambda)) * sin(rad(23.4397))
        val cosDec = cos(asin(sinDec))
        val cosW = (sin(rad(-0.833)) - sin(rad(lat)) * sinDec) / (cos(rad(lat)) * cosDec)
        if (cosW !in -1.0..1.0) return null
        val w = Math.toDegrees(acos(cosW)) / 360
        return millis(transit - w) to millis(transit + w)
    }

    /**
     * Whether [now] is between sunset and sunrise. The forecast's own times win while they cover
     * [now]; otherwise [lat]/[lon] are worked out, and 18:00 to 06:00 where the sun won't set.
     */
    fun isDark(now: Long, zone: ZoneId, lat: Double, lon: Double, sunrises: List<Long> = emptyList(), sunsets: List<Long> = emptyList()): Boolean {
        val pairs = sunrises.zip(sunsets)
        if (pairs.isNotEmpty() && now >= pairs.first().first - HALF_DAY && now < pairs.last().second) {
            return pairs.none { (rise, set) -> now in rise until set }
        }
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val (rise, set) = times(local.toLocalDate(), lat, lon) ?: return local.hour < 6 || local.hour >= 18
        return now < rise || now >= set
    }

    private const val HALF_DAY = 12 * 3_600_000L

    private fun rad(deg: Double) = Math.toRadians(deg)

    private fun millis(julian: Double) = ((julian - UNIX_JULIAN) * 86_400_000).toLong()
}
