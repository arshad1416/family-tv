package app.familytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import androidx.tv.material3.Text

/** Platform IME/password editing. Clipboard access happens only in the explicit Paste action. */
@Composable
internal fun ApiKeyEntry(value: String, onValue: (String) -> Unit, readClipboard: () -> CharSequence?) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val currentOnValue by rememberUpdatedState(onValue)
    var feedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { focus.requestFocus(); withFrameNanos { }; keyboard?.show() }
    DisposableEffect(Unit) { onDispose { currentOnValue(""); keyboard?.hide() } }
    BasicTextField(value, { raw ->
        val trimmed = if (raw.length <= 4096) raw.trim() else null
        if (trimmed != null && trimmed.length <= 512) { onValue(trimmed); feedback = null }
        else feedback = "API keys must be at most 512 characters. The previous entry was kept."
    }, Modifier.fillMaxWidth().background(Tv.Panel, Tv.Shape).padding(16.dp).focusRequester(focus)
        .onFocusChanged { if (it.isFocused) keyboard?.show() }
        .semantics { contentDescription = "Real-Debrid API key" },
        textStyle = TextStyle(color = Tv.Text, fontSize = 18.sp), singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }))
    feedback?.let { Text(it, color = Tv.Warn, fontSize = 14.sp) }
    TvButton("Paste API key", {
        try {
            val text = readClipboard()
            val key = pastedApiKey(text)
            feedback = when {
                text.isNullOrEmpty() -> "Clipboard has no text. Copy the API key and try again."
                key == null -> "Clipboard must contain a 16–512 character API key without spaces."
                else -> { onValue(key); "API key pasted (hidden). Review and save when ready." }
            }
        } catch (_: Exception) {
            feedback = "Clipboard access was denied. Use the keyboard paste action or try again."
        }
    })
}
