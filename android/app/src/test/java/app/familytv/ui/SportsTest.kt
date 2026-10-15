package app.familytv.ui

import app.familytv.data.CategoryEntity
import app.familytv.data.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsTest {
    @Test
    fun channelNameWinsOverItsCategory() {
        assertEquals(Sport.Basketball, Sport.of("beIN Sports NBA", "AR| BEIN SPORTS ⚽"))
        assertEquals(Sport.Soccer, Sport.of("beIN Sports 1", "AR| BEIN SPORTS ⚽"))
        assertEquals(Sport.Motorsport, Sport.of("Sky Sports F1", "UK| SPORT ᴴᴰ"))
        assertEquals(Sport.Soccer, Sport.of("Sky Sports Premier League", "UK| SPORT ᴴᴰ"))
        assertEquals(Sport.More, Sport.of("TSN 1", "CA| SPORTS EN"))
    }

    @Test
    fun specificSportsBeatTheBroadOnes() {
        assertEquals(Sport.AmericanFootball, Sport.of("NFL RedZone", "US| SPORT"))
        assertEquals(Sport.AmericanFootball, Sport.of("CFL 02", "CA| CFL PPV"))
        assertEquals(Sport.Hockey, Sport.of("Leafs vs Habs", "US| NHL TEAM PPV"))
        assertEquals(Sport.HorseRacing, Sport.of("Racing TV", "UK| SPORT ᴴᴰ"))
        assertEquals(Sport.Motorsport, Sport.of("Event 3", "US| FLO RACING PPV"))
        assertEquals(Sport.Rugby, Sport.of("Wigan v Leeds", "UK| SUPER LEAGUE PLUS PPV"))
        assertEquals(Sport.Gaelic, Sport.of("Tyrone v Derry", "IE| TYRONE GAA PPV"))
        assertEquals(Sport.Basketball, Sport.of("Real Madrid v Barça", "ES| M+ BALONCESTO ᴿᴬᵂ"))
        assertEquals(Sport.Soccer, Sport.of("Real Madrid v Barça", "ES| M+ LALIGA VIP"))
        assertEquals(Sport.Golf, Sport.of("Featured group", "US| THE MASTERS PPV"))
    }

    @Test
    fun ppvListsKeepOnlyChannelsThatNameASport() {
        assertEquals(Sport.Combat, Sport.of("PPV 01: UFC 320", "US| PPV EVENT"))
        assertNull(Sport.of("PPV 05", "US| PPV EVENT"))
        assertNull(Sport.of("Stranger Things", "US| NETFLIX PPV"))
    }

    @Test
    fun picksTheSportsCategories() {
        listOf("CA| SPORTS EN", "US| NHL PPV", "UK| PPV EVENT", "AR| BEIN SPORTS ⁸ᴷ & SD ⚽", "TS| TENNIS LIVE", "ES| MOVISTAR DEPORTES VIP", "PT| DESPORTO")
            .forEach { assertTrue(it, Sport.isSportsCategory(it)) }
        listOf("CA| NEWS EN", "US| 24/7 PPV ᴿᴬᵂ ⁶⁰ᶠᵖˢ", "DE| 24/7 MAGENTA SERIE 4K", "IT| 24/7 MOVIES & SERIES", "US| KIDS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ")
            .forEach { assertFalse(it, Sport.isSportsCategory(it)) }
    }

    @Test
    fun groupsFollowSportOrderThenCategoryOrder() {
        val cats = listOf(
            CategoryEntity("s", "live", "2", "CA| SPORTS EN", 0, 0),
            CategoryEntity("s", "live", "1", "US| NHL PPV", 1, 0),
        )
        fun ch(id: String, name: String, cat: String, num: Int) = ChannelEntity("s", id, name, num, null, cat, null, null, 0, 0)
        val groups = groupBySport(cats, listOf(ch("a", "NHL 01", "1", 1), ch("b", "TSN 1", "2", 5), ch("c", "Sportsnet NHL", "2", 9), ch("d", "Other", "9", 1)))
        assertEquals(listOf(Sport.Hockey, Sport.More), groups.keys.toList())
        // CA| SPORTS EN comes first in the person's order, so its hockey channel leads.
        assertEquals(listOf("Sportsnet NHL", "NHL 01"), groups[Sport.Hockey]!!.map { it.name })
        assertEquals(listOf("TSN 1"), groups[Sport.More]!!.map { it.name })
    }
}
