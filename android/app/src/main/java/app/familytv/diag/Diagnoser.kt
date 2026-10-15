package app.familytv.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import app.familytv.BuildConfig
import app.familytv.core.Http
import app.familytv.core.redact
import app.familytv.data.AppDb
import app.familytv.iptv.XtreamParser
import app.familytv.iptv.XtreamUrls
import app.familytv.portal.PortalClient
import app.familytv.portal.Profile
import app.familytv.portal.Source
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class DiagnosisReport(
    val verdict: Verdict,
    val summary: String,
    val findings: List<Finding>,
    val hosts: List<HostFacts>,
    val stream: StreamFacts?,
    val device: Map<String, Any?>,
    val ranAt: Long,
)

/**
 * Works out why playback fails on this TV: no internet, a DNS filter on the home network,
 * a network/ISP block, a dead or changed server, or a problem with the line itself.
 * Takes about 10-20 seconds. Never sends passwords anywhere.
 */
class Diagnoser(private val context: Context, private val db: AppDb, private val portal: PortalClient) {

    suspend fun run(profile: Profile, playingHere: Boolean = false): DiagnosisReport = withContext(Dispatchers.IO) {
        val internetOk = internetReachable()
        val hostFacts = mutableListOf<HostFacts>()
        val findings = mutableListOf<Finding>()
        var streamFacts: StreamFacts? = null

        if (internetOk) {
            val urls = profile.sources.flatMap { s -> s.serverUrls.orEmpty() + listOfNotNull(s.m3uUrl, s.epgUrl) }.distinctBy { hostOf(it) }
            val portalView = runCatching { portal.reachability(urls) }.getOrNull()
                ?.associateBy { it.url.toHttpUrlOrNull()?.let { u -> "${u.host}:${u.port}" } }

            hostFacts += coroutineScope {
                urls.map { url -> async { probeHost(url, portalView?.get(hostOf(url))?.ok) } }.awaitAll()
            }
            hostFacts.forEach { f -> Classifier.host(f)?.let(findings::add) }

            for (s in profile.sources.filter { it.type == "xtream" }) {
                val server = workingServer(s, hostFacts) ?: continue
                accountCheck(s, server)?.let { acc -> Classifier.account(acc, System.currentTimeMillis(), playingHere)?.let(findings::add) }
                if (!playingHere && findings.none { it.verdict.severity >= 3 }) {
                    streamFacts = streamCheck(s, server)
                    val portalOk = hostFacts.firstOrNull { it.host == hostOf(server) }?.portalReachable
                    streamFacts?.let { Classifier.stream(it, portalOk)?.let(findings::add) }
                }
            }
        }

        val verdict = Classifier.overall(findings, internetOk)
        val summary = buildString {
            append(verdict.tvMessage)
            findings.firstOrNull { it.verdict == verdict }?.let { append(" (").append(it.detail).append(')') }
        }
        DiagnosisReport(verdict, summary, findings, hostFacts, streamFacts, deviceInfo(), System.currentTimeMillis())
    }

    /** Runs a check and uploads it; the upload is best effort (no internet = nothing to upload to). */
    suspend fun runAndReport(profile: Profile, playingHere: Boolean = false): DiagnosisReport {
        val r = run(profile, playingHere)
        runCatching { portal.uploadDiagnostics(r.verdict.name, r) }
        return r
    }

    private fun internetReachable(): Boolean {
        // Cloudflare's IP-addressed endpoint needs no DNS, so it separates "no internet" from "DNS broken".
        for (url in listOf("https://1.1.1.1/cdn-cgi/trace", "https://8.8.8.8/")) {
            val ok = runCatching {
                Http.probeClient.newCall(Request.Builder().url(url).head().build()).execute().use { true }
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private suspend fun probeHost(url: String, portalReachable: Boolean?): HostFacts {
        val u = url.toHttpUrlOrNull() ?: return HostFacts(url, url, null, null, null, "bad url", portalReachable)
        val hostPort = "${u.host}:${u.port}"
        val system = withTimeoutOrNull(6000) { runCatching { InetAddress.getAllByName(u.host).map { it.hostAddress!! } }.getOrNull() }
        val public = publicDns(u.host)
        var tcpOk: Boolean? = null
        var tcpError: String? = null
        for (target in Classifier.connectOrder(system, public)) {
            try {
                Socket().use { it.connect(InetSocketAddress(target, u.port), 4000) }
                tcpOk = true
                tcpError = null
                break
            } catch (e: Exception) {
                tcpOk = false
                tcpError = e.javaClass.simpleName
            }
        }
        var status: Int? = null
        var html = false
        if (tcpOk == true && system != null && !Classifier.isSinkhole(system)) {
            runCatching {
                Http.probeClient.newCall(Request.Builder().url("${u.scheme}://$hostPort/").build()).execute().use { res ->
                    status = res.code
                    html = res.header("Content-Type").orEmpty().contains("html") &&
                        res.peekBody(4096).string().contains(Regex("(?i)blocked|court order|restricted|access denied"))
                }
            }
        }
        return HostFacts(redact(url), hostPort, system, public, tcpOk, tcpError, portalReachable, status, html)
    }

    /** DNS-over-HTTPS by IP address, so the home network's DNS (and any filter on it) is bypassed for this lookup only. */
    private fun publicDns(host: String): List<String>? {
        if (host.matches(Regex("^[0-9.]+$")) || host.contains(':')) return listOf(host)
        for (endpoint in listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/resolve")) {
            val result = runCatching {
                val req = Request.Builder().url("$endpoint?name=$host&type=A").header("Accept", "application/dns-json").build()
                Http.probeClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@use null
                    val json = JsonParser.parseString(res.body!!.string()).asJsonObject
                    val answers = json.getAsJsonArray("Answer") ?: return@use emptyList()
                    answers.mapNotNull { a -> a.asJsonObject.takeIf { it.get("type")?.asInt == 1 }?.get("data")?.asString }
                }
            }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    private fun workingServer(s: Source, facts: List<HostFacts>): String? =
        s.serverUrls.orEmpty().firstOrNull { url ->
            val f = facts.firstOrNull { it.host == hostOf(url) }
            f == null || (f.tcpOk == true && !Classifier.isSinkhole(f.systemDns))
        }

    private fun accountCheck(s: Source, server: String): AccountFacts? = runCatching {
        Http.probeClient.newCall(Request.Builder().url(XtreamUrls.api(server, s.username!!, s.password.orEmpty())).build()).execute().use { res ->
            if (!res.isSuccessful) return@use null
            val a = XtreamParser.account(res.body!!.charStream())
            AccountFacts(a.auth, a.status, a.expiresAt, a.activeConnections, a.maxConnections)
        }
    }.getOrNull()

    /** Downloads up to ~1.5 MB of one live channel to measure real throughput to the stream host. */
    private suspend fun streamCheck(s: Source, server: String): StreamFacts? {
        val ch = db.catalog().anyChannel(s.id) ?: return null
        val url = XtreamUrls.live(server, s.username!!, s.password.orEmpty(), ch.streamId)
        val started = System.currentTimeMillis()
        var bytes = 0L
        return try {
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) return StreamFacts(res.code, 0, System.currentTimeMillis() - started, null)
                val buf = ByteArray(32 * 1024)
                val input = res.body!!.byteStream()
                val firstByteAt = System.currentTimeMillis()
                while (bytes < 1_500_000 && System.currentTimeMillis() - firstByteAt < 6000) {
                    val n = input.read(buf)
                    if (n < 0) break
                    bytes += n
                }
                StreamFacts(res.code, bytes, System.currentTimeMillis() - firstByteAt, null)
            }
        } catch (e: Exception) {
            StreamFacts(null, bytes, System.currentTimeMillis() - started, e.javaClass.simpleName)
        }
    }

    private fun deviceInfo(): Map<String, Any?> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val link = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            else -> "other"
        }
        val dnsServers = runCatching { cm?.getLinkProperties(cm.activeNetwork)?.dnsServers?.map { it.hostAddress } }.getOrNull()
        return mapOf(
            "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to Build.VERSION.RELEASE,
            "app" to BuildConfig.VERSION_NAME,
            "network" to link,
            "downMbps" to caps?.linkDownstreamBandwidthKbps?.div(1000),
            "dnsServers" to dnsServers,
            "privateDns" to runCatching { if (Build.VERSION.SDK_INT >= 28) cm?.getLinkProperties(cm.activeNetwork)?.isPrivateDnsActive else null }.getOrNull(),
        )
    }

    private fun hostOf(url: String) = url.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" } ?: url
}
