package app.familytv.iptv

import app.familytv.iptv.StreamQuality.Q
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamQualityTest {
    @Test
    fun qualityComesFromTheNameElseTheHeight() {
        assertEquals(Q.UHD, StreamQuality.of("EN - Dune (2021) 4K"))
        assertEquals(Q.UHD, StreamQuality.of("Dune UHD HDR"))
        assertEquals(Q.FHD, StreamQuality.of("CA: CTV FHD"))
        assertEquals(Q.FHD, StreamQuality.of("Dune.2021.1080p.WEB"))
        assertEquals(Q.HD, StreamQuality.of("CA: CTV HD"))
        assertEquals(Q.P720, StreamQuality.of("Dune 720p"))
        assertEquals(Q.SD, StreamQuality.of("CA: CTV SD"))
        assertEquals(Q.HD, StreamQuality.of("CA: CTVᴴᴰ"))
        assertEquals(Q.HD, StreamQuality.of("CA: CTV ʰᴰ"))
        assertEquals(Q.FHD, StreamQuality.of("CA: TSN ꜰʜᴅ"))
        assertEquals(Q.UHD, StreamQuality.of("CA: TSN ᵁᴴᴰ"))
        assertEquals(Q.UHD, StreamQuality.of("CA: TSN ⁴ᴷ"))
        assertNull(StreamQuality.of("Dune HDR"))
        assertNull(StreamQuality.of("Dune (2021)"))
        assertEquals(Q.UHD, StreamQuality.ofHeight(2160))
        assertEquals(Q.FHD, StreamQuality.ofHeight(1080))
        assertEquals(Q.P720, StreamQuality.ofHeight(720))
        assertEquals(Q.SD, StreamQuality.ofHeight(480))
        assertNull(StreamQuality.ofHeight(0))
    }

    @Test
    fun autoKeepsTodaysChoice() {
        assertEquals(-1, StreamQuality.pick(listOf(Q.SD, Q.UHD), QualityPref.AUTO))
    }

    @Test
    fun exactThenNearestLowerThenNearestHigher() {
        val copies = listOf(Q.SD, Q.FHD, Q.UHD)
        assertEquals(2, StreamQuality.pick(copies, QualityPref.UHD))
        assertEquals(1, StreamQuality.pick(copies, QualityPref.FHD))
        // No 720p: SD is the nearest lower.
        assertEquals(0, StreamQuality.pick(copies, QualityPref.P720))
        // Nothing lower: the nearest higher.
        assertEquals(1, StreamQuality.pick(listOf(Q.UHD, Q.FHD), QualityPref.P720))
    }

    @Test
    fun hdAndSevenTwentyShareARungButPreferTheirOwnLabel() {
        val copies = listOf(Q.P720, Q.HD, Q.FHD)
        assertEquals(1, StreamQuality.pick(copies, QualityPref.HD))
        assertEquals(0, StreamQuality.pick(copies, QualityPref.P720))
        assertEquals(0, StreamQuality.pick(listOf(Q.P720, Q.FHD), QualityPref.HD))
    }

    @Test
    fun anUntaggedCopyComesRightAfterAnExactMatch() {
        assertEquals(1, StreamQuality.pick(listOf(null, Q.UHD), QualityPref.UHD))
        assertEquals(0, StreamQuality.pick(listOf(null, Q.HD), QualityPref.UHD))
        assertEquals(0, StreamQuality.pick(listOf(null, Q.FHD), QualityPref.P720))
    }

    @Test
    fun channelVersionsShareAKey() {
        assertEquals("ca ctv", StreamQuality.channelKey("CA: CTV HD"))
        assertEquals("ca ctv", StreamQuality.channelKey("CA: CTV 4K"))
        assertEquals("ca ctv", StreamQuality.channelKey("CA| CTV FHD HEVC"))
        assertEquals("ca ctv east", StreamQuality.channelKey("CA: CTV HD East"))
        assertEquals("ca ctv", StreamQuality.channelKey("CA: CTVᴴᴰ"))
        assertEquals("ca ctv", StreamQuality.channelKey("CA: CTV ⁴ᴷ"))
        assertEquals("us ppv", StreamQuality.channelKey("US| PPV ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
        assertEquals("us ctv", StreamQuality.channelKey("US: CTV HD"))
        assertEquals("", StreamQuality.channelKey("HD"))
    }
}
