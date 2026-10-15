package app.familytv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.view.KeyEvent

/**
 * Holding Back on any screen asks whether to leave the app. A short press still does what Back
 * did before (close the guide, go to the menu, and so on), because Back acts on release and the
 * release of a held press is swallowed here.
 */
class HoldBackToExit(private val activity: Activity) {
    private var heldPress = -1L
    private var dialog: AlertDialog? = null

    /** Call first from dispatchKeyEvent; true means the event was used here. */
    fun onKey(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_BACK) return false
        return when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount == 0) return false
                if (heldPress != e.downTime && e.eventTime - e.downTime >= HOLD_MS) {
                    heldPress = e.downTime
                    ask()
                }
                true // repeats mean nothing to the Back handlers, which act on release
            }
            KeyEvent.ACTION_UP -> e.downTime == heldPress
            else -> false
        }
    }

    private fun ask() {
        if (dialog?.isShowing == true || activity.isFinishing) return
        val press = heldPress
        dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Exit Family TV?")
            .setPositiveButton("Exit") { _, _ -> activity.finishAffinity() }
            .setNegativeButton("Cancel", null)
            // The release of the same held press lands on the dialog; it mustn't cancel it.
            .setOnKeyListener { _, code, ev -> code == KeyEvent.KEYCODE_BACK && ev.downTime == press }
            .show()
            .also { it.getButton(DialogInterface.BUTTON_NEGATIVE).requestFocus() }
    }

    private companion object {
        const val HOLD_MS = 800L
    }
}
