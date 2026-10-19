package app.familytv

import app.familytv.diag.AccountFacts
import app.familytv.diag.Classifier
import app.familytv.diag.Finding
import app.familytv.diag.HostFacts
import app.familytv.diag.StreamFacts
import app.familytv.diag.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClassifierTest {
    private fun host(
        system: List<String>? = listOf("203.0.113.5"),
        public: List<String>? = listOf("203.0.113.5"),
        tcp: Boolean? = true,
        portal: Boolean? = true,
        status: Int? = 200,
        html: Boolean = false,
    ) = HostFacts("http://line.example:8080", "line.example:8080", system, public, tcp, null, portal, status, html)

    @Test fun healthyHostHasNoFinding() = assertNull(Classifier.host(host()))

    @Test fun adguardStyleSinkholeIsDnsFiltered() {
        assertEquals(Verdict.DNS_FILTERED, Classifier.host(host(system = listOf("0.0.0.0"), tcp = null))!!.verdict)
        assertEquals(Verdict.DNS_FILTERED, Classifier.host(host(system = null, tcp = null))!!.verdict)
    }

    @Test fun deadDomainIsDnsNotFound() =
        assertEquals(Verdict.DNS_NOT_FOUND, Classifier.host(host(system = null, public = emptyList(), tcp = null))!!.verdict)

    @Test fun localConnectFailureWhilePortalReachesIsNetworkBlocked() =
        assertEquals(Verdict.NETWORK_BLOCKED, Classifier.host(host(tcp = false, portal = true))!!.verdict)

    @Test fun bothFailingIsServerDown() =
        assertEquals(Verdict.SERVER_DOWN, Classifier.host(host(tcp = false, portal = false))!!.verdict)

    @Test fun http451IsNetworkBlocked() = assertEquals(Verdict.NETWORK_BLOCKED, Classifier.host(host(status = 451))!!.verdict)

    @Test fun blockPageIsNetworkBlocked() = assertEquals(Verdict.NETWORK_BLOCKED, Classifier.host(host(html = true))!!.verdict)

    @Test fun accountChecks() {
        val now = 1_800_000_000_000L
        assertEquals(Verdict.BAD_LOGIN, Classifier.account(AccountFacts(false, null, null, null, null), now, false)!!.verdict)
        assertEquals(Verdict.EXPIRED, Classifier.account(AccountFacts(true, "Expired", null, null, null), now, false)!!.verdict)
        assertEquals(Verdict.EXPIRED, Classifier.account(AccountFacts(true, "Active", now - 1, 0, 1), now, false)!!.verdict)
        assertEquals(Verdict.MAX_CONNECTIONS, Classifier.account(AccountFacts(true, "Active", now + 1, 1, 1), now, false)!!.verdict)
        // This TV's own stream does not count against it.
        assertNull(Classifier.account(AccountFacts(true, "Active", now + 1, 1, 1), now, true))
        assertNull(Classifier.account(AccountFacts(true, "Active", null, 0, 2), now, false))
    }

    @Test fun streamChecks() {
        assertEquals(Verdict.SLOW_CONNECTION, Classifier.stream(StreamFacts(200, 500_000, 2_000, null), true)!!.verdict) // 2 Mbit/s
        assertNull(Classifier.stream(StreamFacts(200, 1_500_000, 1_000, null), true)) // 12 Mbit/s
        assertEquals(Verdict.MAX_CONNECTIONS, Classifier.stream(StreamFacts(403, 0, 100, null), true)!!.verdict)
        assertEquals(Verdict.NETWORK_BLOCKED, Classifier.stream(StreamFacts(null, 0, 100, "SocketException"), true)!!.verdict)
    }

    @Test fun rootPageServerErrorIsDroppedWhenTheStreamPlays() {
        // The Oct 18 report: the server answers 520 on "/" but the stream test got 200, 1.5 MB in 656 ms.
        val root = Classifier.host(host(status = 520))!!
        assertEquals(Verdict.SERVER_ERROR, root.verdict)
        val played = StreamFacts(200, 1_572_864, 656, null)
        assertNull(Classifier.stream(played, true))
        assertEquals(emptyList<Finding>(), Classifier.streamPlayed(listOf(root), "line.example:8080", played))
        // A slow stream still clears the guess but reports itself.
        val slow = StreamFacts(200, 500_000, 2_000, null)
        val settled = Classifier.streamPlayed(listOf(root), "line.example:8080", slow) + listOfNotNull(Classifier.stream(slow, true))
        assertEquals(Verdict.SLOW_CONNECTION, Classifier.overall(settled, true))
        // A stream that failed, or another server's error, still counts.
        assertEquals(listOf(root), Classifier.streamPlayed(listOf(root), "line.example:8080", StreamFacts(200, 0, 656, null)))
        assertEquals(listOf(root), Classifier.streamPlayed(listOf(root), "line.example:8080", StreamFacts(502, 0, 100, null)))
        assertEquals(listOf(root), Classifier.streamPlayed(listOf(root), "backup.example:80", played))
    }

    @Test fun overallPicksMostSevereAndNoInternetWins() {
        val f = listOf(Finding(Verdict.SLOW_CONNECTION, null, ""), Finding(Verdict.SERVER_DOWN, null, ""), Finding(Verdict.DNS_FILTERED, null, ""))
        assertEquals(Verdict.DNS_FILTERED, Classifier.overall(f, true))
        assertEquals(Verdict.NO_INTERNET, Classifier.overall(f, false))
        assertEquals(Verdict.OK, Classifier.overall(emptyList(), true))
    }

    @Test fun connectCheckTriesIpv4FirstAndSkipsSinkholes() {
        val sys = listOf("2606:4700:3032::ac43:b39e", "0.0.0.0", "104.21.31.196", "172.67.179.158")
        assertEquals(listOf("104.21.31.196", "172.67.179.158", "2606:4700:3032::ac43:b39e"), Classifier.connectOrder(sys, null))
        assertEquals(listOf("198.51.100.7"), Classifier.connectOrder(listOf("0.0.0.0"), listOf("198.51.100.7")))
        assertEquals(emptyList<String>(), Classifier.connectOrder(null, null))
    }
}
