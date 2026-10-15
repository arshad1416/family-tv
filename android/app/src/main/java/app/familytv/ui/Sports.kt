package app.familytv.ui

import app.familytv.data.CategoryEntity
import app.familytv.data.ChannelEntity

/**
 * The sports in Categories > Sports, in the order their tiles show. A live channel goes under the
 * first sport its own name names, else the one its category names; [More] holds the rest of the
 * general sports categories. Matching tries the more specific sports first ([MATCH_ORDER]), so
 * "NFL" isn't soccer's "football" and horse racing isn't motorsport.
 */
enum class Sport(val label: String, pattern: String?) {
    Hockey("Hockey", """\b(NHL|HOCKEY|WHL|OHL|AHL|QMJHL|LHJMQ|KHL|SHL|HNIC|STANLEY CUP)\b"""),
    AmericanFootball("Football (NFL & CFL)", """\b(NFL|CFL|NCAAF|XFL|UFL|AMERICAN FOOTBALL|COLLEGE FOOTBALL|RED ?ZONE|SUPER BOWL|GREY CUP)\b"""),
    Soccer(
        "Soccer",
        """⚽|\b(SOCCER|FOOTBALL|F[UÚ]TBOL|FUTEBOL|FU(SS|ẞ|ß)BALL|CALCIO|EPL|PREMIER LEAGUE|LA ?LIGA|LIGA|LIGUE ?1|SERIE [ABC]|BUNDESLIGA|MLS|UEFA|FIFA|""" +
            """CHAMPIONS|EUROPA LEAGUE|CONFERENCE LEAGUE|FA CUP|FA PLAYER|EFL|CARABAO|SPFL|SCOTTISH|EREDIVISIE|RFEF|COPA|COPPA|LEAGUE ONE|LEAGUE TWO|""" +
            """NATIONAL LEAGUE|CHAMPIONSHIP|NIFL|LOI|CONCACAF|CONMEBOL|LIBERTADORES|S[UÜ]PER ?LIG|ROSHN|PRIMEIRA|MATCH OF THE DAY|CYMRU|FFF|DFB)\b""",
    ),
    Basketball("Basketball", """\b(NBA|WNBA|NCAAB|BASKETBALL|BALONCESTO|BASKET|EUROLEAGUE|ACB|LNP|FIBA|MARCH MADNESS)\b"""),
    Baseball("Baseball", """\b(MLB|MILB|BASEBALL|BLUE JAYS|WORLD SERIES)\b"""),
    Cricket("Cricket", """\b(CRICKET|IPL|WILLOW|T20|ASHES)\b"""),
    Tennis("Tennis", """\b(TENNIS|ATP|WTA|WIMBLEDON|ROLAND GARROS)\b"""),
    Golf("Golf", """\b(GOLF|PGA|LPGA|THE MASTERS|RYDER CUP)\b"""),
    Motorsport(
        "Motorsport",
        """\b(F1|FORMULA (1|ONE|2)|MOTO ?GP|NASCAR|INDY ?CAR|RALLY|WRC|SUPERCROSS|MOTOCROSS|MXGP|TT RACES|RACING|DIRTVISION|DTM|WEC|LE MANS)\b""",
    ),
    Combat("Fighting (UFC, boxing, wrestling)", """\b(UFC|MMA|BOXING|BOXEO|BOXE|WWE|AEW|WRESTLING|MATCHROOM|TRILLER|PFL|BELLATOR|TOP RANK|FIGHT\w*|ONE CHAMPIONSHIP)\b"""),
    Rugby("Rugby", """RUGBY|\b(NRL|SUPER LEAGUE|SIX NATIONS|URC)\b"""),
    AussieRules("Aussie rules", """\b(AFL|AUSSIE RULES)\b"""),
    Gaelic("Gaelic games", """\b(GAA\w*|HURLING|GAELIC)\b"""),
    Darts("Darts & snooker", """\b(DARTS|PDC|SNOOKER|POOL)\b"""),
    HorseRacing("Horse racing", """\b(HORSE RACING|RIKSTOTO|RACING TV|TVG|SKY RACING|AT THE RACES)\b"""),
    Cycling("Cycling", """\b(CYCLING|CICLISMO|TOUR DE FRANCE|GCN)\b"""),
    Volleyball("Volleyball", """\bVOLLEY"""),
    More("More sports", null),
    ;

    internal val regex = pattern?.let { Regex(it, RegexOption.IGNORE_CASE) }

    companion object {
        /** Most specific first: each line names what it must win over. */
        private val MATCH_ORDER = listOf(
            HorseRacing, // before motorsport's RACING
            Combat, // ONE CHAMPIONSHIP before soccer
            Darts, Gaelic, Rugby, AussieRules, // GAA football, Super League before soccer
            AmericanFootball, // NFL before soccer's FOOTBALL
            Hockey, Basketball, Baseball, Cricket, Tennis, Golf, Cycling, Volleyball, Motorsport,
            Soccer, // LIGA, CHAMPIONS and FOOTBALL last
        )

        private val GENERAL = Regex("""SPORT|DEPORTE|DESPORTO|\bSPOR\b|\bTSN|\bESPN""", RegexOption.IGNORE_CASE)
        private val PPV = Regex("""\bPPV\b""", RegexOption.IGNORE_CASE)
        /** 24/7 lists loop films and shows, whatever their names say. */
        private val LOOPS = Regex("""\b24/7\b|24-7""")

        fun match(text: String): Sport? = MATCH_ORDER.firstOrNull { it.regex!!.containsMatchIn(text) }

        /** The sport of a channel named [channel] in a category named [category], or null when it isn't sport. */
        fun of(channel: String, category: String): Sport? =
            match(channel) ?: match(category) ?: More.takeIf { GENERAL.containsMatchIn(category) }

        /** Categories whose channels can hold sport: general sports ones, ones naming a sport, and PPV event lists. */
        fun isSportsCategory(name: String): Boolean =
            !LOOPS.containsMatchIn(name) && (GENERAL.containsMatchIn(name) || match(name) != null || PPV.containsMatchIn(name))
    }
}

/**
 * [channels] (from [categories], in the order the person sees them) grouped by sport, in [Sport]'s
 * order, keeping that category order then each category's own channel order within a sport.
 */
fun groupBySport(categories: List<CategoryEntity>, channels: List<ChannelEntity>): Map<Sport, List<ChannelEntity>> {
    val rank = categories.withIndex().associate { (i, c) -> (c.sourceId to c.categoryId) to i }
    val names = categories.associate { (it.sourceId to it.categoryId) to it.name }
    val out = sortedMapOf<Sport, MutableList<ChannelEntity>>()
    channels
        .sortedWith(compareBy({ rank[it.sourceId to it.categoryId] ?: Int.MAX_VALUE }, { it.num }, { it.name }))
        .forEach { ch ->
            val sport = Sport.of(ch.name, names[ch.sourceId to ch.categoryId] ?: return@forEach) ?: return@forEach
            out.getOrPut(sport) { mutableListOf() }.add(ch)
        }
    return out
}
