package app.familytv.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.familytv.app
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The TV's "liquid glass" look (parity checklist, "Spec: Liquid glass TV design"): white glass
 * panels over a dark gradient with one soft coloured glow per screen. Phones and tablets keep
 * the [Tv] look.
 */
object Glass {
    /** A panel: white at about 8% over the background. */
    val Fill = Color(0x14FFFFFF)
    /** Buttons, pills and the search box: a little more white. */
    val Raised = Color(0x1AFFFFFF)
    /** A focused row or pill that isn't selected. */
    val Focus = Color(0x2EFFFFFF)
    /** Panel edges, 1 px at about 20%. */
    val Line = Color(0x33FFFFFF)
    val LineSoft = Color(0x24FFFFFF)
    /** The selected tab: a white pill with dark text. */
    val Selected = Color(0xEBFFFFFF)
    val OnSelected = Color(0xFF0B0F18)
    /** The focus ring. */
    val Ring = Color(0xF2FFFFFF)
    val Text = Color(0xFFF5F7FA)
    val Dim = Color(0xB3F5F7FA)
    val Faint = Color(0x99F5F7FA)
    val Live = Color(0xFFE0242B)

    /** What [FocusBox] paints on glass pages: the [Tv] panel colours become glass, focus a white wash. */
    internal fun fill(color: Color, focused: Boolean, selected: Boolean): Color = when {
        color == Selected -> Selected
        focused -> Focus
        selected -> Color(0x24FFFFFF)
        color == Tv.Panel -> Fill
        color == Tv.Raised -> Color(0x1FFFFFFF)
        else -> color
    }
}

/** True on the TV's glass pages: [FocusBox] rings focus in white there instead of painting it blue. */
val LocalGlass = staticCompositionLocalOf { false }

/** The mockup is drawn at 1280 x 720; this many dp per mockup pixel on this screen (0.75 on a 960 dp TV). */
internal val LocalGlassScale = staticCompositionLocalOf { 0.75f }

/** Mockup pixels as dp on this screen. */
internal val Int.gx: Dp @Composable get() = (this * LocalGlassScale.current).dp

/** Mockup pixels as text size on this screen. */
internal val Int.gsp: TextUnit @Composable get() = (this * LocalGlassScale.current).sp

/** The mockup is 1280 px wide; a 960 dp TV gets 0.75 (the same until the window is measured). */
@Composable
internal fun rememberGlassScale(): Float {
    val width = LocalWindowInfo.current.containerSize.width
    val density = LocalDensity.current
    return if (width <= 0) 0.75f else with(density) { width.toDp().value } / 1280f
}

/** A glass panel's fill and 1 px edge. */
fun Modifier.glass(shape: Shape, fill: Color = Glass.Fill, line: Color = Glass.Line): Modifier =
    clip(shape).background(fill).border(1.dp, line, shape)

/** The dark radial gradient with [glow] softly lighting the top right. */
@Composable
internal fun GlassBackground(glow: Color, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val top = lerp(Color(0xFF151B28), glow, 0.16f)
    Box(
        modifier.fillMaxSize().drawBehind {
            drawRect(Brush.radialGradient(0f to top, 0.45f to Color(0xFF10141D), 1f to Color(0xFF07090F), center = Offset(size.width * 0.22f, 0f), radius = size.width * 1.1f))
            drawRect(Brush.radialGradient(listOf(glow.copy(alpha = 0.30f), glow.copy(alpha = 0.10f), Color.Transparent), center = Offset(size.width * 0.78f, size.height * 0.04f), radius = size.width * 0.42f))
        },
        content = content,
    )
}

/** A rounded glass button or tab: a white pill with dark text when [selected], a white ring and 1.1x when focused. */
@Composable
internal fun GlassPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    fill: Color = Color.Transparent,
    border: Boolean = false,
    fontSize: TextUnit = 16.gsp,
    padH: Dp = 18.gx,
    padV: Dp = 8.gx,
    icon: (@Composable () -> Unit)? = null,
    onFocus: () -> Unit = {},
) {
    FocusBox(
        onClick = onClick, onFocus = onFocus, color = if (selected) Glass.Selected else fill, shape = RoundedCornerShape(50),
        scale = 1.06f, contentAlignment = Alignment.Center,
        modifier = modifier.then(if (border && !selected) Modifier.border(1.dp, Glass.LineSoft, RoundedCornerShape(50)) else Modifier),
    ) { focused ->
        Row(Modifier.padding(horizontal = padH, vertical = padV), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { it(); Spacer(Modifier.width(10.gx)) }
            Text(
                text, fontSize = fontSize, maxLines = 1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = when { selected -> Glass.OnSelected; focused -> Glass.Text; else -> Glass.Dim },
            )
        }
    }
}

/** A round glass icon button (Settings, Back). */
@Composable
internal fun GlassIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    FocusBox(
        onClick = onClick, color = if (selected) Glass.Selected else Glass.Raised, shape = CircleShape, scale = 1.06f, contentAlignment = Alignment.Center,
        modifier = modifier.size(48.gx).border(1.dp, Glass.LineSoft, CircleShape),
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) Glass.OnSelected else Glass.Text, modifier = Modifier.size(22.gx))
    }
}

@Composable
internal fun GlassBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) =
    GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick, modifier)

/** The clock at the top right. */
@Composable
internal fun GlassClock() {
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(15_000); value = System.currentTimeMillis() } }
    Text(time(now), fontSize = 20.gsp, color = Color(0xD9F5F7FA), maxLines = 1)
}

/** A small section heading ("Providers"). */
@Composable
internal fun GlassHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 17.gsp, fontWeight = FontWeight.SemiBold, color = Color(0xE6F5F7FA), modifier = modifier)
}

/** A column heading inside a panel ("GENRE"). */
@Composable
internal fun GlassLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), fontSize = 13.gsp, fontWeight = FontWeight.SemiBold, color = Glass.Faint, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.padding(start = 10.gx, top = 4.gx, bottom = 6.gx))
}

/**
 * The search box: a glass pill that searches only the page it's on. OK opens the keyboard;
 * Search or Down goes to the results.
 */
@Composable
internal fun GlassSearchBox(query: String, onQuery: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = TextStyle(color = Glass.Text, fontSize = 15.gsp),
        cursorBrush = SolidColor(Glass.Text),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focusManager.moveFocus(FocusDirection.Down) }),
        modifier = modifier.width(260.gx).height(42.gx).onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxSize().clip(shape).background(if (focused) Glass.Focus else Glass.Raised)
                    .border(if (focused) 2.dp else 1.dp, if (focused) Glass.Ring else Glass.LineSoft, shape)
                    .padding(horizontal = 16.gx),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = Glass.Dim, modifier = Modifier.size(18.gx))
                Spacer(Modifier.width(10.gx))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(hint, fontSize = 15.gsp, color = Color(0xA6F5F7FA), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            }
        },
    )
}

/** The YouTube and JustWatch buttons on Movies and TV Shows. */
@Composable
internal fun AppButtons() {
    val ctx = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.gx), verticalAlignment = Alignment.CenterVertically) {
        GlassPill(
            "YouTube", { openApp(ctx, "YouTube", YOUTUBE, "https://www.youtube.com") },
            fill = Glass.Raised, border = true, fontSize = 15.gsp, padH = 16.gx, padV = 13.gx,
            icon = { PlayMark(Color(0xFFFF0033), Color.White) },
        )
        GlassPill(
            "JustWatch", { openApp(ctx, "JustWatch", JUSTWATCH, "https://www.justwatch.com") },
            fill = Glass.Raised, border = true, fontSize = 15.gsp, padH = 16.gx, padV = 13.gx,
            icon = { PlayMark(Color(0xFFFBC500), Color(0xFF0B0F18)) },
        )
    }
}

/** A small rounded square with a play triangle, standing in for an app's icon. */
@Composable
private fun PlayMark(back: Color, mark: Color) {
    Canvas(Modifier.size(width = 24.gx, height = 18.gx)) {
        drawRoundRect(back, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.28f))
        val p = Path().apply {
            moveTo(size.width * 0.40f, size.height * 0.28f)
            lineTo(size.width * 0.68f, size.height * 0.50f)
            lineTo(size.width * 0.40f, size.height * 0.72f)
            close()
        }
        drawPath(p, mark)
    }
}

/** The TV, phone and Fire TV YouTube apps. */
internal val YOUTUBE = listOf("com.google.android.youtube.tv", "com.amazon.firetv.youtube", "com.google.android.youtube")
internal val JUSTWATCH = listOf("com.justwatch.justwatch")

/** Opens the first of [packages] installed, else [web] in the browser; says so when the TV can do neither. */
internal fun openApp(ctx: Context, name: String, packages: List<String>, web: String) {
    val pm = ctx.packageManager
    val app = packages.firstNotNullOfOrNull { runCatching { pm.getLeanbackLaunchIntentForPackage(it) ?: pm.getLaunchIntentForPackage(it) }.getOrNull() }
    val intent = (app ?: Intent(Intent.ACTION_VIEW, Uri.parse(web))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        ctx.app.scope.launch { toast(ctx, "$name isn't on this TV") }
    }
}

/** The page's settings button. */
@Composable
internal fun SettingsButton(selected: Boolean, requester: FocusRequester, onClick: () -> Unit) {
    GlassIconButton(Icons.Outlined.Settings, "Settings", onClick, Modifier.focusRequester(requester), selected = selected)
}

/** Selects [value] once focus has rested on it (as the old rail did), so passing over an item doesn't load it. */
@Composable
internal fun <T> FollowFocus(state: MutableState<T?>, onRest: (T) -> Unit) {
    val rest by rememberUpdatedState(onRest)
    LaunchedEffect(state.value) { state.value?.let { delay(300); rest(it) } }
}

/** A tall glass column ("Genre", "Country") of selectable rows. */
@Composable
internal fun GlassColumnPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.glass(RoundedCornerShape(24.gx), Color(0x12FFFFFF), Color(0x2EFFFFFF)).padding(12.gx), verticalArrangement = Arrangement.spacedBy(6.gx)) { content() }
}

/** One selectable row in a [GlassColumnPanel]: white with dark text when selected. */
@Composable
internal fun GlassRowItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    onFocus: () -> Unit = {},
) {
    FocusBox(onClick = onClick, onFocus = onFocus, color = if (selected) Glass.Selected else Color.Transparent, shape = RoundedCornerShape(14.gx), modifier = modifier.fillMaxWidth()) { _ ->
        Row(Modifier.padding(horizontal = 14.gx, vertical = 11.gx), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text, fontSize = 16.gsp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Glass.OnSelected else Color(0xD9F5F7FA),
            )
            count?.let { Text("$it", fontSize = 16.gsp, maxLines = 1, color = (if (selected) Glass.OnSelected else Glass.Text).copy(alpha = 0.6f)) }
        }
    }
}

/** Lights the page with [color] while this is composed (a provider's page takes the provider's colour). */
@Composable
internal fun GlassGlow(color: Color) {
    val shell = LocalGlassShell.current ?: return
    DisposableEffect(color) {
        val before = shell.glow.value
        shell.glow.value = color
        onDispose { shell.glow.value = before }
    }
}
