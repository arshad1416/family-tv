package app.familytv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.robolectric.RuntimeEnvironment
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ApiKeyEntryTest {
    @get:Rule val rule = createComposeRule()
    @Test fun fieldInitiallyFocusedAndNormalInputTrimsWithoutChangingCase() {
        var value by mutableStateOf("")
        var reads = 0
        rule.setContent { FamilyTvTheme { Column { ApiKeyEntry(value, { value = it }, { reads++; null }) } } }
        val field = rule.onNodeWithContentDescription("Real-Debrid API key")
        field.assertIsFocused()
        field.performTextReplacement("  ABCDsyntheticKEY123WXYZ  ")
        rule.runOnIdle { assertEquals("ABCDsyntheticKEY123WXYZ", value); assertEquals(0, reads) }
        field.performTextReplacement("x".repeat(513))
        rule.runOnIdle { assertEquals("ABCDsyntheticKEY123WXYZ", value) }
    }
    @OptIn(ExperimentalTestApi::class)
    @Test fun platformKeyboardPasteWorksAndImeIsShownThenHidden() {
        var value by mutableStateOf("")
        var visible by mutableStateOf(true)
        var shows = 0
        var hides = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() { shows++ }
            override fun hide() { hides++ }
        }
        rule.setContent { CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
            FamilyTvTheme { Column { if (visible) ApiKeyEntry(value, { value = it }, { error("Explicit paste was not selected") }) } }
        } }
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Synthetic fixture", "  ABCDsyntheticKEY123WXYZ  "))
        rule.onNodeWithContentDescription("Real-Debrid API key").performSemanticsAction(SemanticsActions.PasteText) { it() }
        rule.runOnIdle { assertEquals("ABCDsyntheticKEY123WXYZ", value); assertTrue(shows > 0); visible = false }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("", value); assertTrue(hides > 0) }
    }

    @Test fun clipboardFailuresInlineAndDisposeClearsDraft() {
        var value by mutableStateOf("ABCDsyntheticKEY123WXYZ")
        var visible by mutableStateOf(true)
        var denied = false
        var reads = 0
        rule.setContent { FamilyTvTheme { Column { if (visible) ApiKeyEntry(value, { value = it }, { reads++; if (denied) throw SecurityException() else null }) } } }
        rule.runOnIdle { assertEquals(0, reads) }
        rule.onNodeWithText("Paste API key").performClick()
        rule.onNodeWithText("Clipboard has no text. Copy the API key and try again.").assertExists()
        rule.runOnIdle { denied = true }
        rule.onNodeWithText("Paste API key").performClick()
        rule.onNodeWithText("Clipboard access was denied. Use the keyboard paste action or try again.").assertExists()
        rule.runOnIdle { assertEquals(2, reads); visible = false }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("", value) }
    }
}
