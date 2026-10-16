package app.familytv.discovery

import app.familytv.FamilyTvApp
import app.familytv.portal.Profile
import com.google.gson.Gson
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest

/** Actual profile ID plus current policy. Identical-looking profiles must never share local credentials. */
data class DiscoveryIdentity(val profileId: String, val policy: String, val deviceId: String?)

class DiscoverySession internal constructor(
    private val identity: DiscoveryIdentity, private val current: suspend () -> DiscoveryIdentity?,
) {
    val profileId get() = identity.profileId
    val policy get() = identity.policy
    suspend fun verify() {
        currentCoroutineContext().ensureActive()
        if (current() != identity) {
            throw SourceUnavailable("The TV profile or parental policy changed. Close this window and unlock again.")
        }
        currentCoroutineContext().ensureActive()
    }

    companion object {
        suspend fun open(app: FamilyTvApp, expected: Profile): DiscoverySession {
            suspend fun current(): DiscoveryIdentity? {
                val config = app.portal.config()
                if (!config.paired || config.profile == null) return null
                val id = app.portal.progressPull(0).profile?.takeIf { it.isNotBlank() } ?: return null
                return DiscoveryIdentity(id, policyKey(config.profile), app.portal.deviceId)
            }
            val identity = current() ?: throw SourceUnavailable("Cannot verify the current profile. Try again when the portal is available.")
            if (identity.policy != policyKey(expected)) throw SourceUnavailable("The TV profile changed. Reload the app before searching externally.")
            return DiscoverySession(identity, ::current).also { it.verify() }
        }
        fun policyKey(profile: Profile): String = digest(Gson().toJson(profile))
    }
}

internal fun digest(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
