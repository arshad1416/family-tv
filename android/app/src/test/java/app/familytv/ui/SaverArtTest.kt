package app.familytv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaverArtTest {
    @Test
    fun spaceAndEarthAreNasaPhotosEachOnce() {
        val pictures = SpaceArt.pictures
        assertTrue(pictures.size >= 20)
        assertEquals(pictures.size, pictures.map { it.url }.distinct().size)
        pictures.forEach {
            assertTrue(it.url, it.url.matches(Regex("""https://images-assets\.nasa\.gov/image/([\w-]+)/\1~large\.jpg""")))
            assertTrue(it.caption.orEmpty().endsWith(" · NASA"))
        }
    }
}
