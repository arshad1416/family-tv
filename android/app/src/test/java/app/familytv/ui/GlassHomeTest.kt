package app.familytv.ui

import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import app.familytv.FamilyTvApp
import app.familytv.data.CategoryEntity
import app.familytv.data.ChannelEntity
import app.familytv.data.Kind
import app.familytv.data.MovieEntity
import app.familytv.data.ProgrammeEntity
import app.familytv.portal.DeviceConfig
import app.familytv.portal.Profile
import app.familytv.portal.Source
import app.familytv.user.Appearance
import app.familytv.user.ChannelRef
import app.familytv.user.ProgressEntity
import app.familytv.user.ProgressKey
import app.familytv.weather.WeatherTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import kotlin.math.abs

/** The TV's glass home on a small catalog: the tabs open their pages and each tab's search finds its own kind. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = FamilyTvApp::class,
    qualifiers = "w960dp-h540dp-land-television",
    shadows = [GlassHomeTest.LazyWorkManager::class],
    instrumentedPackages = ["androidx.work"],
)
class GlassHomeTest {
    @get:Rule val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<FamilyTvApp>()

    @Before
    fun catalog() = runBlocking {
        val dao = app.db.catalog()
        fun cat(kind: String, id: String, name: String, sort: Int) = CategoryEntity("s", kind, id, name, sort, 0)
        dao.upsertCategories(
            listOf(
                cat(Kind.LIVE, "1", "UK| NEWS ᴴᴰ", 1),
                cat(Kind.LIVE, "2", "CA| SPORTS EN", 2),
                cat(Kind.MOVIE, "10", "EN - ACTION", 1),
                cat(Kind.MOVIE, "11", "NETFLIX MOVIES", 2),
            ),
        )
        fun channel(id: String, name: String, cat: String, num: Int, epg: String? = null) = ChannelEntity("s", id, name, num, null, cat, epg, null, 0, 0)
        dao.upsertChannels(listOf(channel("100", "BBC News", "1", 101, epg = "bbc.uk"), channel("200", "NHL Network", "2", 201)))
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        fun show(title: String, start: Long, about: String? = null) = ProgrammeEntity(epgId = "bbc.uk", start = start, stop = start + hour, title = title, description = about)
        dao.insertProgrammes(
            listOf(
                show("Newsday", now - hour / 2, about = "The latest headlines from around the world."),
                show("World Business Report", now + hour / 2),
                show("Click", now + hour * 3 / 2),
            ),
        )
        fun movie(id: String, name: String, cat: String, added: Long) = MovieEntity("s", id, name, null, cat, "8.1", "mp4", null, added, 0, titleKey = name.lowercase())
        dao.upsertMovies(listOf(movie("1000", "Star Wars", "10", 2), movie("1001", "The Irishman", "11", 1)))
        // A fresh saved forecast, so the panel never asks Open-Meteo during tests.
        app.glassPrefs.saveWeather(System.currentTimeMillis(), WeatherTest.SAMPLE)
    }

    private fun home() = rule.setContent { HomeScreen(MainViewModel(app), DeviceConfig(paired = true, profile = Profile())) }

    // Generous: the first test in a busy run also pays for Robolectric, Compose and Room warming up.
    private fun waitFor(text: String) = rule.waitUntilAtLeastOneExists(hasText(text), 20_000)

    @Test
    fun opensOnMoviesWithItsShelves() {
        home()
        waitFor("Search movies")
        waitFor("Languages")
        waitFor("English")
        waitFor("Recently added")
        waitFor("Top rated")
    }

    @Test
    fun continueWatchingLeadsWithTimeLeftAndCategoriesStartFolded() = runBlocking {
        app.userDb.user().upsertProgress(
            ProgressEntity(ProgressKey.movie("s", "1001"), ProgressKey.MOVIE, "s", "1001", positionMs = 3_000_000, durationMs = 6_000_000, percent = 50f, updatedAt = 1),
        )
        home()
        waitFor("Continue watching")
        waitFor("50 min left")
        // Directly under the highlights, above Providers.
        val cw = rule.onNodeWithText("Continue watching").fetchSemanticsNode().boundsInRoot
        val providers = rule.onAllNodes(hasText("Providers")).fetchSemanticsNodes()
        providers.forEach { assertTrue("Continue watching $cw above Providers ${it.boundsInRoot}", cw.top < it.boundsInRoot.top) }
        // The category chips wait behind one pill.
        val page = rule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().indices.maxBy {
            rule.onAllNodes(hasScrollToNodeAction())[it].fetchSemanticsNode().boundsInRoot.height
        }
        rule.onAllNodes(hasScrollToNodeAction())[page].performScrollToNode(hasText("Show all categories (2)"))
        rule.onNodeWithText("EN - ACTION").assertDoesNotExist()
        rule.onNodeWithText("Show all categories (2)").performClick()
        waitFor("EN - ACTION")
        waitFor("Hide categories")
    }

    @Test
    fun liveTvListsCategoriesCountriesAndChannels() {
        home()
        waitFor("Live TV")
        rule.onNodeWithText("Live TV").performClick()
        waitFor("Search channels")
        waitFor("CATEGORY")
        waitFor("News")
        waitFor("UK")
        waitFor("BBC News")
        waitFor("TV guide")
    }

    @Test
    fun liveTvShowsRecentlyWatchedNewestFirst() {
        val line = Profile(sources = listOf(Source("s", "xtream", "Line")))
        app.lastChannels.played(line, ChannelRef("s", "1", "100"))
        app.lastChannels.played(line, ChannelRef("s", "2", "200"))
        rule.setContent { HomeScreen(MainViewModel(app), DeviceConfig(paired = true, profile = line)) }
        waitFor("Live TV")
        rule.onNodeWithText("Live TV").performClick()
        waitFor("RECENTLY WATCHED")
        // NHL Network played last, so its pill comes first, left of BBC News's.
        // News is the open category, so NHL Network is only in the row.
        val nhl = rule.onNode(hasText("NHL Network") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val bbc = rule.onAllNodes(hasText("BBC News") and hasClickAction()).fetchSemanticsNodes().map { it.boundsInRoot }.single { abs(it.top - nhl.top) < 1f }
        assertTrue("NHL $nhl before BBC $bbc", nhl.right <= bbc.left)
    }

    @Test
    fun liveTvHasNoRecentRowBeforeAnythingPlayed() {
        home()
        waitFor("Live TV")
        rule.onNodeWithText("Live TV").performClick()
        waitFor("BBC News")
        rule.onNodeWithText("RECENTLY WATCHED").assertDoesNotExist()
    }

    @Test
    fun okOnACountryMovesIntoItsChannels() {
        home()
        waitFor("Live TV")
        rule.onNodeWithText("Live TV").performClick()
        waitFor("BBC News")
        rule.onNode(hasText("News") and hasClickAction()).requestFocus()
        rule.waitUntilAtLeastOneExists(isFocused() and hasText("News"), 10_000)
        press(Key.DirectionRight)
        rule.waitUntilAtLeastOneExists(isFocused() and hasText("UK"), 10_000)
        press(Key.DirectionCenter)
        rule.waitUntilAtLeastOneExists(isFocused() and hasText("BBC News"), 10_000)
    }

    @Test
    fun liveTvGuideBoxShowsNowAndNext() {
        home()
        waitFor("Live TV")
        rule.onNodeWithText("Live TV").performClick()
        waitFor("GUIDE · BBC NEWS")
        waitFor("The latest headlines from around the world.")
        waitFor("World Business Report")
        waitFor("Click")
    }

    @Test
    fun sportsGroupsChannelsUnderTheirSport() {
        home()
        waitFor("Sports")
        rule.onNodeWithText("Sports").performClick()
        waitFor("Hockey")
        waitFor("NHL Network")
        // No guide, so nothing says it's live.
        rule.onNodeWithText("LIVE").assertDoesNotExist()
    }

    @Test
    fun moviesSearchFindsMovies() {
        home()
        waitFor("Search movies")
        // OK (a click) starts typing; until then the field can't take focus.
        rule.onNodeWithText("Search movies").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("star")
        waitFor("Star Wars")
    }

    @Test
    fun arrowsPassOverTheSearchBox() {
        home()
        waitFor("Search movies")
        val box = rule.onNode(hasText("Search movies") and hasClickAction())
        box.requestFocus()
        box.performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        box.assertIsNotFocused()
        rule.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun rightStepsFromCountryToTheChannels() = runBlocking {
        // Canada comes first, so UK's row sits below the top of the channel list.
        app.db.catalog().upsertCategories(listOf(CategoryEntity("s", Kind.LIVE, "3", "CA| NEWS EN", 3, 0)))
        app.db.catalog().upsertChannels(listOf(ChannelEntity("s", "300", "CBC News", 301, null, "3", null, null, 0, 0)))
        home()
        waitFor("Live TV")
        rule.onNodeWithText("Live TV").performClick()
        waitFor("CBC News")
        // Into the page on News, its entry point; then only the arrows, as on a remote.
        rule.onNode(hasText("News") and hasClickAction()).requestFocus()
        rule.waitUntilAtLeastOneExists(isFocused() and hasText("News"), 10_000)
        press(Key.DirectionRight)
        rule.waitUntilAtLeastOneExists(isFocused() and hasText("Canada"), 10_000)
        press(Key.DirectionDown)
        rule.waitForIdle()
        rule.waitUntilAtLeastOneExists(isFocused() and hasText("UK"), 10_000)
        waitFor("BBC News")
        press(Key.DirectionRight)
        rule.waitUntilAtLeastOneExists(isFocused() and hasText("BBC News"), 10_000)
        press(Key.DirectionLeft)
        rule.waitForIdle()
        rule.waitUntilAtLeastOneExists(isFocused() and hasText("UK"), 10_000)
    }

    private fun press(key: Key) {
        rule.waitForIdle()
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
    }

    @Test
    fun tabsOpenOnOkNotOnFocus() {
        home()
        waitFor("Recently added")
        rule.onNode(hasText("TV Shows") and hasClickAction()).requestFocus()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        // Still Movies: its own search box is still there.
        rule.onNodeWithText("Search movies").assertExists()
        rule.onNode(hasText("TV Shows") and hasClickAction()).performClick()
        waitFor("Search TV shows")
    }

    @Test
    fun screensaverComesAfterFiveIdleMinutesAndAnyKeyWakesIt() {
        home()
        waitFor("Recently added")
        rule.onNode(hasText("Movies") and hasClickAction()).requestFocus()
        rule.waitForIdle()
        rule.onNodeWithText("Press any button to wake").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(SCREENSAVER_IDLE_MS + 30_000)
        waitFor("Press any button to wake")
        // The waking key goes no further: still on Movies, nothing opened.
        press(Key.DirectionDown)
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Press any button to wake")).fetchSemanticsNodes().isEmpty() }
        rule.onNode(isFocused() and hasText("Movies")).assertExists()
    }

    @Test
    fun topBarButtonsDontOverlapOnMovies() {
        home()
        waitFor("Search movies")
        val switch = rule.onNodeWithContentDescription("Appearance: Auto").fetchSemanticsNode().boundsInRoot
        val gear = rule.onNodeWithContentDescription("Settings").fetchSemanticsNode().boundsInRoot
        assertTrue("switch $switch overlaps Settings $gear", switch.right <= gear.left)
        // 96 x 44 when it has its room.
        assertTrue("switch is squeezed: $switch", switch.width > switch.height * 2)
    }

    @Test
    fun backOnTheTabsAsksBeforeLeaving() {
        var back: OnBackPressedDispatcher? = null
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            HomeScreen(MainViewModel(app), DeviceConfig(paired = true, profile = Profile()))
        }
        fun pressBack() = rule.runOnUiThread { back!!.onBackPressed() }
        waitFor("Search movies")
        rule.onNode(hasText("Movies") and hasClickAction()).requestFocus()
        rule.waitForIdle()
        pressBack()
        waitFor("Exit Family TV?")
        rule.waitUntilAtLeastOneExists(isFocused() and hasText("Stay"), 10_000)
        // Back again stays.
        pressBack()
        rule.waitForIdle()
        rule.onNodeWithText("Exit Family TV?").assertDoesNotExist()
    }

    @Test
    fun appearancePickerChoosesAndIsSaved() {
        home()
        waitFor("Search movies")
        rule.onNodeWithContentDescription("Appearance: Auto").performClick()
        // OK opens the picker with Auto, the current one, focused.
        rule.waitUntilAtLeastOneExists(isFocused() and hasText("Auto"), 10_000)
        rule.onNode(hasText("Dark") and hasClickAction()).performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("Appearance: Dark"), 10_000)
        assertEquals(Appearance.DARK, app.glassPrefs.appearance.value)
        rule.onNodeWithText("Light").assertDoesNotExist()
    }

    @Test
    fun arrowsLeaveTheAppearanceSwitchAlone() {
        home()
        waitFor("Search movies")
        val switch = rule.onNodeWithContentDescription("Appearance: Auto")
        switch.requestFocus()
        switch.performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        assertEquals(Appearance.AUTO, app.glassPrefs.appearance.value)
        rule.onNodeWithContentDescription("Appearance: Auto").assertIsNotFocused()
    }

    @Test
    fun weatherPanelAsksWhereFirst() {
        home()
        waitFor("Weather and clocks")
        rule.onNodeWithText("Weather and clocks").performClick()
        waitFor("Show weather for where you are?")
        waitFor("Use my location")
        rule.onNodeWithText("Enter a city").performClick()
        waitFor("Search a city")
        assertTrue(app.glassPrefs.placeAsked.value)
    }

    @Test
    fun weatherPanelShowsTheWeatherAndOptionalClocks() {
        app.glassPrefs.setPlaceAsked()
        home()
        waitFor("Weather and clocks")
        rule.onNodeWithText("Weather and clocks").performClick()
        waitFor("12°")
        waitFor("Partly cloudy")
        waitFor("World clocks")
        waitFor("Switch on Show world clocks to see the time in up to 4 countries.")
        rule.onNodeWithText("Show world clocks").performClick()
        waitFor("Karachi")
        assertTrue(app.glassPrefs.clocksOn.value)
    }

    /** The app schedules its background check-in as it starts; Robolectric doesn't run WorkManager's own start-up. */
    @Implements(WorkManager::class)
    class LazyWorkManager {
        companion object {
            @JvmStatic
            @Implementation
            @Suppress("RestrictedApi")
            fun getInstance(context: Context): WorkManager {
                if (WorkManagerImpl.getInstance() == null) WorkManager.initialize(context, Configuration.Builder().build())
                return WorkManagerImpl.getInstance()!!
            }
        }
    }
}
