package app.familytv.ui

import app.familytv.R
import app.familytv.ui.Splash.Season
import org.junit.Assert.assertEquals
import org.junit.Test

class SplashTest {
    @Test
    fun seasonFollowsTheMonthWithHalloweenAllOctober() {
        val byMonth = (1..12).map(Splash::season)
        assertEquals(
            listOf(Season.WINTER, Season.WINTER, Season.SPRING, Season.SPRING, Season.SPRING, Season.SUMMER,
                Season.SUMMER, Season.SUMMER, Season.FALL, Season.HALLOWEEN, Season.FALL, Season.WINTER),
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

    @Test
    fun theRiveSplashTakesTheSameSeasonAndBrand() {
        assertEquals(Splash.Choice(Season.WINTER, nook = true), Splash.choice(Splash.NOOK, 12))
        assertEquals(Splash.Choice(Season.HALLOWEEN, nook = false), Splash.choice(null, 10))
        assertEquals(Splash.Choice(Season.FALL, nook = true), Splash.choice(Splash.NOOK, 11))
        // The Rive file's "season" input counts 0 spring to 3 winter, then 4 Halloween.
        assertEquals(listOf(0, 1, 2, 3, 4), listOf(Season.SPRING, Season.SUMMER, Season.FALL, Season.WINTER, Season.HALLOWEEN).map { it.ordinal })
        // No Halloween video: October's stand-in is fall's.
        assertEquals(R.raw.the_nook_splash_fall, Splash.choice(Splash.NOOK, 10).video)
        assertEquals(R.raw.family_tv_splash_fall, Splash.choice(null, 10).video)
        // Its stand-in video is the one the month and profile would have played.
        assertEquals(R.raw.the_nook_splash_summer, Splash.choice(Splash.NOOK, 7).video)
        assertEquals(R.raw.family_tv_splash_spring, Splash.choice(null, 4).video)
    }
}
