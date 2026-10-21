package app.familytv.sync

import android.content.Context
import android.content.SharedPreferences
import app.familytv.portal.DeviceLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "Lock this TV" from the portal's Devices page: while [message] is set the TV plays nothing (live,
 * movies, episodes, previews) and shows it full screen. The config and every check-in carry the
 * portal's state; the last one heard is kept, so a restart or an offline start stays locked.
 */
object TvLock {
    const val DEFAULT_MESSAGE = "This TV is paused. Contact Arshad."

    private var prefs: SharedPreferences? = null
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val locked: Boolean get() = _message.value != null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("tv_lock", Context.MODE_PRIVATE)
        _message.value = prefs?.getString("message", null)
    }

    /** The portal's answer: null unlocks; a lock with no message of its own shows [DEFAULT_MESSAGE]. */
    fun apply(lock: DeviceLock?) {
        val next = lock?.let { it.message?.trim()?.takeIf(String::isNotEmpty) ?: DEFAULT_MESSAGE }
        if (next == _message.value) return
        _message.value = next
        prefs?.edit()?.putString("message", next)?.apply()
    }
}
