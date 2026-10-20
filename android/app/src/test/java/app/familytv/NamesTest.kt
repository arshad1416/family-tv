package app.familytv

import app.familytv.data.Names
import app.familytv.data.SearchText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NamesTest {
    @Test fun styledLettersReadAsPlainOnes() {
        assertEquals("RAW 60fps", Names.clean("ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
        assertEquals("CA: TSN 1 HD", Names.clean("CA: ᴛꜱɴ 1 ᴴᴰ"))
        assertEquals("Plain name", Names.clean("Plain name"))
        assertEquals(listOf("raw", "60fps"), SearchText.words("ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
    }

    @Test fun channelNamesLoseCountryAndLanguagePrefixes() {
        assertEquals("CBC", Names.channel("CA EN: CBC"))
        assertEquals("TSN 1 HD", Names.channel("CA: ᴛꜱɴ 1 ᴴᴰ"))
        assertEquals("Sky Sports", Names.channel("[UK] Sky Sports"))
        assertEquals("ESPN", Names.channel("US| ESPN"))
        assertEquals("Star Plus", Names.channel("IN/HI - Star Plus"))
        assertEquals("TSN: Live", Names.channel("TSN: Live"))
        assertEquals("CNN", Names.channel("CNN"))
        assertEquals("ABC: News", Names.channel("ABC: News"))
    }

    @Test fun headingRowsAreSeparators() {
        for (n in listOf("##### 4K #####", "#### SPORTS ####", "######", "# - #", "### NEWS", "  ## UK ##  ")) assertTrue(n, Names.isSeparator(n))
        for (n in listOf("#1 Hits", "CA: TSN 1", "Sports #2", "------", "# Kids")) assertFalse(n, Names.isSeparator(n))
    }
}
