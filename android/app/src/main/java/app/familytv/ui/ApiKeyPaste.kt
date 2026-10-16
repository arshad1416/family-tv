package app.familytv.ui

import app.familytv.discovery.ProfileRealDebrid

/** No clipboard access here: the UI reads text only after an explicit Paste press. */
internal fun pastedApiKey(value: CharSequence?): String? {
    if (value == null || value.length > 4096) return null
    val key = value.toString().trim()
    return key.takeIf { ProfileRealDebrid.validKey(it) && !it.contains("://") }
}
