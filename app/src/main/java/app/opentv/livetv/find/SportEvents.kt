package app.opentv.livetv.find

import app.opentv.data.parser.ChannelNameNormalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/** One side of a fixture, with every name a provider might write for it (most specific first). */
@kotlinx.serialization.Serializable
data class Competitor(val names: List<String>, val logo: String? = null, val color: Long? = null, val shortName: String? = null)

/** A scheduled sporting event from a schedule source. Times are the schedule's, never a slot's. */
@kotlinx.serialization.Serializable
data class SportEvent(
    val id: String,
    val sport: String,
    val league: String,
    val title: String,
    val competitors: List<Competitor>,
    val startUtc: Long,
    val endUtc: Long?,
    val live: Boolean,
    val finished: Boolean,
    /** Broadcaster names from the schedule (ESPN: US national and regional). */
    val broadcasters: List<String>,
    /** For events without two sides (F1): word groups that must all appear, any word per group. */
    val wordGroups: List<List<String>> = emptyList(),
    /** League artwork for events without two sides (F1) and as a fallback. */
    val leagueLogo: String? = null,
    /** Events that belong together (a race weekend's sessions, a Test's days): shown as one card. */
    val meeting: String? = null,
    /** Which part of the meeting this is: "Qualifying", "Race", "2nd Test · Day 3". */
    val session: String? = null,
    /** A match over several days (a Test): its first day's start and how many days it runs. */
    val firstDay: Long? = null,
    val days: Int = 1,
    /** The series or tournament this belongs to, for leagues that have one (an NBA playoff series, the NBA Cup). */
    val group: String? = null,
)

/** How we know a stream shows an event. Strongest first. */
enum class WatchEvidence(val label: String) {
    /** The provider's own event stream names this game. */
    NAMED_BY_PROVIDER("Event stream"),
    /** The provider's guide lists this game (or, for a race weekend, the sport) on this channel at this time. */
    GUIDE("In the guide"),
    /** The schedule names this network; we have the channel. */
    BROADCASTER("On TV"),
    SPORT_CHANNEL("Sport channel"),
}

/** What a watch option is showing, judged from the schedule's time (never the slot's) and replay cues. */
enum class WatchState { LIVE, UPCOMING, REPLAY }

@kotlinx.serialization.Serializable
data class WatchOption(val title: String, val region: String, val evidence: WatchEvidence, val streamIds: List<Long>,
    val state: WatchState = WatchState.UPCOMING)

/** What an event slot's own name says about it. Only a hint: event times come from the schedule. */
enum class SlotState { IDLE, UPCOMING, LIVE, ENDED, REPLAY, NAMED }

/** Slot ↔ event matching. Pure: no Android, no network. */
object EventMatcher {
    private val NON_ALNUM = Regex("""[^a-z0-9]+""")

    fun fold(text: String): String =
        " " + NON_ALNUM.replace(ChannelNameNormalizer.foldSuperscripts(text).lowercase().replace("`", "'").replace("'", ""), " ").trim() + " "

    private fun mentions(folded: String, name: String): Boolean {
        val n = fold(name)
        return n.length >= 5 && folded.contains(n) // " xyz " = 3 letters + 2 spaces
    }

    private val ENDED = Regex("""^\s*(ENDED|END)\b""", RegexOption.IGNORE_CASE)
    private val ISO = Regex("""\b(20\d\d)-(\d\d)-(\d\d)\b""")
    private val DMY = Regex("""\b(\d\d)-(\d\d)-(20\d\d)\b""")
    private val MONTH_DAY = Regex("""\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\.? (\d{1,2})\b(?![:.]\d)""", RegexOption.IGNORE_CASE)
    private val DAY_MONTH = Regex("""\b(\d{1,2}) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\b""", RegexOption.IGNORE_CASE)
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** The calendar date a slot name carries, if any. Its zone is unknown (often US Eastern), so callers allow ±1 day. */
    fun slotDate(name: String, year: Int): LocalDate? = runCatching {
        ISO.find(name)?.let { return@runCatching LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        DMY.find(name)?.let { return@runCatching LocalDate.of(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }
        MONTH_DAY.find(name)?.let { return@runCatching LocalDate.of(year, MONTHS.indexOf(it.groupValues[1].take(3).lowercase()) + 1, it.groupValues[2].toInt()) }
        DAY_MONTH.find(name)?.let { return@runCatching LocalDate.of(year, MONTHS.indexOf(it.groupValues[2].take(3).lowercase()) + 1, it.groupValues[1].toInt()) }
        null
    }.getOrNull()

    fun matches(slotName: String, event: SportEvent, now: Long): Boolean = matchesFolded(slotName, fold(slotName), event, now)

    /** [text] is [fold] of [slotName], computed once per slot by callers that test many events. */
    /** Days between the slot's own date and the event's UTC day; 0 when the slot carries no date. Lower is a better match. */
    fun dayDistance(slotName: String, event: SportEvent): Long {
        val eventDay = Instant.ofEpochMilli(event.startUtc).atZone(ZoneOffset.UTC).toLocalDate()
        return slotDate(slotName, eventDay.year)?.let { abs(it.toEpochDay() - eventDay.toEpochDay()) } ?: 0
    }

    /** Guide names for national teams: "BLACKCAPS v India" is New Zealand v India. */
    private val NICKNAMES = listOf("black caps" to "new zealand", "blackcaps" to "new zealand", "white ferns" to "new zealand",
        "proteas" to "south africa", "windies" to "west indies", "southern stars" to "australia")

    /** Whether a guide programme's title names both sides of [event] (the programme's time is checked by the caller). */
    fun guideNames(title: String, event: SportEvent): Boolean {
        if (event.competitors.size < 2) return false
        var text = title.lowercase()
        NICKNAMES.forEach { (nick, name) -> text = text.replace(nick, name) }
        val folded = fold(text)
        return event.competitors.all { side -> side.names.any { mentions(folded, it) } }
    }

    fun matchesFolded(slotName: String, text: String, event: SportEvent, now: Long): Boolean {
        if (ENDED.containsMatchIn(slotName)) return false
        val named = if (event.competitors.size >= 2) event.competitors.all { side -> side.names.any { mentions(text, it) } }
            else event.wordGroups.isNotEmpty() && event.wordGroups.all { group -> group.any { mentions(text, it) } }
        if (!named) return false
        val eventDay = Instant.ofEpochMilli(event.startUtc).atZone(ZoneOffset.UTC).toLocalDate()
        val date = slotDate(slotName, eventDay.year)
        return if (date != null) abs(date.toEpochDay() - eventDay.toEpochDay()) <= 1
            else event.startUtc in (now - 6 * HOUR)..(now + 30 * HOUR)
    }

    /** Names a team might go by in a slot: "Adelaide Strikers Women" is also written "Adelaide Strikers". */
    fun variants(vararg names: String?): List<String> = names.filterNotNull().map(String::trim).filter { it.length >= 3 }
        .flatMap { listOf(it, it.removeSuffix(" Women").removeSuffix(" Men").removeSuffix(" W")) }
        .filterNot { it.contains('-') && it.length <= 5 } // "AS-W" style codes collide
        .distinct()

    /** A schedule broadcaster to channel keys it may be carried as. Streaming services ("ESPN+") never match a channel. */
    fun broadcasterKeys(name: String): Set<String> {
        if (name.contains('+') || name.contains("Streaming", true)) return emptySet()
        val key = ChannelNameNormalizer.groupKeyOf(name)
        return if (key.length < 2) emptySet() else setOf(key, key + "usa", key + "hd")
    }

    /** Dedicated channels per sport, by name. Shown under the sport, any region. */
    val SPORT_CHANNELS: Map<String, Regex> = mapOf(
        "f1" to Regex("""\bF1\b|FORMULA 1|FORMULA ONE""", RegexOption.IGNORE_CASE),
        "cricket" to Regex("""CRICKET|WILLOW""", RegexOption.IGNORE_CASE),
        "nba" to Regex("""\bNBA\b""", RegexOption.IGNORE_CASE),
        "nfl" to Regex("""NFL NETWORK|NFL REDZONE|NFL RED ZONE""", RegexOption.IGNORE_CASE),
        "nhl" to Regex("""NHL NETWORK""", RegexOption.IGNORE_CASE),
        "mlb" to Regex("""MLB NETWORK""", RegexOption.IGNORE_CASE),
        "afl" to Regex("""FOX FOOTY|\bAFL\b""", RegexOption.IGNORE_CASE),
        "nrl" to Regex("""FOX LEAGUE|\bNRL\b""", RegexOption.IGNORE_CASE),
        "motogp" to Regex("""MOTO ?GP""", RegexOption.IGNORE_CASE),
        "indycar" to Regex("""INDYCAR|INDY CAR""", RegexOption.IGNORE_CASE),
        "nascar" to Regex("""NASCAR""", RegexOption.IGNORE_CASE),
        "ufc" to Regex("""\bUFC\b""", RegexOption.IGNORE_CASE),
    )

    private val PLACEHOLDER = Regex("""NO EVENT|\b209\d-\d\d-\d\d""", RegexOption.IGNORE_CASE)
    /** Just a service tag and a slot number: ":MAX SE  15", "UFC 10:", "Mola Event 08 :". */
    private val BARE_SLOT = Regex("""^[\s:(]*[A-Za-z0-9+&.'():| ]{0,34}?\s*\d{1,4}\s*(\([A-Z]\))?\s*:?\s*$""")
    private val REPLAY_WORDS = Regex("""\b(replay|replays|rebroadcast|highlights|rewind|condensed|re-air|encore|best of|in 30|full match|recap|review)\b|\(R\)""", RegexOption.IGNORE_CASE)
    private val NEXT = Regex("""^\s*(NEXT|UPCOMING|SOON)\b""", RegexOption.IGNORE_CASE)
    private val LIVE_MARK = Regex("""^\s*LIVE\b""", RegexOption.IGNORE_CASE)

    fun slotState(name: String, category: String?): SlotState = when {
        ENDED.containsMatchIn(name) -> SlotState.ENDED
        !MATCHUP_WORDS.containsMatchIn(name) && (PLACEHOLDER.containsMatchIn(name) || BARE_SLOT.matches(name.trim())) -> SlotState.IDLE
        REPLAY_WORDS.containsMatchIn(name) || category.orEmpty().contains("REPLAY", true) -> SlotState.REPLAY
        NEXT.containsMatchIn(name) -> SlotState.UPCOMING
        LIVE_MARK.containsMatchIn(name) -> SlotState.LIVE
        else -> SlotState.NAMED
    }
    private val MATCHUP_WORDS = Regex("""\b(vs?\.?|at)\s+\S|\s@\s[A-Za-z]""", RegexOption.IGNORE_CASE)

    /** On air by the schedule: from 10 minutes before the start to the scheduled end (or a sport-length guess). */
    fun isLive(event: SportEvent, now: Long): Boolean =
        !settle(event, now).finished && (event.live || now in (event.startUtc - 10 * 60_000)..minOf(event.endUtc ?: Long.MAX_VALUE, event.startUtc + PLAY_WINDOW))

    /** Without a live flag from the schedule, an event is over [PLAY_WINDOW] after its start. One rule for every caller. */
    fun settle(event: SportEvent, now: Long): SportEvent = when {
        event.finished -> event
        // Past its known end and not flagged live (a match running over keeps ESPN's live flag): over. Catches cards
        // restored from the last session that still call a finished F1 session "Next".
        event.endUtc != null && now >= event.endUtc && !event.live -> event.copy(finished = true)
        !event.live && event.startUtc + PLAY_WINDOW < now -> event.copy(finished = true)
        else -> event
    }

    private const val PLAY_WINDOW = 5 * 3_600_000L

    const val HOUR = 3_600_000L
}
