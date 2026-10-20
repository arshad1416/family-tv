package app.familytv.ui

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.familytv.portal.Profile

/**
 * The parental PIN, entered the way Google TV's parental controls ask for it: a row of slots and
 * no keypad, so nobody watching sees which digit was pressed. Up and Down roll the focused slot
 * through 0-9 (its digit shows only there, only while choosing); Right or OK makes it a dot and
 * moves on, Left steps back and clears; confirming the last slot checks the PIN. The remote's
 * number keys fill a slot with a dot straight away. A wrong PIN shakes the row and starts over.
 * Touch screens get a hidden number field instead. [onOk] runs once the right PIN is entered;
 * the row is the page's entry point unless [firstKey] is given.
 */
@Composable
internal fun PinPrompt(profile: Profile, firstKey: Modifier? = null, onOk: () -> Unit) {
    // No screenshots or casting while the PIN is asked for (backlog item 18).
    val window = LocalContext.current.findActivity()?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    var wrong by remember { mutableStateOf(false) }
    val check = { pin: String -> if (pinMatches(profile, pin)) { wrong = false; onOk(); true } else { wrong = true; false } }
    val focus = firstKey ?: entryPoint()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Enter the parental PIN", fontSize = 16.sp, color = MutedText)
        if (LocalFormFactor.current.touch) PinField(pinLength(profile), check, focus) else PinSlots(pinLength(profile), check, focus)
        if (wrong) Text("Wrong PIN", color = Tv.Bad)
    }
}

/** How many slots to show: the PIN's own length (the portal takes any), four when there is none. */
internal fun pinLength(profile: Profile): Int = profile.parentalPin?.length?.takeIf { it in 1..MAX_PIN } ?: 4

private const val MAX_PIN = 8

@Composable
private fun PinSlots(count: Int, check: (String) -> Boolean, modifier: Modifier) {
    var entered by remember { mutableStateOf("") }
    var digit by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    var shakes by remember { mutableIntStateOf(0) }
    val still = rememberReducedMotion()
    LaunchedEffect(shakes) {
        if (shakes == 0 || still) return@LaunchedEffect
        shake.animateTo(0f, keyframes { durationMillis = 300; -14f at 50; 14f at 110; -9f at 170; 6f at 230 })
    }
    fun confirm(d: Int) {
        entered += d
        digit = 0
        if (entered.length < count) return
        if (!check(entered)) { entered = ""; shakes++ }
    }
    val glass = LocalGlass.current
    val ring = if (glass) Glass.Ring else Tv.Accent
    val line = if (glass) Glass.Line else Tv.Line
    val text = if (glass) Glass.Text else Tv.Text
    Row(
        modifier
            .semantics { contentDescription = "PIN, ${entered.length} of $count entered" }
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val typed = NUMBER_KEYS[e.key]
                when {
                    typed != null -> { confirm(typed); true }
                    e.key == Key.DirectionUp -> { digit = (digit + 1) % 10; true }
                    e.key == Key.DirectionDown -> { digit = (digit + 9) % 10; true }
                    e.key == Key.DirectionRight || e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter -> { confirm(digit); true }
                    // Left at the first slot leaves the row like any other.
                    e.key == Key.DirectionLeft && entered.isNotEmpty() -> { entered = entered.dropLast(1); digit = 0; true }
                    else -> false
                }
            }
            .focusable()
            .graphicsLayer { translationX = shake.value },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(count) { i ->
            val current = focused && i == entered.length
            Box(
                Modifier.size(52.dp, 64.dp).border(if (current) 3.dp else 1.dp, if (current) ring else line, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    i < entered.length -> Text("•", fontSize = 30.sp, color = text)
                    current -> Text("$digit", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = text)
                }
            }
        }
    }
}

/** On a touch screen: the system's number keyboard, with dots in the field. */
@Composable
private fun PinField(count: Int, check: (String) -> Boolean, modifier: Modifier) {
    var pin by remember { mutableStateOf("") }
    val glass = LocalGlass.current
    val text = if (glass) Glass.Text else Tv.Text
    BasicTextField(
        pin,
        { v ->
            pin = v.filter(Char::isDigit).take(count)
            if (pin.length == count && !check(pin)) pin = ""
        },
        modifier.width((52 * count).dp).border(1.dp, if (glass) Glass.Line else Tv.Line, RoundedCornerShape(12.dp)).semantics { contentDescription = "PIN" },
        textStyle = TextStyle(color = text, fontSize = 28.sp),
        singleLine = true,
        cursorBrush = SolidColor(if (glass) Glass.Ring else Tv.Accent),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (pin.isNotEmpty() && !check(pin)) pin = "" }),
        decorationBox = { inner -> Box(Modifier.size((52 * count).dp, 56.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) { inner() } },
    )
}

private val NUMBER_KEYS = mapOf(
    Key.Zero to 0, Key.One to 1, Key.Two to 2, Key.Three to 3, Key.Four to 4,
    Key.Five to 5, Key.Six to 6, Key.Seven to 7, Key.Eight to 8, Key.Nine to 9,
    Key.NumPad0 to 0, Key.NumPad1 to 1, Key.NumPad2 to 2, Key.NumPad3 to 3, Key.NumPad4 to 4,
    Key.NumPad5 to 5, Key.NumPad6 to 6, Key.NumPad7 to 7, Key.NumPad8 to 8, Key.NumPad9 to 9,
)

/** True when [pin] is the profile's parental PIN, or the profile has none. */
fun pinMatches(profile: Profile, pin: String): Boolean = profile.parentalPin.isNullOrEmpty() || profile.parentalPin == pin
