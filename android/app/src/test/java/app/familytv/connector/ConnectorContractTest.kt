package app.familytv.connector

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The connector contract (docs/CONNECTOR.md): what may never change in protocol 1, and the caps the host enforces. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ConnectorContractTest {
    private fun methods(name: String): List<String> =
        File("src/main/aidl/app/familytv/connector/v1/$name.aidl").readLines()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { Regex("^\\w+ \\w+\\(.*\\) = \\d+;$").matches(it) }

    @Test fun theV1InterfacesAreFrozen() {
        // Changing a line here breaks every released companion: add a method with the next number, or start v2.
        assertEquals(
            listOf(
                "void negotiate(String requestId, in Bundle hello, ISourceCallback callback) = 1;",
                "void listSources(String requestId, String sessionId, in Bundle identity, ISourceCallback callback) = 2;",
                "void resolve(String requestId, String sessionId, String selectionId, ISourceCallback callback) = 3;",
                "void cancel(String requestId) = 4;",
                "void closeSession(String sessionId) = 5;",
            ),
            methods("ISourceProvider"),
        )
        assertEquals(
            listOf(
                "void onResult(String requestId, in Bundle result) = 1;",
                "void onError(String requestId, int code, String detail) = 2;",
            ),
            methods("ISourceCallback"),
        )
        assertEquals("app.familytv.connector.SOURCE_PROVIDER_V1", Protocol.ACTION)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), ConnectorError.entries.map { it.code })
        assertEquals(ConnectorError.PROVIDER_ERROR, ConnectorError.of(99))
    }

    @Test fun negotiationTakesOnlyVersionOne() {
        val hello = Protocol.hello("0.2.73")
        assertEquals(1, hello.getInt("protocolMin"))
        assertEquals(1, hello.getInt("protocolMax"))
        fun answer(v: Int) = Bundle().apply { putInt("protocol", v); putString("providerName", "  Test\u0007 ") }
        assertEquals("Test", Protocol.negotiated(answer(1))?.providerName)
        assertNull(Protocol.negotiated(answer(2)))
        assertNull(Protocol.negotiated(Bundle()))
    }

    @Test fun anIdentityNeedsAnIdAndTheRightShape() {
        val movie = MediaIdentity(MediaIdentity.Type.MOVIE, tmdb = "10331", imdb = "tt0063350")
        assertEquals(movie, MediaIdentity.from(movie.toBundle()))
        val episode = MediaIdentity(MediaIdentity.Type.EPISODE, tvdb = "12345", season = 1, episode = 2)
        assertEquals(episode, MediaIdentity.from(episode.toBundle()))
        assertFalse("no id", MediaIdentity(MediaIdentity.Type.MOVIE).isValid)
        assertFalse("imdb shape", MediaIdentity(MediaIdentity.Type.MOVIE, imdb = "0063350").isValid)
        assertFalse("tmdb digits", MediaIdentity(MediaIdentity.Type.MOVIE, tmdb = "10331; drop").isValid)
        assertFalse("movie with a season", MediaIdentity(MediaIdentity.Type.MOVIE, tmdb = "1", season = 1).isValid)
        assertFalse("episode without one", MediaIdentity(MediaIdentity.Type.EPISODE, tvdb = "1", episode = 1).isValid)
        assertNull(MediaIdentity.from(Bundle().apply { putString("type", "show"); putString("tmdb", "1") }))
    }

    @Test fun sourcesAreCappedAndBadOnesDropped() {
        fun src(id: String?, title: String? = "Title", size: Long = 1) = Bundle().apply {
            putString("selectionId", id); putString("title", title); putLong("sizeBytes", size); putString("extra", "ignored")
        }
        val list = arrayListOf(
            src("a1", "x".repeat(500)),
            src("a1"),
            src("bad id"),
            src(null),
            src("no-title", null),
            src("neg", size = -5),
        )
        repeat(150) { list += src("n$it") }
        val parsed = Protocol.sources(Bundle().apply { putParcelableArrayList("sources", list) })
        assertEquals(Protocol.MAX_SOURCES, parsed.size)
        assertEquals(listOf("a1", "neg", "n0"), parsed.take(3).map { it.selectionId })
        assertEquals(Protocol.MAX_TITLE, parsed[0].title.length)
        assertNull(parsed[1].sizeBytes)
        assertTrue(Protocol.sources(Bundle()).isEmpty())
    }

    @Test fun onlyAnHttpUriWithCleanHeadersReachesThePlayer() {
        fun resolved(uri: String, headers: Bundle? = null) = Protocol.resolved(Bundle().apply { putString("uri", uri); headers?.let { putBundle("headers", it) } })
        assertNotNull(resolved("https://example.invalid/a.mp4"))
        assertNotNull(resolved("http://192.0.2.1:8096/a.mkv"))
        for (bad in listOf("file:///sdcard/a.mp4", "content://x/a", "javascript:alert(1)", "ftp://x/a", "https://x/a b", "https:x", "https://x/" + "a".repeat(5000))) {
            assertNull(bad, resolved(bad))
        }
        val headers = Bundle().apply {
            putString("Authorization", "Bearer synthetic")
            putString("Bad Name", "x")
            putString("X-Split", "a\r\nInjected: b")
            repeat(20) { putString("X-$it", "v") }
        }
        val r = resolved("https://example.invalid/a.mp4", headers)!!
        assertEquals(Protocol.MAX_HEADERS, r.headers.size)
        assertEquals("Bearer synthetic", r.headers["Authorization"])
        assertFalse(r.headers.containsKey("Bad Name") || r.headers.containsKey("X-Split"))
        // Never in a log line.
        assertFalse(r.toString().contains("example.invalid") || r.toString().contains("synthetic"))
    }

    @Test fun releaseBuildsApproveNoProvider() {
        assertTrue(ApprovedProviders.forBuild(debug = false, hostPackage = "app.familytv").isEmpty())
        val debug = ApprovedProviders.forBuild(debug = true, hostPackage = "app.familytv")
        assertEquals(listOf(ApprovedProvider(ApprovedProviders.REFERENCE_ADDON, "app.familytv", ApprovedProviders.SAME_AS_HOST)), debug)
    }

    @Test fun aProviderMustHaveExactlyThePinnedSigner() {
        val pin = "ab".repeat(32)
        val p = ApprovedProvider("x", "com.example.provider", pin.uppercase().chunked(2).joinToString(":"))
        assertTrue(ApprovedProviders.matches(p, listOf(pin), null))
        assertFalse("other key", ApprovedProviders.matches(p, listOf("cd".repeat(32)), null))
        assertFalse("two signers", ApprovedProviders.matches(p, listOf(pin, "cd".repeat(32)), null))
        assertFalse("not installed", ApprovedProviders.matches(p, null, null))
        val same = p.copy(certSha256 = ApprovedProviders.SAME_AS_HOST)
        assertTrue(ApprovedProviders.matches(same, listOf(pin), listOf(pin)))
        assertFalse(ApprovedProviders.matches(same, listOf(pin), listOf("cd".repeat(32))))
        assertFalse("host unknown", ApprovedProviders.matches(same, listOf(pin), null))
    }
}
