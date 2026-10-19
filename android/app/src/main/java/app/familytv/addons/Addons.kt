package app.familytv.addons

import app.familytv.portal.DeviceConfig
import app.familytv.portal.PortalClient

/**
 * Paid add-ons: features already built into the app that the portal switches on for a profile,
 * bought once per add-on (parity checklist, "Spec: Paid add-ons framework"). The portal is the only
 * authority: a feature runs only while the last config says its add-on is active and that config
 * is at most [OFFLINE_MS] old. The app never keeps a "paid" flag of its own.
 */
object Addons {
    /** Add-on ids this version of the app has a feature for. Others are listed but never turn anything on. */
    val KNOWN: Set<String> = emptySet()

    /** Without a config from the portal for this long, owned add-ons pause until it answers again. */
    const val OFFLINE_MS = 7 * 24 * 3600_000L

    enum class State { ACTIVE, PAUSED, AVAILABLE, NOT_OFFERED }

    data class Addon(val id: String, val name: String, val state: State, val known: Boolean, val offline: Boolean) {
        /** The plain line Settings shows under its name. */
        val line: String get() = when {
            !known -> "Needs a newer version of the app"
            state == State.ACTIVE -> "On"
            state == State.PAUSED -> if (offline) "Paused: can't reach the portal" else "Paused: membership ended"
            state == State.AVAILABLE -> "Buy on the portal"
            else -> "No longer offered"
        }
    }

    /** Every add-on in [config], as of [now], given the config arrived at [configAt]. */
    fun list(config: DeviceConfig?, configAt: Long, now: Long, known: Set<String> = KNOWN): List<Addon> {
        val stale = now - configAt > OFFLINE_MS
        return config?.addons.orEmpty().mapNotNull { a ->
            val id = a.id?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // Anything but these (a state from a newer portal, say) never turns a feature on.
            val sent = when (a.state) {
                "active" -> State.ACTIVE
                "paused" -> State.PAUSED
                "available" -> State.AVAILABLE
                else -> State.NOT_OFFERED
            }
            val offline = stale && sent == State.ACTIVE
            Addon(id, a.name?.trim()?.takeIf { it.isNotEmpty() } ?: id, if (offline) State.PAUSED else sent, id in known, offline)
        }.distinctBy { it.id }
    }

    fun isActive(id: String, config: DeviceConfig?, configAt: Long, now: Long, known: Set<String> = KNOWN): Boolean =
        list(config, configAt, now, known).any { it.id == id && it.known && it.state == State.ACTIVE }

    /** The feature gate: whether add-on [id] may run on this TV now. */
    fun isActive(portal: PortalClient, id: String): Boolean = isActive(id, portal.cachedConfig, portal.configAt, System.currentTimeMillis())
}
