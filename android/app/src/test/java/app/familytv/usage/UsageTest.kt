package app.familytv.usage

import app.familytv.usage.Usage.Action
import app.familytv.usage.Usage.Screen
import app.familytv.usage.Usage.Word
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTest {
    @After fun off() { Usage.enabled = false }

    @Test fun collectsNothingWhileTheProfileHasItOff() {
        Usage.enabled = false
        Usage.log(Screen.HOME, Action.OPEN)
        assertTrue(Usage.take().isEmpty())
    }

    @Test fun sendsOnlyFixedNamesAndNumbers() {
        Usage.enabled = true
        Usage.log(Screen.SEARCH, Action.SEARCH_SUBMIT, 4)
        Usage.log(Screen.SETTINGS, Action.SETTING_CHANGE, Word.STREAM_QUALITY_LIVE)
        assertEquals(
            listOf(Triple("search", "search_submit", 4), Triple("settings", "setting_change", "stream_quality_live")),
            Usage.take().map { Triple(it.screen, it.action, it.value) },
        )
    }

    @Test fun actionsBelongToTheScreenOnTop() {
        Usage.enabled = true
        Usage.opened(Screen.SERIES)
        Usage.opened(Screen.SERIES_PAGE)
        Usage.log(Action.HOLD_OK)
        Usage.left(Screen.SERIES_PAGE, 12)
        Usage.log(Action.BACK)
        Usage.left(Screen.SERIES, 30)
        assertEquals(
            listOf("series open", "series_page open", "series_page hold_ok", "series_page leave 12", "series back", "series leave 30"),
            Usage.take().map { listOfNotNull(it.screen, it.action, it.value).joinToString(" ") },
        )
    }

    @Test fun sendsInBatchesAndTurningOffEmptiesTheQueue() {
        Usage.enabled = true
        repeat(Usage.BATCH + 20) { Usage.log(Screen.PLAYER, Action.ZAP_DOWN) }
        assertEquals(Usage.BATCH, Usage.take().size)
        Usage.enabled = false
        Usage.enabled = true
        assertTrue(Usage.take().isEmpty())
    }

    // The portal (portal/src/usage.ts) and the Rust app use exactly these names.
    @Test fun namesMatchTheSharedSpec() {
        with(Usage) {
            assertEquals(
                "home live guide movies series series_page categories sports search settings player player_menu pairing update",
                Screen.entries.joinToString(" ") { it.wire },
            )
            assertEquals(
                "open leave back select hold_ok favourite_add favourite_remove mark_watched zap_up zap_down play_start play_fail " +
                    "play_switch_copy search_submit search_pick search_empty setting_change update_install exit_prompt exit error",
                Action.entries.joinToString(" ") { it.wire },
            )
            assertEquals(
                "stream_quality_live stream_quality_movies stream_quality_series picture_size audio_track subtitles run_check " +
                    "reload_channels parental_unlock category_order trakt open_on_start info menu live movie episode network decoder " +
                    "timeout source other",
                Word.entries.joinToString(" ") { it.wire },
            )
        }
    }
}
