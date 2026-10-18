package app.familytv.iptv

import androidx.media3.common.PlaybackException
import app.familytv.iptv.ServerSwitch.Reason
import app.familytv.portal.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ServerSwitchTest {
    private val line = Source("a", "xtream", "Line", serverUrls = listOf("http://main.example:8080/", "http://backup.example:80"))

    @Test fun onlyServerSideFailuresCount() {
        val io = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertEquals(Reason("HTTP 503", network = false), ServerSwitch.classify(io, 503, null))
        assertEquals("HTTP 522", ServerSwitch.classify(io, 522, null)?.label)
        for (code in listOf(401, 403, 404, 451, 511, 513)) assertNull("$code", ServerSwitch.classify(io, code, null))
        val failed = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        assertEquals(Reason.CONNECTION, ServerSwitch.classify(failed, null, IOException(UnknownHostException("main.example"))))
        assertEquals(Reason.CONNECTION, ServerSwitch.classify(failed, null, IOException(ConnectException("refused"))))
        assertEquals(Reason.CONNECTION, ServerSwitch.classify(failed, null, SocketException("Connection reset")))
        assertEquals(Reason.TIMEOUT, ServerSwitch.classify(failed, null, IOException(SocketTimeoutException())))
        assertTrue(Reason.TIMEOUT.network && Reason.CONNECTION.network && !Reason.http(500).network)
        assertEquals(Reason.TIMEOUT, ServerSwitch.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null, null))
        assertNull(ServerSwitch.classify(PlaybackException.ERROR_CODE_DECODING_FAILED, null, null))
        assertNull(ServerSwitch.classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, null, IOException(SocketException())))
    }

    @Test fun threeInARowWithinTenMinutes() {
        val f = ServerSwitch.Failures()
        val t = 1_000_000L
        assertFalse(f.add("a", "main", t))
        assertFalse(f.add("a", "main", t + 60_000))
        assertTrue(f.add("a", "main", t + 120_000))
        // Spread out: the oldest falls out of the window.
        assertFalse(f.add("a", "main", t))
        assertFalse(f.add("a", "main", t + 9 * 60_000))
        assertFalse(f.add("a", "main", t + 11 * 60_000))
        // A play in between, or a failure on another server, starts the count again.
        f.clear("a")
        assertFalse(f.add("a", "main", t))
        assertFalse(f.add("a", "backup", t + 1))
        assertFalse(f.add("a", "backup", t + 2))
        assertTrue(f.add("a", "backup", t + 3))
    }

    @Test fun movesTheSameStreamToAnotherServer() {
        val url = "http://main.example:8080/live/user/pass/42.ts"
        assertEquals("http://main.example:8080", ServerSwitch.serverOf(line, url))
        assertEquals("http://backup.example:80/live/user/pass/42.ts", ServerSwitch.swap(url, "http://main.example:8080", "http://backup.example:80/"))
        assertNull(ServerSwitch.serverOf(line, "http://other.example/live/user/pass/42.ts"))
    }

    @Test fun withNothingSavedTheLineUsesItsUsualServer() {
        assertNull(ServerSwitch.chosen(line))
        assertEquals("http://backup.example:80", ServerSwitch.server(line, "http://backup.example:80"))
        assertEquals("http://main.example:8080", ServerSwitch.server(line, null))
    }
}
