package app.familytv.diag

/** Must match the keys in portal/src/model.ts VERDICTS. Ordered roughly by how actionable they are. */
enum class Verdict(val severity: Int, val tvMessage: String) {
    OK(0, "Everything checks out."),
    SLOW_CONNECTION(1, "Your internet is too slow for smooth playback. Try moving closer to Wi-Fi or using a cable."),
    SERVER_ERROR(2, "The TV service is having problems right now. It usually fixes itself; try again shortly."),
    MAX_CONNECTIONS(2, "Too many devices are watching on this account. Stop one and try again."),
    NO_INTERNET(3, "This TV isn't connected to the internet. Check Wi-Fi or the router."),
    DNS_FILTERED(3, "Something on your home network (an ad blocker or parental filter) is blocking the TV service."),
    NETWORK_BLOCKED(3, "The TV service can't be reached from this network. It may be blocked by the network or internet provider."),
    DNS_NOT_FOUND(3, "The TV service address has changed. Your admin has been told."),
    SERVER_DOWN(3, "The TV service is down right now. Try again later."),
    BAD_LOGIN(3, "The TV service login stopped working. Your admin has been told."),
    EXPIRED(3, "The TV service subscription has expired. Your admin has been told."),
}

/** Raw observations for one host. Kept separate from classification so the rules are unit-testable. */
data class HostFacts(
    val url: String,
    val host: String,
    val systemDns: List<String>?, // null = lookup failed
    val publicDns: List<String>?, // null = DoH unreachable; empty = NXDOMAIN
    val tcpOk: Boolean?,
    val tcpError: String? = null,
    val portalReachable: Boolean?, // can Cloudflare's edge reach the same server?
    val httpStatus: Int? = null,
    val httpBodyLooksLikeHtml: Boolean = false,
)

data class AccountFacts(
    val auth: Boolean,
    val status: String?,
    val expiresAt: Long?,
    val activeConnections: Int?,
    val maxConnections: Int?,
)

data class StreamFacts(
    val httpStatus: Int?,
    val bytes: Long,
    val millis: Long,
    val error: String?,
) {
    val kbps: Long get() = if (millis <= 0) 0 else bytes * 8 / millis
}

data class Finding(val verdict: Verdict, val host: String?, val detail: String)

object Classifier {
    private val SINKHOLES = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "0:0:0:0:0:0:0:0", "0:0:0:0:0:0:0:1")

    fun isSinkhole(addrs: List<String>?): Boolean =
        addrs != null && addrs.isNotEmpty() && addrs.all { it in SINKHOLES || it.startsWith("127.") }

    fun host(f: HostFacts): Finding? {
        val sysFailed = f.systemDns == null || f.systemDns.isEmpty()
        val publicResolves = !f.publicDns.isNullOrEmpty()

        if ((sysFailed || isSinkhole(f.systemDns)) && publicResolves) {
            return Finding(Verdict.DNS_FILTERED, f.host, "Home DNS answered ${f.systemDns?.joinToString() ?: "nothing"}, public DNS answered ${f.publicDns!!.joinToString()}. Allowlist ${f.host.substringBefore(':')} on the network's DNS filter.")
        }
        if (sysFailed && f.publicDns != null && f.publicDns.isEmpty()) {
            return Finding(Verdict.DNS_NOT_FOUND, f.host, "No DNS record anywhere for ${f.host}.")
        }
        if (f.tcpOk == false) {
            return when (f.portalReachable) {
                true -> Finding(Verdict.NETWORK_BLOCKED, f.host, "TV can't connect (${f.tcpError ?: "no answer"}) but the portal reaches the server.")
                false -> Finding(Verdict.SERVER_DOWN, f.host, "Neither the TV nor the portal can reach the server.")
                null -> Finding(Verdict.SERVER_DOWN, f.host, "TV can't connect (${f.tcpError ?: "no answer"}); portal check unavailable.")
            }
        }
        when (f.httpStatus) {
            451 -> return Finding(Verdict.NETWORK_BLOCKED, f.host, "HTTP 451: the network refused this site.")
            in 500..599 -> return Finding(Verdict.SERVER_ERROR, f.host, "Server answered HTTP ${f.httpStatus}.")
        }
        if (f.httpBodyLooksLikeHtml && f.portalReachable == true) {
            return Finding(Verdict.NETWORK_BLOCKED, f.host, "Got a web page instead of the TV service; looks like a block or captive page.")
        }
        return null
    }

    fun account(a: AccountFacts, now: Long, playingHere: Boolean): Finding? {
        if (!a.auth) return Finding(Verdict.BAD_LOGIN, null, "Provider rejected the username/password.")
        if (a.status != null && !a.status.equals("Active", ignoreCase = true)) {
            return Finding(Verdict.EXPIRED, null, "Line status is ${a.status}.")
        }
        if (a.expiresAt != null && a.expiresAt in 1 until now) return Finding(Verdict.EXPIRED, null, "Line expired.")
        val max = a.maxConnections ?: 0
        val active = (a.activeConnections ?: 0) - if (playingHere) 1 else 0
        if (max > 0 && active >= max) return Finding(Verdict.MAX_CONNECTIONS, null, "$active of $max connections already in use elsewhere.")
        return null
    }

    fun stream(s: StreamFacts, portalReachable: Boolean?): Finding? {
        when (s.httpStatus) {
            null -> if (s.error != null && s.bytes == 0L) {
                return if (portalReachable == true) Finding(Verdict.NETWORK_BLOCKED, null, "Stream connection failed (${s.error}) while the server is up.")
                else Finding(Verdict.SERVER_ERROR, null, "Stream connection failed (${s.error}).")
            }
            451 -> return Finding(Verdict.NETWORK_BLOCKED, null, "Stream refused with HTTP 451.")
            401, 403, 458, 509 -> return Finding(Verdict.MAX_CONNECTIONS, null, "Stream refused with HTTP ${s.httpStatus} (usually a connection limit).")
            in 400..599 -> return Finding(Verdict.SERVER_ERROR, null, "Stream returned HTTP ${s.httpStatus}.")
        }
        // A 1080p channel needs roughly 5-8 Mbit/s; under 3 will stutter.
        if (s.bytes > 64 * 1024 && s.kbps in 1 until 3000) return Finding(Verdict.SLOW_CONNECTION, null, "Stream downloaded at ${s.kbps} kbit/s.")
        return null
    }

    fun overall(findings: List<Finding>, internetOk: Boolean): Verdict {
        if (!internetOk) return Verdict.NO_INTERNET
        return findings.maxByOrNull { it.verdict.severity * 100 - it.verdict.ordinal }?.verdict ?: Verdict.OK
    }
}
