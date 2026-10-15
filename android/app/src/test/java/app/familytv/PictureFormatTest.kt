package app.familytv

import app.familytv.player.boxMargins
import org.junit.Assert.assertEquals
import org.junit.Test

class PictureFormatTest {
    @Test
    fun forcedShapesFitTheLargestCentredBox() {
        // 4:3 on a 1920x1080 screen: pillarbox 1440 wide.
        assertEquals(240 to 0, boxMargins(1920, 1080, 4f / 3f))
        // 16:9 on a 16:9 screen: nothing to trim.
        assertEquals(0 to 0, boxMargins(1920, 1080, 16f / 9f))
        // 16:9 on a portrait phone: letterbox 1080x608 in 1080x2400.
        assertEquals(0 to 896, boxMargins(1080, 2400, 16f / 9f))
        // Not laid out yet.
        assertEquals(0 to 0, boxMargins(0, 0, 4f / 3f))
    }
}
