package app.opentv.ui.channels

import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.livetv.find.LiveFinder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/*
 * How Live TV and Sports write times and provider names. Display only: stored names and slot names are never changed
 * (matching reads the provider's own text).
 */

private val CLOCK = DateTimeFormatter.ofPattern("h:mm a")
private val WEEKDAY = DateTimeFormatter.ofPattern("EEE")
private val WEEKDAY_DATE = DateTimeFormatter.ofPattern("EEE d MMM")

/** "9:00 am". */
internal fun liveClock(utc: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    CLOCK.format(Instant.ofEpochMilli(utc).atZone(zone)).lowercase()

/**
 * When something starts, one way everywhere in Live TV and Sports: "9:00 am" today, "Fri 9:00 am" within six days
 * either side, "Fri 27 Nov 9:00 am" beyond. [dayWords]: "Tomorrow 9:00 am" and "Yesterday 9:00 am" for those days
 * (off for tight chips, where every day reads the same way).
 */
internal fun liveWhen(utc: Long, zone: ZoneId = ZoneId.systemDefault(), dayWords: Boolean = true): String {
    val day = Instant.ofEpochMilli(utc).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val prefix = when {
        day == today -> null
        dayWords && day == today.plusDays(1) -> "Tomorrow"
        dayWords && day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) && day.isBefore(today.plusDays(7)) -> WEEKDAY.format(day)
        else -> WEEKDAY_DATE.format(day)
    }
    return listOfNotNull(prefix, liveClock(utc, zone)).joinToString(" ")
}

// ---- Names ------------------------------------------------------------------------------------

/** "Live | Honda Indy 200 …", "Next | …": a slot's state word, not part of its name. */
private val STATE_LEAD = Regex("""^\s*(LIVE|NEXT|END|ENDED|UPCOMING|SOON|NOW)\s*[|:]\s*""", RegexOption.IGNORE_CASE)
/** "(2026-04-11 01:00:07)", "start:2026-11-15 23:25:00 stop:…", "16-11-2026", "14:15 (GMT)". */
private val TIMESTAMP = Regex("""\(\s*20\d\d-\d\d-\d\d[^()]*\)|\bstart:\s*20\d\d-.*$|\b\d\d-\d\d-20\d\d\b|\b\d{1,2}:\d\d\s*(\((GMT|UTC|ET|EST|PT)\))?""", RegexOption.IGNORE_CASE)
/** Pipe sections that carry no name: "8K EXCLUSIVE", "all", "DK: VIAPLAY PPV 56", "(MX) (Disney 018)". */
private val NOISE_SECTION = Regex("""^(\d+K\s+EXCLUSIVE|all|no event|[A-Z]{2,3}:\s.*\b\d{1,3}|([A-Z]{2,3}\s*)?(\([^()]*\)\s*)+)$""", RegexOption.IGNORE_CASE)
/** "(MX)", "(ESP)", "(A)", "(F)": a country, language or feed letter in brackets. Not a call sign ("(WHO)", "(WLS)"). */
private val BRACKET_CODE = Regex("""\s*[(\[]\s*(?![KW][A-Z]{2}\s*[)\]])[A-Z]{1,3}\s*[)\]]""")
/** A leading service, region or slot tag: "US:", "F1:", "flolive:", "UFC 00 :", "MOTOGP EVENT 05:". */
private val LEAD_TAG = Regex("""^\s*[A-Za-z0-9&+]{1,10}(?:-[A-Za-z]+)?(?:\s+(?:EVENTO?|PPV|SLOT|CH))?(?:\s*\d{1,3})?\s*:\s*(?=\S)""")
private val LEAD_YEAR = Regex("""^20\d\d\s+""")
/** A stray feed letter at the end: "NBC WTVJ Miami F", "NBC 10 WCAU Trenton H". */
private val TRAILING_LETTER = Regex("""(\S{2,}\s+\S+)\s+[A-Z]$""")
/** "SP⚽RTS" (a ball for the O) and other pictographs. */
private val BALL_O = Regex("""(?<=[A-Za-z])[⚽🏀🏈⚾🎾](?=[A-Za-z])""")
private val PICTOGRAPHS = Regex("""[^\p{L}\p{N}\p{P}\p{Zs}+&$£€|]""")
private val SP_RTS = Regex("""\bSP\s+RTS\b""", RegexOption.IGNORE_CASE)
private val SPACES = Regex("""\s+""")
private val EDGE_PUNCT = Regex("""^[\s\-–|:·,]+|[\s\-–|:·,]+$""")

/** Brands whose own spelling plain title case would lose. */
private val BRANDS = mapOf("BEIN" to "beIN", "MOTOGP" to "MotoGP", "INDYCAR" to "IndyCar", "NASCAR" to "NASCAR",
    "ESPNEWS" to "ESPNews", "DAZN" to "DAZN", "VIAPLAY" to "Viaplay", "EUROSPORT" to "Eurosport")

private val nameCache = ConcurrentHashMap<String, String>()

/**
 * A provider's channel or slot name as it should read on a tile. Every name: no quality tags, bracketed codes
 * ("(MX)", "(A)"), "SP⚽RTS" read as "Sports", a local station's stray feed letter ("NBC WTVJ Miami F"), and shouting
 * in title case. Event slots ([slot], or any name with "|" sections) also lose state words ("Live |"), slot numbers
 * ("UFC 00 :"), leading tags and timestamps: "Live | Honda Indy 200 at Mid-Ohio | … | DK: VIAPLAY PPV 56" →
 * "Honda Indy 200 at Mid-Ohio". Ordinary channel names keep their colons and endings ("CSI: Miami", "TV 2 Sport X").
 * Falls back to the name as given when cleaning would leave nothing.
 */
internal fun liveName(raw: String, slot: Boolean = false): String {
    if (raw.isBlank()) return raw
    // A slot family's short code ("FLO" from "US| FLO VIP PPV") is a service.
    if (raw.trim().equals("FLO", ignoreCase = true)) return "FloSports"
    val full = slot || '|' in raw
    val cacheKey = if (full) "|$raw" else raw
    nameCache[cacheKey]?.let { return it }
    val cleaned = runCatching { clean(raw, full) }.getOrNull()?.takeIf { it.any(Char::isLetterOrDigit) } ?: raw.trim()
    if (nameCache.size > 4000) nameCache.clear()
    nameCache[cacheKey] = cleaned
    return cleaned
}

private fun clean(raw: String, slot: Boolean): String {
    var text = ChannelNameNormalizer.foldSuperscripts(raw).replace(BALL_O, "O").replace(PICTOGRAPHS, " ")
    if (slot) text = slotText(text)
    text = BRACKET_CODE.replace(text, "")
    // Quality and stream tags at the end ("HD", "RAW", "4K", "60FPS", "VIP").
    var words = SPACES.split(text.trim()).filter { it.isNotEmpty() }
    while (words.size > 1 && words.last().let { w -> ChannelNameNormalizer.qualityRankOfToken(w) != null || ChannelNameNormalizer.isStreamMarker(w) || w.equals("VIP", true) })
        words = words.dropLast(1)
    text = words.joinToString(" ")
    // Only a local station's (a network and a call sign): elsewhere a last letter can be the name's own ("Formula E").
    if (LiveFinder.callSign(text) != null) text = TRAILING_LETTER.replace(text) { it.groupValues[1] }
    text = SP_RTS.replace(text, "Sports")
    text = EDGE_PUNCT.replace(text, "")
    return tidyCase(text)
}

/** An event slot's name down to the event: state word, the section that names something, no tags or timestamps. */
private fun slotText(raw: String): String {
    var text = STATE_LEAD.replace(raw, "")
    // Slot names are pipe sections: keep the first that names something.
    val sections = text.split('|').map { TIMESTAMP.replace(it, " ").trim() }.filter { it.isNotEmpty() }
    if (sections.size > 1) text = sections.firstOrNull { !NOISE_SECTION.matches(it) } ?: text
    text = TIMESTAMP.replace(text, " ")
    // Up to two leading tags ("US: NBA TV", "F1: F1-TV"), each only if something is left after it.
    repeat(2) { LEAD_TAG.find(text)?.let { m -> text.substring(m.range.last + 1).takeIf { it.any(Char::isLetter) }?.let { text = it } } }
    text = LEAD_YEAR.replace(text.trim(), "")
    // A repeated name in brackets ("Hacken vs Man City (ESP) (Hacken vs Man City (ESP))"): codes first, then the copy.
    text = BRACKET_CODE.replace(text, "")
    Regex("""\s*\(([^()]+)\)\s*$""").find(text)?.let { m ->
        val inner = m.groupValues[1].trim()
        val rest = text.substring(0, m.range.first)
        if (rest.contains(inner, ignoreCase = true) || inner.split(' ').all { w -> rest.contains(w, ignoreCase = true) }) text = rest
    }
    return text
}

/** Shouting into title case (acronyms, call signs and short words kept); known brands in their own spelling. */
private fun tidyCase(text: String): String {
    val shouting = text.none(Char::isLowerCase) && text.count(Char::isLetter) > 4
    val cased = if (shouting) LiveFinder.displayCase(text) else text.split(' ').joinToString(" ") { w ->
        // A shouted word inside a mixed name ("Bein SPORTS"): six letters or more (MSNBC, ESPNU stay), all capitals.
        if (w.length >= 6 && w.all { it in 'A'..'Z' } && w !in BRANDS) w.lowercase().replaceFirstChar(Char::uppercase) else w
    }
    return cased.split(' ').joinToString(" ") { w -> BRANDS[w.uppercase()] ?: w }
}
