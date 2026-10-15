package app.familytv.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Which layout the app draws. TV keeps the D-pad layout (left rail, focus-driven headers);
 * phones get bottom tabs and compact lists; tablets get a touch side rail and two panes.
 */
enum class FormFactor {
    Tv, Phone, Tablet;

    val touch get() = this != Tv

    companion object {
        /** Phone vs tablet uses the smallest width, so turning a phone sideways doesn't make it a tablet. */
        const val TABLET_MIN_DP = 600

        fun of(isTv: Boolean, hasTouch: Boolean, smallestWidthDp: Int): FormFactor = when {
            isTv || !hasTouch -> Tv
            smallestWidthDp >= TABLET_MIN_DP -> Tablet
            else -> Phone
        }
    }
}

/** True on Android TV / Google TV / Fire TV (leanback or TV UI mode). */
fun Context.isTelevision(): Boolean {
    val pm = packageManager
    val uiTv = (getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    return uiTv || pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature("amazon.hardware.fire_tv")
}

fun Context.formFactor(smallestWidthDp: Int = resources.configuration.smallestScreenWidthDp): FormFactor =
    FormFactor.of(isTelevision(), packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN), smallestWidthDp)

val LocalFormFactor = staticCompositionLocalOf { FormFactor.Tv }

@Composable
fun rememberFormFactor(): FormFactor {
    val ctx = LocalContext.current
    val sw = LocalConfiguration.current.smallestScreenWidthDp
    return remember(sw) { ctx.formFactor(sw) }
}
