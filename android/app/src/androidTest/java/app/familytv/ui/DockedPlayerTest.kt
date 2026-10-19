package app.familytv.ui

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.familytv.app
import app.familytv.data.ChannelEntity
import app.familytv.portal.DeviceConfig
import app.familytv.portal.Profile
import app.familytv.portal.Source
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives the phone and tablet player the way a person would (rotating, Back, taps) and checks
 * the player's own state: which layout it is in, what Back would close next, and that the same
 * player keeps playing across every switch. No screenshots.
 *
 * Needs an emulator or device: `./gradlew connectedDebugAndroidTest`. The phone tests skip on a
 * tablet and the other way round, so run it once on each. Plays a bundled clip as both a live
 * channel and a film; the debug portal address is the emulator's own host, so no real portal or
 * IPTV line is touched.
 */
@RunWith(AndroidJUnit4::class)
class DockedPlayerTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var clip: String

    @Before fun setUp() {
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        val file = File(target.filesDir, "test-clip.mp4")
        instrumentation.context.assets.open("clip.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        clip = file.toURI().toString()
        // A set-up TV with one channel, so Home opens without a portal or a channel load.
        target.app.portal.cachedConfig = DeviceConfig(paired = true, profile = Profile(sources = listOf(Source(SOURCE, "m3u", "Test"))))
        runBlocking {
            target.app.db.catalog().upsertChannels(
                (1..3).map { ChannelEntity(SOURCE, "s$it", "Test $it", it, null, CATEGORY, null, clip, 0, 0) },
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }
        waitFor("startup video to finish", 20_000) { !activity.splashing }
    }

    @After fun tearDown() {
        main { activity.dock.close() }
        scenario.close()
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }

    @Test fun phone_live_docksUpright_fullScreenSideways_noRestart() {
        assumeForm(FormFactor.Phone)
        startLive()
        waitMode("docked")
        val player = main { activity.dock.current!! }
        val start = position()

        turn(sideways = true)
        waitMode("full")
        assertSame("same player after turning sideways", player, main { activity.dock.current })
        waitFor("playback to continue in full screen") { main { player.isPlaying } && position() > start }

        val mid = position()
        turn(sideways = false)
        waitMode("docked")
        assertSame("same player after turning upright", player, main { activity.dock.current })
        waitFor("playback to continue docked") { main { player.isPlaying } && position() > mid }
    }

    @Test fun phone_live_backStepsThroughGuideThenDocksThenCloses() {
        assumeForm(FormFactor.Phone)
        startLive()
        turn(sideways = true)
        waitMode("full")
        val player = main { activity.dock.current!! }

        // The TV's Back sequence over live TV, one overlay at a time.
        for (step in listOf("guide_over", "guide_picture", "guide_categories")) {
            back()
            waitFor("Back to reach $step") { main { player.backState } == step }
            assertEquals("full", mode())
        }
        back()
        waitMode("docked")
        assertSame("Back docks the same player", player, main { activity.dock.current })
        assertEquals("none", main { player.backState })
        assertTrue("still playing when docked", waitPlaying(player))

        back()
        waitMode("closed")
    }

    @Test fun phone_film_fullScreenButtonThenBackDocks() {
        assumeForm(FormFactor.Phone)
        main { play(activity, clip, "Test film") }
        waitMode("docked")
        val player = main { activity.dock.current!! }
        assertTrue(waitPlaying(player))

        // The film's controls show by themselves when it starts; tap only if they've already hidden.
        if (!fullScreenButtonShown()) tapVideo()
        waitFor("the full-screen button", 5_000) { fullScreenButtonShown() }
        compose.onNodeWithContentDescription("Full screen").performClick()
        waitMode("full")
        assertSame(player, main { activity.dock.current })
        val at = position()
        waitFor("film to keep playing in full screen") { position() > at }

        back()
        waitMode("docked")
        assertSame(player, main { activity.dock.current })
        back()
        waitMode("closed")
    }

    @Test fun tablet_sideways_sideBySide_doubleTapFullScreen_backReturns() {
        assumeForm(FormFactor.Tablet)
        turn(sideways = true)
        startLive()
        waitMode("side")
        val player = main { activity.dock.current!! }
        assertTrue(waitPlaying(player))

        // A real double tap through the system, like a finger (Compose's own touch injection skips window focus).
        tapVideo(times = 2, atY = 0.5f)
        waitMode("full")
        assertSame(player, main { activity.dock.current })

        // Back steps through the guide first, then returns to side by side (never closes from full screen).
        for (step in listOf("guide_over", "guide_picture", "guide_categories")) {
            back()
            waitFor("Back to reach $step (is ${main { player.backState }}, ${mode()})") { main { player.backState } == step }
            assertEquals("full", mode())
        }
        back()
        waitMode("side")
        assertSame("same player back side by side", player, main { activity.dock.current })
        assertTrue(waitPlaying(player))

        turn(sideways = false)
        waitMode("docked")
        assertSame(player, main { activity.dock.current })
    }

    // ---------- helpers ----------

    private fun fullScreenButtonShown() = runCatching { compose.onNodeWithContentDescription("Full screen").assertExists() }.isSuccess

    private fun assumeForm(f: FormFactor) = assumeTrue("needs a ${f.name.lowercase()}", target.formFactor() == f)

    private fun startLive() = main { playLive(activity, SOURCE, CATEGORY, 0) }

    private fun mode() = main { activity.dock.mode }

    private fun position() = main { activity.dock.current?.playbackPosition ?: -1 }

    private fun waitMode(want: String) = waitFor("player to be $want") { mode() == want }

    private fun waitPlaying(p: PlayerController): Boolean {
        waitFor("playback") { main { p.isPlaying } }
        return true
    }

    /** Freezes the screen sideways or upright, whichever way round the device's natural orientation is. */
    private fun turn(sideways: Boolean) {
        val want = if (sideways) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        for (r in listOf(UiAutomation.ROTATION_FREEZE_0, UiAutomation.ROTATION_FREEZE_90)) {
            instrumentation.uiAutomation.setRotation(r)
            instrumentation.waitForIdleSync()
            if (runCatching { waitFor("screen to turn", 3_000) { orientation() == want } }.isSuccess) return
        }
        throw AssertionError("Screen didn't turn ${if (sideways) "sideways" else "upright"}")
    }

    private fun orientation() = main { activity.resources.configuration.orientation }

    /** Back through the system, as the gesture or Back button sends it; it goes to the window with focus. */
    private fun back() {
        waitFor("the app's window to have focus") { main { activity.hasWindowFocus() } }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
    }

    /** Taps on the video through the system, like a finger: one shows the film's controls, two go full screen on live. */
    private fun tapVideo(times: Int = 1, atY: Float = 0.15f) {
        val (x, y) = main {
            val v = activity.dock.current!!.root
            val at = IntArray(2).also(v::getLocationOnScreen)
            // Top middle: clear of the close and full-screen buttons and of the film's play button.
            (at[0] + v.width / 2f) to (at[1] + v.height * atY)
        }
        repeat(times) {
            val down = SystemClock.uptimeMillis()
            instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
            instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0))
            if (it < times - 1) SystemClock.sleep(80)
        }
    }

    /** Waits through the Compose rule: its test clock (the startup video's timers, fades) only moves while the rule waits. */
    private fun waitFor(what: String, timeoutMs: Long = 10_000, ok: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs) { ok() }
        } catch (e: ComposeTimeoutException) {
            val state = main {
                "${activity.dock.mode}, back=${activity.dock.current?.backState}, playing=${activity.dock.current?.isPlaying}, " +
                    "windowFocus=${activity.hasWindowFocus()}, focused=${activity.currentFocus?.javaClass?.simpleName}"
            }
            throw AssertionError("Timed out waiting for $what (player: $state)")
        }
    }

    private fun <T> main(block: () -> T): T {
        var out: Result<T>? = null
        instrumentation.runOnMainSync { out = runCatching(block) }
        return out!!.getOrThrow()
    }

    private companion object {
        const val SOURCE = "player-test"
        const val CATEGORY = "player-test"
    }
}
