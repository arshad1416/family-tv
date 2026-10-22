package app.familytv.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A short list of actions for one movie or episode, opened by hold-OK or Menu (TV) or a long press (touch). */
class ItemMenu(val title: String, val actions: List<Pair<String, () -> Unit>>)

/** Opens an [ItemMenu] over whatever screen is showing; [Host] draws it. */
class ItemMenus {
    var open by mutableStateOf<ItemMenu?>(null)

    @Composable
    fun Host() {
        val menu = open ?: return
        val close = { open = null }
        // Hold-OK opens the menu while OK is still down: its repeats and its release then reach the menu, and the
        // release clicked the first option, so the menu shut at once. OK does nothing here until a fresh press.
        val fresh = remember(menu) { booleanArrayOf(false) }
        Dialog(onDismissRequest = close) {
            // Scrolls, for long lists.
            Column(
                Modifier.onPreviewKeyEvent { e -> heldOk(e.nativeKeyEvent, fresh) }
                    .widthIn(min = 280.dp, max = 460.dp).background(Tv.Raised, Tv.Shape).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(menu.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val first = remember(menu) { FocusRequester() }
                menu.actions.forEachIndexed { i, (label, action) ->
                    TvButton(label, { close(); action() }, if (i == 0) Modifier.fillMaxWidth().focusRequester(first) else Modifier.fillMaxWidth())
                }
                TvButton("Close", close, Modifier.fillMaxWidth())
                LaunchedEffect(menu) { runCatching { first.requestFocus() } }
            }
        }
    }
}

val LocalItemMenus = compositionLocalOf { ItemMenus() }

/**
 * True (eat it) for an OK key event left over from the press that opened a menu: its repeats and its
 * release. [fresh] turns true at the first new OK press, which goes through like any other.
 */
internal fun heldOk(k: android.view.KeyEvent, fresh: BooleanArray): Boolean {
    if (fresh[0] || !isOk(k.keyCode)) return false
    if (k.action == android.view.KeyEvent.ACTION_DOWN && k.repeatCount == 0) { fresh[0] = true; return false }
    return true
}

private fun isOk(code: Int) =
    code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER

suspend fun toast(ctx: Context, text: String) = withContext(Dispatchers.Main) {
    Toast.makeText(ctx.applicationContext, text, Toast.LENGTH_SHORT).show()
}
