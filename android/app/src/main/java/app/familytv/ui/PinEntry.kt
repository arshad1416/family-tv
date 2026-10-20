package app.familytv.ui

import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.familytv.portal.Profile
import kotlinx.coroutines.delay

/**
 * The parental PIN, entered the way Google TV asks for it: on a TV, the remote-wheel control
 * ([PinWheel]), whose screen never shows which digit was pressed; the remote's number keys work
 * too. The PIN is checked once the last digit is in; Back takes the last digit away, and with
 * none goes back as usual. A wrong PIN shakes the dots and starts over; five in a row lock entry
 * for 30 seconds ([PinLock]). The digits never reach the accessibility text, only how many are
 * entered. Touch screens get a hidden number field instead. [onOk] runs once the right PIN is
 * entered; the control is the page's entry point unless [firstKey] is given.
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
    var wait by remember { mutableIntStateOf(PinLock.secondsLeft()) }
    LaunchedEffect(wait > 0) { while (wait > 0) { delay(1_000); wait = PinLock.secondsLeft() } }
    val check = { pin: String ->
        when {
            PinLock.secondsLeft() > 0 -> false
            pinMatches(profile, pin) -> { PinLock.right(); wrong = false; onOk(); true }
            else -> { PinLock.wrong(); wrong = true; wait = PinLock.secondsLeft(); false }
        }
    }
    val focus = firstKey ?: entryPoint()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Enter the parental PIN", fontSize = 16.sp, color = MutedText)
        if (LocalFormFactor.current.touch) PinField(pinLength(profile), check, focus) else PinWheel(pinLength(profile), check, focus)
        when {
            wait > 0 -> Text("Too many tries. Try again in $wait s", color = Tv.Bad)
            wrong -> Text("Wrong PIN", color = Tv.Bad)
        }
    }
}

/** Wrong PINs in a row, for every PIN on this TV: the fifth locks entry for 30 seconds. Kept until the app closes. */
internal object PinLock {
    const val TRIES = 5
    const val LOCK_MS = 30_000L
    var now: () -> Long = SystemClock::elapsedRealtime
    private var wrongs = 0
    private var until = 0L

    fun secondsLeft(): Int = ((until - now() + 999) / 1_000).toInt().coerceAtLeast(0)

    fun wrong() {
        if (++wrongs < TRIES) return
        wrongs = 0
        until = now() + LOCK_MS
    }

    fun right() { wrongs = 0 }

    fun reset() { wrongs = 0; until = 0 }
}

/** How many slots to show: the PIN's own length (the portal takes any), four when there is none. */
internal fun pinLength(profile: Profile): Int = profile.parentalPin?.length?.takeIf { it in 1..MAX_PIN } ?: 4

private const val MAX_PIN = 8

/**
 * The TV's PIN control, after Google TV's own: a round wheel showing one row of the keypad, Up/Down
 * changing the row, Left/OK/Right entering its left, middle or right number ([0] takes OK only).
 * A press shows nothing but the next dot filling, and the wheel stays on its row until Up or Down
 * moves it, so a run of digits from one row needs no extra presses.
 */
@Composable
private fun PinWheel(count: Int, check: (String) -> Boolean, modifier: Modifier) {
    var entered by remember { mutableStateOf("") }
    var row by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    var backTaken by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    var shakes by remember { mutableIntStateOf(0) }
    val still = rememberReducedMotion()
    LaunchedEffect(shakes) {
        if (shakes == 0 || still) return@LaunchedEffect
        shake.animateTo(0f, keyframes { durationMillis = 300; -14f at 50; 14f at 110; -9f at 170; 6f at 230 })
    }
    fun confirm(d: Int) {
        if (PinLock.secondsLeft() > 0) return
        entered += d
        if (entered.length < count) return
        if (!check(entered)) { entered = ""; shakes++ }
    }
    val glass = LocalGlass.current
    val ring = if (glass) Glass.Ring else Tv.Accent
    val line = if (glass) Glass.Line else Tv.Line
    val text = if (glass) Glass.Text else Tv.Text
    val fill = if (glass) Glass.Raised else Tv.Raised
    Column(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                // Back takes a digit away (down and up both, so the page doesn't also go back); on none it passes.
                if (e.key == Key.Back) {
                    if (e.type == KeyEventType.KeyDown && entered.isNotEmpty()) { entered = entered.dropLast(1); backTaken = true; return@onKeyEvent true }
                    if (e.type == KeyEventType.KeyUp && backTaken) { backTaken = false; return@onKeyEvent true }
                    return@onKeyEvent false
                }
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                // A held key enters one digit, not a run of them.
                val held = e.nativeKeyEvent.repeatCount > 0
                val typed = NUMBER_KEYS[e.key]
                // Read here, not from the last composition: several presses can land before it recomposes.
                val keys = ROWS[row]
                when {
                    typed != null -> { if (!held) confirm(typed); true }
                    e.key == Key.DirectionUp -> { row = (row + ROWS.size - 1) % ROWS.size; true }
                    e.key == Key.DirectionDown -> { row = (row + 1) % ROWS.size; true }
                    e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter -> { if (!held) confirm(keys[keys.size / 2]); true }
                    // The wheel keeps Left and Right: on [0] they do nothing rather than move focus off the PIN.
                    e.key == Key.DirectionLeft -> { if (!held && keys.size == 3) confirm(keys[0]); true }
                    e.key == Key.DirectionRight -> { if (!held && keys.size == 3) confirm(keys[2]); true }
                    else -> false
                }
            }
            .focusable()
            // After focusable, so the control stays focusable for TalkBack while no digit reaches it.
            .clearAndSetSemantics { contentDescription = "PIN, ${entered.length} of $count digits entered" },
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.graphicsLayer { translationX = shake.value }, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(count) { i ->
                Column(Modifier.width(34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                        if (i < entered.length) Box(Modifier.size(14.dp).background(text, CircleShape))
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.size(34.dp, 2.dp).background(if (focused && i == entered.length) ring else line))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            // The wheel: the active row's numbers where the remote's Left, OK and Right are.
            Box(
                Modifier.size(196.dp).background(fill, CircleShape).border(if (focused) 3.dp else 1.dp, if (focused) ring else line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.KeyboardArrowUp, null, Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(22.dp), tint = MutedText)
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).size(22.dp), tint = MutedText)
                Box(Modifier.size(64.dp).border(1.dp, line, CircleShape))
                Row(Modifier.fillMaxWidth().padding(horizontal = 26.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    val keys = ROWS[row]
                    val slots = if (keys.size == 3) keys.map { "$it" } else listOf("", "${keys[0]}", "")
                    slots.forEach { Text(it, Modifier.width(32.dp), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = text, textAlign = TextAlign.Center) }
                }
            }
            // The keypad on the side: which row the wheel is on, never which number.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ROWS.forEachIndexed { r, digits ->
                    Row(
                        Modifier
                            .then(if (r == row) Modifier.background(ring.copy(alpha = 0.22f), CircleShape).border(1.dp, ring, CircleShape) else Modifier)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        digits.forEach { Text("$it", Modifier.width(14.dp), fontSize = 16.sp, color = if (r == row) text else MutedText, textAlign = TextAlign.Center) }
                    }
                }
            }
        }
        Text("Up or Down picks a row. Left, OK or Right enters a number. Back deletes.", fontSize = 14.sp, color = MutedText)
    }
}

/** The keypad's rows, as the wheel shows them. */
private val ROWS = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(0))

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
