package app.familytv.ui

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.speech.RecognizerIntent
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import org.robolectric.Shadows.shadowOf
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

    /** The home for [profile], moved on to Movies (it opens on Home). */
    private fun home(profile: Profile = Profile()) {
        rule.setContent { HomeScreen(MainViewModel(app), DeviceConfig(paired = true, profile = profile)) }
        tab("Movies").performClick()
        waitFor("Search movies")
    }

    /** The top bar's [name] tab, not a row or button of the same name below it. */
    private fun tab(name: String): SemanticsNodeInteraction {
        val m = hasText(name) and hasClickAction()
        rule.waitUntilAtLeastOneExists(m, 20_000)
        val top = rule.onAllNodes(m).fetchSemanticsNodes().minBy { it.boundsInRoot.top }
        return rule.onNode(SemanticsMatcher("$name tab") { it.id == top.id })
    }

    // Generous: the first test in a busy run also pays for Robolectric, Compose and Room warming up.
    private fun waitFor(text: String) = rule.waitUntilAtLeastOneExists(hasText(text), 20_000)

    @Test
    fun opensOnHomeTheMainScreen() {
        rule.setContent { HomeScreen(MainViewModel(app), DeviceConfig(paired = true, profile = Profile())) }
        // Home is the film highlights, rows and providers the app has always opened on.
        waitFor("Recently added")
        waitFor("Top rated")
        assertEquals(2, rule.onAllNodes(hasText("Home")).fetchSemanticsNodes().size)
    }

    @Test
    fun moviesHasItsShelves() {
        home()
        waitFor("Languages")
        waitFor("English")
        waitFor("Recently added")
        waitFor("Top rated")
    }

    @Test
    fun continueWatchingLeadsWithTimeLeftAndCategoriesStartFolded() {
        runBlocking {
            app.userDb.user().upsertProgress(
                ProgressEntity(ProgressKey.movie("s", "1001"), ProgressKey.MOVIE, "s", "1001", positionMs = 3_000_000, durationMs = 6_000_000, percent = 50f, updatedAt = 1),
            )
        }
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
        tab("Live TV").performClick()
        waitFor("Search channels")
        waitFor("CATEGORY")
        waitFor("News")
        waitFor("UK")
        // All opens first, on Canada (it leads the countries).
        waitFor("NHL Network")
        waitFor("TV guide")
    }

    @Test
    fun liveTvShowsRecentlyWatchedNewestFirst() {
        val line = Profile(sources = listOf(Source("s", "xtream", "Line")))
        app.lastChannels.played(line, ChannelRef("s", "1", "100"))
        app.lastChannels.played(line, ChannelRef("s", "2", "200"))
        home(line)
        waitFor("Live TV")
        tab("Live TV").performClick()
        waitFor("RECENTLY WATCHED")
        // NHL Network played last, so its pill comes first, left of BBC News's (the row is above the lists).
        val nhl = rule.onAllNodes(hasText("NHL Network") and hasClickAction()).fetchSemanticsNodes().map { it.boundsInRoot }.minBy { it.top }
        val bbc = rule.onAllNodes(hasText("BBC News") and hasClickAction()).fetchSemanticsNodes().map { it.boundsInRoot }.single { abs(it.top - nhl.top) < 1f }
        assertTrue("NHL $nhl before BBC $bbc", nhl.right <= bbc.left)
    }

    @Test
    fun liveTvAllListsEveryCountry() {
        runBlocking {
            val dao = app.db.catalog()
            // A category with no genre word and a country code the app doesn't know.
            dao.upsertCategories(listOf(CategoryEntity("s", Kind.LIVE, "3", "XY| LOCAL", 3, 0)))
            dao.upsertChannels(listOf(ChannelEntity("s", "300", "Local One", 301, null, "3", null, null, 0, 0)))
        }
        home()
        tab("Live TV").performClick()
        waitFor("All Countries")
        waitFor("COUNTRY")
        waitFor("Canada")
        waitFor("UK")
        waitFor("XY")
        rule.onNode(hasText("XY") and hasClickAction()).performClick()
        waitFor("Local One")
    }

    @Test
    fun liveTvHasNoRecentRowBeforeAnythingPlayed() {
        home()
        waitFor("Live TV")
        tab("Live TV").performClick()
        waitFor("NHL Network")
        rule.onNodeWithText("RECENTLY WATCHED").assertDoesNotExist()
    }

    @Test
    fun okOnACountryMovesIntoItsChannels() {
        home()
        waitFor("Live TV")
        tab("Live TV").performClick()
        // All opens on Canada; wait for its list so nothing arriving later moves focus.
        waitFor("NHL Network")
        focusGenre("News")
        // All opens first; resting on News switches to it.
        waitFor("NEWS · COUNTRY")
        press(Key.DirectionRight)
        rule.waitUntilAtLeastOneExists(isFocused() and hasText("UK"), 10_000)
        press(Key.DirectionCenter)
        rule.waitUntilAtLeastOneExists(isFocused() and hasText("BBC News"), 10_000)
    }

    @Test
    fun comingBackFromFullScreenFocusesTheChannelLastPlaying() {
        runBlocking { app.db.catalog().upsertChannels(listOf(ChannelEntity("s", "101", "Sky News", 102, null, "1", null, null, 0, 0))) }
        val line = Profile(sources = listOf(Source("s", "xtream", "Line")))
        // The home's own start and stop, which full screen (another activity) brings.
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry.createUnsafe(this) }
        val screen = owner.lifecycle.apply { currentState = Lifecycle.State.RESUMED }
        val vm = MainViewModel(app)
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                HomeScreen(vm, DeviceConfig(paired = true, profile = line))
            }
        }
        waitFor("Live TV")
        tab("Live TV").performClick()
        // All opens on Canada; wait for its list so nothing arriving later moves focus.
        waitFor("NHL Network")
        focusGenre("News")
        // All opens first; resting on News switches to it.
        waitFor("NEWS · COUNTRY")
        press(Key.DirectionRight)
        rule.waitUntilAtLeastOneExists(isFocused() and hasText("UK"), 10_000)
        press(Key.DirectionCenter)
        rule.waitUntilAtLeastOneExists(isFocused() and hasText("BBC News"), 10_000)
        // OK plays BBC News full screen; there Up zaps to Sky News, then Back leaves.
        press(Key.DirectionCenter)
        rule.runOnUiThread { screen.currentState = Lifecycle.State.CREATED }
        app.lastChannels.played(line, ChannelRef("s", "1", "101"))
        rule.runOnUiThread { screen.currentState = Lifecycle.State.RESUMED }
        rule.waitUntilAtLeastOneExists(isFocused() and hasText("Sky News"), 10_000)
        waitFor("GUIDE · SKY NEWS")
    }

    @Test
    fun liveTvGuideBoxShowsNowAndNext() {
        home()
        waitFor("Live TV")
        tab("Live TV").performClick()
        // The genres show once the channels have loaded.
        rule.waitUntilAtLeastOneExists(hasText("News") and hasClickAction(), 20_000)
        rule.onNode(hasText("News") and hasClickAction()).performClick()
        waitFor("GUIDE · BBC NEWS")
        waitFor("The latest headlines from around the world.")
        waitFor("World Business Report")
        waitFor("Click")
    }

    @Test
    fun sportsGroupsChannelsUnderTheirSport() {
        home()
        waitFor("Sports")
        tab("Sports").performClick()
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
        // The pill itself: the merged node's focus action is the text field's, which only OK may focus.
        val box = rule.onAllNodes(hasClickAction() and hasAnyDescendant(hasText("Search movies")), useUnmergedTree = true).onFirst()
        box.requestFocus()
        box.assertIsFocused()
        box.performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        box.assertIsNotFocused()
        rule.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun theSearchPillOffersVoiceWhenTheTvCanListen() {
        val recogniser = ComponentName("com.example.speech", "com.example.speech.Recogniser")
        shadowOf(app.packageManager).addActivityIfNotPresent(recogniser)
        shadowOf(app.packageManager).addIntentFilterForActivity(recogniser, IntentFilter(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        home()
        waitFor("Search movies")
        // The page's shelves settle first: their arrival can take focus back from an early request.
        waitFor("Recently added")
        val pill = rule.onAllNodes(hasClickAction() and hasAnyDescendant(hasText("Search movies")), useUnmergedTree = true).onFirst()
        for (attempt in 1..5) {
            pill.requestFocus()
            if (runCatching { rule.waitUntilAtLeastOneExists(hasText("Hold OK to speak"), 2_000) }.isSuccess) break
        }
        waitFor("Hold OK to speak")
        // A short OK still starts typing.
        press(Key.DirectionCenter)
        rule.waitUntilAtLeastOneExists(hasSetTextAction() and isFocused(), 10_000)
    }

    @Test
    fun rightStepsFromCountryToTheChannels() {
        // Canada comes first, so UK's row sits below the top of the channel list.
        // Only the writes block: the UI steps run outside runBlocking, as in the other tests.
        runBlocking {
            app.db.catalog().upsertCategories(listOf(CategoryEntity("s", Kind.LIVE, "3", "CA| NEWS EN", 3, 0)))
            app.db.catalog().upsertChannels(listOf(ChannelEntity("s", "300", "CBC News", 301, null, "3", null, null, 0, 0)))
        }
        home()
        waitFor("Live TV")
        tab("Live TV").performClick()
        waitFor("CBC News")
        // Onto News; then only the arrows, as on a remote.
        focusGenre("News")
        // All opens first; resting on News switches to it.
        waitFor("NEWS · COUNTRY")
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

    /** Focus on Live TV's [name] genre. Coming in from the tabs lands on the page's entry point (All) first. */
    private fun focusGenre(name: String) {
        val row = hasText(name) and hasClickAction()
        rule.onNode(row).requestFocus()
        rule.waitForIdle()
        rule.onNode(row).requestFocus()
        rule.waitUntilAtLeastOneExists(isFocused() and hasText(name), 10_000)
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

    /** Arshad and Izaan (a kids viewer) on this TV, for a profile with a parental PIN. */
    private fun twoViewers(): Profile {
        val profile = Profile(sources = listOf(Source("s", "xtream", "Line")), parentalPin = "1234")
        app.viewers.add(profile, "Arshad", kids = false)
        app.viewers.add(profile, "Izaan", kids = true)
        return profile
    }

    @Test
    fun aViewerGetsTheirOwnContinueWatchingAndForYou() {
        runBlocking {
            val dao = app.db.catalog()
            dao.upsertMovies(listOf(MovieEntity("s", "1003", "Die Hard", null, "10", "8.2", "mp4", null, 3, 0, titleKey = "die hard")))
            app.userDb.user().upsertProgress(
                ProgressEntity(ProgressKey.movie("s", "1000"), ProgressKey.MOVIE, "s", "1000", positionMs = 3_000_000, durationMs = 6_000_000, percent = 50f, updatedAt = 1),
            )
        }
        val profile = twoViewers()
        val arshad = app.viewers.all().first()
        app.viewers.choose(arshad)
        app.viewers.played(ProgressKey.movie("s", "1000"))
        home(profile)
        waitFor(CONTINUE_WATCHING)
        // For you: more from the category Arshad plays, not what he played.
        val page = rule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().indices.maxBy {
            rule.onAllNodes(hasScrollToNodeAction())[it].fetchSemanticsNode().boundsInRoot.height
        }
        rule.waitUntil(20_000) { runCatching { rule.onAllNodes(hasScrollToNodeAction())[page].performScrollToNode(hasText("Die Hard")) }.isSuccess }
        rule.onNodeWithText("For you").assertExists()
        // A grown-up switching to another grown-up needs no PIN; their rows start empty.
        rule.runOnUiThread { app.viewers.add(profile, "Sara", kids = false)?.let(app.viewers::choose) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("For you")).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText(CONTINUE_WATCHING).assertDoesNotExist()
    }

    @Test
    fun viewersAreAddedInSettingsOnTheTv() {
        val profile = Profile(sources = listOf(Source("s", "xtream", "Line")))
        home(profile)
        waitFor("Search movies")
        rule.onNodeWithContentDescription("Who's watching?").assertDoesNotExist()
        rule.onNode(hasContentDescription("Settings") and hasClickAction()).performClick()
        waitFor("Who's watching")
        // No parental PIN: grown-ups only.
        waitFor("A kids viewer needs a parental PIN: set one for this profile in the portal.")
        rule.onNode(hasSetTextAction()).performTextInput("Arshad")
        // The D-pad leaves the name field: Down moves on, and Right does once the cursor is at the end.
        rule.onNode(hasSetTextAction()).requestFocus()
        rule.onNode(hasSetTextAction()).assertIsFocused()
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).assertIsNotFocused()
        // Settings scrolls; the panel sits below the first screenful.
        rule.onNode(hasText("Add") and hasClickAction()).performScrollTo().performClick()
        waitFor("Remove Arshad")
        assertEquals(listOf("Arshad"), app.viewers.all().map { it.name })
    }

    @Test
    fun settingsKeepsItsPlaceAfterAChoice() {
        val vm = MainViewModel(app)
        rule.setContent {
            val menus = remember { ItemMenus() }
            CompositionLocalProvider(LocalItemMenus provides menus) {
                HomeScreen(vm, DeviceConfig(paired = true, profile = Profile()))
                menus.Host()
            }
        }
        rule.onNode(hasContentDescription("Settings") and hasClickAction()).performClick()
        waitFor("Stream quality")
        val live = rule.onNode(hasText("Live TV: ", substring = true) and hasClickAction())
        live.performScrollTo().requestFocus()
        live.performClick()
        waitFor("Stream quality · Live TV")
        rule.onNode(hasText("Always prefer HD") and hasClickAction()).performClick()
        waitFor("Live TV: Always prefer HD")
        rule.waitForIdle()
        live.assertIsFocused()
    }

    @Test
    fun settingsHasSectionsAndArrangeCategoriesStartsFolded() {
        var back: OnBackPressedDispatcher? = null
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            HomeScreen(MainViewModel(app), DeviceConfig(paired = true, profile = Profile()))
        }
        rule.onNode(hasContentDescription("Settings") and hasClickAction()).performClick()
        waitFor("Arrange categories")
        // Short sections, two to a column.
        fun left(heading: String) = rule.onNodeWithText(heading).fetchSemanticsNode().positionInRoot.x
        val columns = listOf("Viewing", "Channels and categories", "Viewers and PIN", "This TV").map(::left)
        assertEquals(columns[0], columns[1])
        assertEquals(columns[2], columns[3])
        assertTrue("$columns", columns[2] > columns[0])
        // One row per kind with its count, and no categories until one opens.
        val live = rule.onNode(hasText("Live TV") and hasText("2  ▾"))
        rule.onNode(hasText("Movies") and hasText("2  ▾")).assertExists()
        rule.onNodeWithText("CA| SPORTS EN").assertDoesNotExist()
        // The first focus into the page lands on its entry point; the second reaches the row.
        repeat(2) { live.performScrollTo().requestFocus(); rule.waitForIdle() }
        live.performKeyInput { pressKey(Key.DirectionCenter) }
        waitFor("CA| SPORTS EN")
        rule.onNode(hasText("Movies") and hasText("2  ▾")).assertExists()
        // Back folds it again, with focus back on its row.
        rule.onAllNodes(hasText("▼") and hasClickAction()).onFirst().requestFocus()
        rule.runOnUiThread { back!!.onBackPressed() }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("CA| SPORTS EN")).fetchSemanticsNodes().isEmpty() }
        rule.waitUntilAtLeastOneExists(isFocused() and hasText("Live TV") and hasText("2  ▾"), 10_000)
    }

    @Test
    fun aKidsViewerSeesOnlyKidsCategoriesAndLeavingAsksForThePin() {
        val profile = twoViewers()
        home(profile)
        waitFor("Recently added")
        rule.onNode(hasText("Who's watching?") and hasClickAction()).performClick()
        rule.onNode(hasText("Izaan · Kids") and hasClickAction()).performClick()
        waitFor("Izaan")
        // The catalog has no kids' films, so Movies has nothing to show.
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Recently added")).fetchSemanticsNodes().isEmpty() }
        // Back to Arshad needs the PIN; a wrong one keeps the kids' view.
        rule.onNode(hasText("Izaan") and hasClickAction()).performClick()
        rule.onNode(hasText("Arshad") and hasClickAction()).performClick()
        waitFor("Switch to Arshad?")
        val pin = rule.onNode(hasContentDescription("PIN", substring = true))
        pin.requestFocus()
        pin.performKeyInput { listOf(Key.Nine, Key.Nine, Key.Nine, Key.Nine).forEach { pressKey(it) } }
        waitFor("Wrong PIN")
        pin.performKeyInput { listOf(Key.One, Key.Two, Key.Three, Key.Four).forEach { pressKey(it) } }
        waitFor("Recently added")
        rule.onNode(hasText("Arshad") and hasClickAction()).assertExists()
    }

    @Test
    fun aKidsViewerGetsBiggerPosters() {
        runBlocking {
            val dao = app.db.catalog()
            dao.upsertCategories(listOf(CategoryEntity("s", Kind.MOVIE, "12", "KIDS MOVIES", 3, 0)))
            dao.upsertMovies(listOf(MovieEntity("s", "1002", "Frozen", null, "12", "7.4", "mp4", null, 3, 0, titleKey = "frozen")))
            // Half watched, so each shows as a poster in Continue watching.
            listOf("1000", "1002").forEach { id ->
                app.userDb.user().upsertProgress(
                    ProgressEntity(ProgressKey.movie("s", id), ProgressKey.MOVIE, "s", id, positionMs = 3_000_000, durationMs = 6_000_000, percent = 50f, updatedAt = id.toLong()),
                )
            }
        }
        val profile = twoViewers()
        home(profile)
        // The narrowest card naming the film is its poster (a highlight tile is wider).
        fun poster(title: String): Float {
            val card = hasClickAction() and hasAnyDescendant(hasText(title))
            rule.waitUntil(20_000) { rule.onAllNodes(card, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            return rule.onAllNodes(card, useUnmergedTree = true).fetchSemanticsNodes().minOf { it.boundsInRoot.width }
        }
        val adult = poster("Star Wars")
        rule.onNode(hasText("Who's watching?") and hasClickAction()).performClick()
        rule.onNode(hasText("Izaan · Kids") and hasClickAction()).performClick()
        waitFor("Izaan")
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Star Wars")).fetchSemanticsNodes().isEmpty() }
        // Continue watching is what this viewer played here.
        app.viewers.played(ProgressKey.movie("s", "1002"))
        val kids = poster("Frozen")
        assertTrue("kids poster $kids is not bigger than $adult", kids > adult * 1.2f)
    }

    @Test
    fun topBarButtonsDontOverlapOnMovies() {
        home()
        waitFor("Search movies")
        // The title row can settle after the tabs on a slow runner.
        rule.waitUntilAtLeastOneExists(hasContentDescription("Appearance: Auto"), 20_000)
        rule.waitUntilAtLeastOneExists(hasContentDescription("Settings"), 20_000)
        rule.waitForIdle()
        val switch = rule.onNodeWithContentDescription("Appearance: Auto").fetchSemanticsNode().boundsInRoot
        val gear = rule.onNodeWithContentDescription("Settings").fetchSemanticsNode().boundsInRoot
        assertTrue("switch $switch overlaps Settings $gear", switch.right <= gear.left)
        // 96 x 44 when it has its room.
        assertTrue("switch is squeezed: $switch", switch.width > switch.height * 2)
    }

    @Test
    fun focusNeverSticksOnSettings() {
        home()
        // The shelves first, so the card picked below isn't swapped out while focus is on it (slow CI runners).
        waitFor("Recently added")
        rule.waitUntilAtLeastOneExists(hasContentDescription("Settings"), 20_000)
        rule.waitForIdle()
        val movies = hasText("Movies") and hasClickAction()
        // Up from the right-most card goes to the chosen tab, not the Settings button above it.
        val tabs = rule.onNode(movies).fetchSemanticsNode().boundsInRoot
        val right = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { it.boundsInRoot.top > tabs.bottom }.maxBy { it.boundsInRoot.right }
        rule.onNode(SemanticsMatcher("right-most card") { it.id == right.id }).requestFocus()
        repeat(8) {
            if (rule.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.top <= tabs.bottom) return@repeat
            press(Key.DirectionUp)
        }
        rule.onNode(movies).assertIsFocused()
        // Left from Settings reaches the Appearance switch; Right moves on to the tabs.
        rule.onNodeWithContentDescription("Settings").requestFocus()
        press(Key.DirectionLeft)
        rule.onNodeWithContentDescription("Appearance: Auto").assertIsFocused()
        rule.onNodeWithContentDescription("Settings").requestFocus()
        press(Key.DirectionRight)
        rule.waitForIdle()
        rule.onNode(movies).assertIsFocused()
    }

    @Test
    fun backOnTheTabsAsksBeforeLeaving() {
        var back: OnBackPressedDispatcher? = null
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            HomeScreen(MainViewModel(app), DeviceConfig(paired = true, profile = Profile()))
        }
        fun pressBack() = rule.runOnUiThread { back!!.onBackPressed() }
        tab("Movies").performClick()
        waitFor("Search movies")
        tab("Movies").requestFocus()
        rule.waitForIdle()
        // Back on another tab goes to Home first, without asking, with focus on its page.
        pressBack()
        val home = tab("Home").fetchSemanticsNode().boundsInRoot
        rule.waitUntil(10_000) { rule.onAllNodes(isFocused()).fetchSemanticsNodes().any { it.boundsInRoot.top > home.bottom } }
        rule.onNodeWithText("Exit Family TV?").assertDoesNotExist()
        // Back from the page goes up to the tabs, then Back on Home asks.
        pressBack()
        rule.waitUntilAtLeastOneExists(isFocused() and hasText("Home"), 10_000)
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
