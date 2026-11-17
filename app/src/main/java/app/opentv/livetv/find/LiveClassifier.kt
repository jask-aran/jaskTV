package app.opentv.livetv.find

import app.opentv.data.parser.ChannelNameNormalizer

/**
 * What a provider "live stream" actually is. Providers mix four things in one list:
 * real channels, numbered event slots renamed for each event, 24/7 single-show loops and
 * divider rows. Finding channels treats each differently.
 */
enum class LiveKind { CHANNEL, EVENT_SLOT, LOOP, HEADER }

/** Genres offered when browsing. Taken from IPTV-org categories when strictly matched, else the provider category. */
enum class LiveGenre(val label: String) {
    NEWS("News"), SPORTS("Sports"), ENTERTAINMENT("Entertainment"), MOVIES("Movies"), KIDS("Kids"),
    DOCUMENTARY("Documentary"), MUSIC("Music"), LIFESTYLE("Lifestyle"), LOCAL("Local stations"),
    RELIGIOUS("Religious"), OTHER("More channels"),
}

data class LiveClass(
    val kind: LiveKind,
    /** Provider region code, normalised (`UK` → `GB`). Unknown codes are kept, never dropped. */
    val region: String,
    /** Streaming-service bundle the provider files it under (`Prime Video`, `Tubi`…), a label only. */
    val service: String?,
    /** Genre from the provider's category wording; identity matching may refine it. */
    val genre: LiveGenre,
)

object LiveClassifier {

    fun classify(name: String, categoryName: String?): LiveClass {
        val category = ChannelNameNormalizer.foldSuperscripts(categoryName.orEmpty())
        val prefix = category.substringBefore('|', "").trim().uppercase()
        val theme = category.substringAfter('|', category).trim().uppercase()
        val folded = ChannelNameNormalizer.foldSuperscripts(name).trim()
        val region = region(prefix, theme, folded)
        return LiveClass(kind(folded, theme, prefix), region, service(theme, folded), genre(theme, folded))
    }

    // ---- Kind ---------------------------------------------------------------------------------

    private val HEADER = Regex("""#{3,}|^\s*[=*★-]{3,}""")
    private val EVENTISH_CATEGORY = Regex("""\b(PPV|EVENTS?|REPLAY)\b""")
    private val COUNTRY_LEAD = Regex("""^[A-Z]{2,3}\s*:\s*""")
    private val SLOT_WORDS = Regex("""\b(PPV|EVENT|NO EVENT|LIVE EVENT)\b|\|""", RegexOption.IGNORE_CASE)
    private val TRAILING_NUMBER = Regex("""\b\d{1,4}\s*$""")
    private val MATCHUP = Regex("""\b(vs?\.?|at)\s+\S|\s@\s""", RegexOption.IGNORE_CASE)

    private fun kind(name: String, theme: String, prefix: String): LiveKind = when {
        HEADER.containsMatchIn(name) -> LiveKind.HEADER
        name.startsWith("24/7") || theme.contains("24/7") || prefix == "24/7" -> LiveKind.LOOP
        EVENTISH_CATEGORY.containsMatchIn(theme) -> when {
            // Team and regional channels also sit in PPV categories ("US: MLB TAMPA BAY RAYS").
            COUNTRY_LEAD.containsMatchIn(name) && !SLOT_WORDS.containsMatchIn(name) &&
                !MATCHUP.containsMatchIn(name) && !TRAILING_NUMBER.containsMatchIn(name) -> LiveKind.CHANNEL
            else -> LiveKind.EVENT_SLOT
        }
        else -> LiveKind.CHANNEL
    }

    // ---- Region -------------------------------------------------------------------------------

    /** Category prefixes that are not a country: the channel name's own prefix decides. */
    private val MIXED = setOf("4K", "8K", "VIP", "TS", "WT", "ASIA", "FOR ADULTS", "24/7", "")

    /** Indian-language subcategories (providers file India under ASIA by language). */
    private val INDIA_THEMES = Regex(
        """HINDI|TAMIL|TELUGU|TELEGU|PUNJABI|MALAYALAM|MARATHI|KANNADA|BENGALI|BANGAL|GUJARATI|BHOJPURI|ODISHA|INDIA|BOLLYWOOD""")
    private val ASIA_THEMES = mapOf("PAKISTAN" to "PK", "BANGLADESH" to "BD", "NEPAL" to "NP", "SINHALA" to "LK", "UK" to "GB")
    private val NAME_PREFIX = Regex("""^([A-Z]{2})(?:-[A-Z]+)?\s*:""")

    fun normaliseRegion(code: String): String = when (code) {
        "UK" -> "GB"
        "USA" -> "US"
        else -> code
    }

    private fun region(prefix: String, theme: String, name: String): String {
        if (prefix == "ASIA") {
            ASIA_THEMES.entries.firstOrNull { theme.contains(it.key) }?.let { return it.value }
            if (INDIA_THEMES.containsMatchIn(theme)) return "IN"
        }
        if (prefix !in MIXED) return normaliseRegion(prefix)
        NAME_PREFIX.find(name.uppercase())?.let { return normaliseRegion(it.groupValues[1]) }
        return prefix.ifEmpty { "?" }
    }

    // ---- Service and genre --------------------------------------------------------------------

    private val SERVICES = listOf(
        "PRIME" to "Prime Video", "AT&T" to "AT&T", "TUBI" to "Tubi", "ROKU" to "Roku", "PEACOCK" to "Peacock",
        "NETFLIX" to "Netflix", "HULU" to "Hulu", "DISNEY+" to "Disney+", "HBO MAX" to "HBO Max",
        "PARAMOUNT+" to "Paramount+", "PLUTO" to "Pluto TV", "SAMSUNG" to "Samsung TV Plus", "YUPP" to "YuppTV",
        "BBC IPLAYER" to "BBC iPlayer", "ITV X" to "ITVX", "9NOW" to "9Now", "ONEPLAY" to "OnePlay",
    )

    private fun service(theme: String, name: String): String? =
        SERVICES.firstOrNull { (key, _) -> theme.contains(key) }?.second
            ?: if (name.uppercase().startsWith("RK:")) "Roku" else null

    private val CALL_SIGN = Regex("""\(([KW][A-Z]{2,3})(?:[-/ ][A-Z0-9-]+)?\)|\b[KW][A-Z]{2,3}-(?:DT|TV|LD|CD)\b""")
    private val NETWORK_THEME = Regex("""^(NBC|FOX|CBS|ABC|CW|MIAMI|TELEMUNDO NETWORK|SPECTRUM NETWORK)\b""")

    private val NEWS_T = Regex("""NEWS""")
    private val SPORTS_T = Regex("""SPORT|SOCCER|FOOTBALL|CRICKET|NBA|NFL|NHL|MLB|RACING|GOLF|TENNIS|RUGBY|AFL|NRL|UFC""")
    private val KIDS_T = Regex("""KIDS|CARTOON|CHILD|FAMILY""")
    private val MOVIES_T = Regex("""CINEMA|MOVIE|FILM""")
    private val DOCS_T = Regex("""DOCU|DOCS""")
    private val MUSIC_T = Regex("""MUSIC""")
    private val RELIGION_T = Regex("""RELIGI""")
    private val GENERAL_T = Regex("""ENTERTAINMENT|GENERAL""")

    private fun genre(theme: String, name: String): LiveGenre = when {
        CALL_SIGN.containsMatchIn(name.uppercase()) || NETWORK_THEME.containsMatchIn(theme) -> LiveGenre.LOCAL
        NEWS_T.containsMatchIn(theme) -> LiveGenre.NEWS
        SPORTS_T.containsMatchIn(theme) -> LiveGenre.SPORTS
        KIDS_T.containsMatchIn(theme) -> LiveGenre.KIDS
        MOVIES_T.containsMatchIn(theme) -> LiveGenre.MOVIES
        DOCS_T.containsMatchIn(theme) -> LiveGenre.DOCUMENTARY
        MUSIC_T.containsMatchIn(theme) -> LiveGenre.MUSIC
        RELIGION_T.containsMatchIn(theme) -> LiveGenre.RELIGIOUS
        GENERAL_T.containsMatchIn(theme) -> LiveGenre.ENTERTAINMENT
        else -> LiveGenre.OTHER
    }

    private val NAME_NEWS = Regex("""\bNEWS\b""", RegexOption.IGNORE_CASE)
    private val NAME_SPORTS = Regex("""\bSPORTS?\b|\bESPN|\bCRICKET\b|\bGOLF\b|\bRACING\b""", RegexOption.IGNORE_CASE)
    private val NAME_KIDS = Regex("""\bKIDS\b|CARTOON|\bJUNIOR\b|NICK\s?JR|DISNEY JR""", RegexOption.IGNORE_CASE)
    private val NAME_MOVIES = Regex("""\bMOVIES?\b|\bCINEMA\b|\bFILMS?\b""", RegexOption.IGNORE_CASE)

    /** Last resort when neither identity nor provider category says: obvious words in the channel's own name. */
    fun genreFromName(name: String): LiveGenre = when {
        NAME_NEWS.containsMatchIn(name) -> LiveGenre.NEWS
        NAME_SPORTS.containsMatchIn(name) -> LiveGenre.SPORTS
        NAME_KIDS.containsMatchIn(name) -> LiveGenre.KIDS
        NAME_MOVIES.containsMatchIn(name) -> LiveGenre.MOVIES
        else -> LiveGenre.OTHER
    }

    /** IPTV-org category ids → browse genre. The first recognised category wins. */
    fun genreOf(iptvOrgCategories: List<String>): LiveGenre? = iptvOrgCategories.firstNotNullOfOrNull { category ->
        when (category) {
            "news", "business", "weather", "legislative" -> LiveGenre.NEWS
            "sports", "outdoor", "auto" -> LiveGenre.SPORTS
            "kids", "animation" -> LiveGenre.KIDS
            "movies", "classic" -> LiveGenre.MOVIES
            "documentary", "science", "education", "culture" -> LiveGenre.DOCUMENTARY
            "music" -> LiveGenre.MUSIC
            "lifestyle", "cooking", "travel", "relax", "shop" -> LiveGenre.LIFESTYLE
            "religious" -> LiveGenre.RELIGIOUS
            "entertainment", "comedy", "series", "family", "general" -> LiveGenre.ENTERTAINMENT
            else -> null
        }
    }

    /** Display names for region codes; unknown codes show as themselves. `AR` is this provider family's Arabic group. */
    fun regionLabel(code: String): String = REGION_LABELS[code] ?: code

    private val REGION_LABELS = mapOf(
        "AU" to "Australia", "US" to "United States", "GB" to "United Kingdom", "IN" to "India", "CA" to "Canada",
        "NZ" to "New Zealand", "IE" to "Ireland", "PK" to "Pakistan", "BD" to "Bangladesh", "NP" to "Nepal",
        "LK" to "Sri Lanka", "AR" to "Arabic", "ES" to "Spain", "FR" to "France", "DE" to "Germany", "IT" to "Italy",
        "NL" to "Netherlands", "SE" to "Sweden", "DK" to "Denmark", "NO" to "Norway", "FI" to "Finland",
        "PT" to "Portugal", "PL" to "Poland", "TR" to "Turkey", "MX" to "Mexico", "BR" to "Brazil", "AL" to "Albania",
        "AT" to "Austria", "CH" to "Switzerland", "BE" to "Belgium", "GR" to "Greece", "IL" to "Israel",
        "IR" to "Iran", "RU" to "Russia", "CZ" to "Czechia", "HU" to "Hungary", "RO" to "Romania", "BG" to "Bulgaria",
        "HR" to "Croatia", "PH" to "Philippines", "TH" to "Thailand", "ID" to "Indonesia", "KR" to "Korea",
        "JP" to "Japan", "AFR" to "Africa", "ASIA" to "Asia (other)", "EXYU" to "Former Yugoslavia", "LA" to "Latin America",
    )
}
