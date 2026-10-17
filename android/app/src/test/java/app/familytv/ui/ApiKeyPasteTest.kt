package app.familytv.ui

import org.junit.Assert.*
import org.junit.Test

class ApiKeyPasteTest {
    @Test fun acceptsBoundedKeyAndTrimsOnlySurroundingWhitespace() {
        assertEquals("synthetic-key-0123456789", pastedApiKey(" \nsynthetic-key-0123456789\r\n"))
        assertEquals("x".repeat(512), pastedApiKey("x".repeat(512)))
    }
    @Test fun rejectsMissingOversizedMultilineAndControlContent() {
        for (value in listOf(null, "", "short", "x".repeat(513), "x".repeat(4097), "synthetic key 0123456789", "synthetic-key\n0123456789", "synthetic-key-\u00000123456789", "https://example.test/key")) {
            assertNull(pastedApiKey(value))
        }
    }
}
