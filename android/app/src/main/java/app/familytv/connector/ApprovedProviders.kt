package app.familytv.connector

import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * A companion the host may bind: its package, the SHA-256 of its one signing certificate, and the
 * portal add-on that switches it on. Only providers that are licensed or serve the household's own
 * media go here, and only once Arshad names one (parity checklist, "Spec: External source connector").
 */
data class ApprovedProvider(val addonId: String, val packageName: String, val certSha256: String)

object ApprovedProviders {
    /** In place of a certificate: signed with the same key as this app (the debug reference companion). */
    const val SAME_AS_HOST = "same-as-host"

    /** Release builds bind nothing. Add an entry here, never from config, once a provider is approved. */
    private val RELEASE: List<ApprovedProvider> = emptyList()

    /** The add-on id the debug reference companion answers to. */
    const val REFERENCE_ADDON = "connector-reference"
    const val REFERENCE_CLASS = "app.familytv.connector.ReferenceSourceProvider"

    /** Debug builds also allow the reference companion in this APK (src/debug), which serves synthetic public-domain data only. */
    fun forBuild(debug: Boolean, hostPackage: String): List<ApprovedProvider> =
        if (debug) RELEASE + ApprovedProvider(REFERENCE_ADDON, hostPackage, SAME_AS_HOST) else RELEASE

    /** The SHA-256s (lowercase hex) of [pkg]'s current signing certificates; null when it isn't installed. */
    @Suppress("DEPRECATION")
    fun signers(pm: PackageManager, pkg: String): List<String>? = try {
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        sigs.orEmpty().map { sha256(it.toByteArray()) }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** Whether a package signed by [signers] is [p]: exactly one signer, and it is the pinned one. */
    fun matches(p: ApprovedProvider, signers: List<String>?, hostSigners: List<String>?): Boolean {
        val pinned = if (p.certSha256 == SAME_AS_HOST) hostSigners?.singleOrNull() else p.certSha256.lowercase().replace(":", "")
        return pinned != null && signers?.singleOrNull() == pinned
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
