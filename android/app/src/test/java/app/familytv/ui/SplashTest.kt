package app.familytv.ui

import app.familytv.R
import app.familytv.ui.Splash.Season
import org.junit.Assert.assertEquals
import org.junit.Test

class SplashTest {
    @Test
    fun seasonFollowsTheMonth() {
        val byMonth = (1..12).map(Splash::season)
        assertEquals(
            listOf(Season.WINTER, Season.WINTER, Season.SPRING, Season.SPRING, Season.SPRING, Season.SUMMER,
                Season.SUMMER, Season.SUMMER, Season.FALL, Season.FALL, Season.FALL, Season.WINTER),
            byMonth,
        )
    }

    @Test
    fun theNookOnlyOnTheAirbnbProfile() {
        assertEquals(R.raw.the_nook_splash_fall, Splash.video(Splash.NOOK, 10))
        assertEquals(R.raw.family_tv_splash_fall, Splash.video(null, 10))
        assertEquals(R.raw.family_tv_splash_winter, Splash.video("something-else", 1))
        assertEquals(R.raw.the_nook_splash_summer, Splash.video(Splash.NOOK, 7))
    }
}
