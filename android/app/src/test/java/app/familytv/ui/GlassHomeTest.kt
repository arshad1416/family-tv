package app.familytv.ui

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import app.familytv.FamilyTvApp
import app.familytv.data.CategoryEntity
import app.familytv.data.ChannelEntity
import app.familytv.data.Kind
import app.familytv.data.MovieEntity
import app.familytv.portal.DeviceConfig
import app.familytv.portal.Profile
import app.familytv.user.Appearance
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
        fun channel(id: String, name: String, cat: String, num: Int) = ChannelEntity("s", id, name, num, null, cat, null, null, 0, 0)
        dao.upsertChannels(listOf(channel("100", "BBC News", "1", 101), channel("200", "NHL Network", "2", 201)))
        fun movie(id: String, name: String, cat: String, added: Long) = MovieEntity("s", id, name, null, cat, "8.1", "mp4", null, added, 0, titleKey = name.lowercase())
        dao.upsertMovies(listOf(movie("1000", "Star Wars", "10", 2), movie("1001", "The Irishman", "11", 1)))
        // A fresh saved forecast, so the panel never asks Open-Meteo during tests.
        app.glassPrefs.saveWeather(System.currentTimeMillis(), WeatherTest.SAMPLE)
    }

    private fun home() = rule.setContent { HomeScreen(MainViewModel(app), DeviceConfig(paired = true, profile = Profile())) }

    private fun waitFor(text: String) = rule.waitUntilAtLeastOneExists(hasText(text), 10_000)

    @Test
    fun opensOnMoviesWithItsShelves() {
        home()
        waitFor("Search movies")
        waitFor("By language and country")
        waitFor("English")
        waitFor("Recently added")
        waitFor("Top rated")
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
    fun sportsGroupsChannelsUnderTheirSport() {
        home()
        waitFor("Sports")
        rule.onNodeWithText("Sports").performClick()
        waitFor("Hockey")
        waitFor("NHL Network")
    }

    @Test
    fun moviesSearchFindsMovies() {
        home()
        waitFor("Search movies")
        rule.onNode(hasSetTextAction()).performTextInput("star")
        waitFor("Star Wars")
    }

    @Test
    fun appearanceSwitchStepsAndIsSaved() {
        home()
        waitFor("Search movies")
        rule.onNodeWithContentDescription("Appearance: Auto").performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("Appearance: Dark"), 10_000)
        assertEquals(Appearance.DARK, app.glassPrefs.appearance.value)
        rule.onNodeWithContentDescription("Appearance: Dark").performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("Appearance: Light"), 10_000)
    }

    @Test
    fun weatherPanelShowsTheWeatherAndOptionalClocks() {
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
