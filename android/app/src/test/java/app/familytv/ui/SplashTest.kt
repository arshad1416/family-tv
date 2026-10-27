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
        assertEquals(R.raw.the_nook_splash_halloween, Splash.video(Splash.NOOK, 10))
        assertEquals(R.raw.family_tv_splash_fall, Splash.video(null, 11))
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
        // October plays the Halloween video too.
        assertEquals(R.raw.the_nook_splash_halloween, Splash.choice(Splash.NOOK, 10).video)
        assertEquals(R.raw.family_tv_splash_halloween, Splash.choice(null, 10).video)
        // Its stand-in video is the one the month and profile would have played.
        assertEquals(R.raw.the_nook_splash_summer, Splash.choice(Splash.NOOK, 7).video)
        assertEquals(R.raw.family_tv_splash_spring, Splash.choice(null, 4).video)
    }

    @Test
    fun theRiveSplashRendersAt720pAndScalesUp() {
        assertEquals(1.5f, Splash.riveScale(1920, 1080), 0f)
        assertEquals(3f, Splash.riveScale(3840, 2160), 0f)
        assertEquals(1f, Splash.riveScale(1280, 720), 0f)
        // By the short side, so a phone held upright isn't drawn tiny.
        assertEquals(1.5f, Splash.riveScale(1080, 2400), 0f)
        // A smaller screen draws at its own size, never scaled down.
        assertEquals(1f, Splash.riveScale(854, 480), 0f)
    }
}
