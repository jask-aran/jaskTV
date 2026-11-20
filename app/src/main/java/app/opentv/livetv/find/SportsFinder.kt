package app.opentv.livetv.find

import app.opentv.data.parser.ChannelNameNormalizer

/** A followed event with every way we found to watch it, strongest evidence first. */
@kotlinx.serialization.Serializable
data class EventCard(val event: SportEvent, val options: List<WatchOption>)

/** Joins schedule events to provider streams. Pure: works on a [LiveFindIndex] already built. */
class SportsFinder(private val index: LiveFindIndex, private val airings: List<GuideAiring> = emptyList()) {
    private val slots = index.slots.filterNot { it.name.contains("NO EVENT", true) }.map { it to EventMatcher.fold(it.name) }
    private val channelsByKey = index.channels.groupBy { ChannelNameNormalizer.groupKeyOf(LiveFinder.cleanName(it.name)) }

    private val seriesChannels = HashMap<Pair<String, Set<String>>, List<FindChannel>>()

    fun cards(events: List<SportEvent>, regions: Set<String>, now: Long = System.currentTimeMillis()): List<EventCard> =
        events.map { EventMatcher.settle(it, now) }.map { event -> EventCard(event, options(event, regions, now)) }

    private fun options(event: SportEvent, regions: Set<String>, now: Long): List<WatchOption> {
        val live = EventMatcher.isLive(event, now)
        // Replay only when the slot says so. A plain slot still naming a finished game is a stale name, not a replay.
        fun stateOf(slot: SlotState) = when {
            slot == SlotState.REPLAY -> WatchState.REPLAY
            event.finished -> null
            // "NEXT | …": the provider advertising a later broadcast. While this event is on, that slot has no
            // picture yet (it buffered forever when offered as "Live").
            slot == SlotState.UPCOMING && live -> null
            live -> WatchState.LIVE
            else -> WatchState.UPCOMING
        }
        // A race weekend's slot naming another session ("PRACTICE 3" for Practice 1) is that session's, not this one's.
        val session = sessionOf(event.title)
        val matched = slots.filter { (slot, folded) -> EventMatcher.matchesFolded(slot.name, folded, event, now) && !isSideFeed(slot.name) &&
                (session == null || sessionOf(slot.name).let { it == null || it == session }) }
            .map { it.first to EventMatcher.dayDistance(it.first.name, event) }
        // A daily-repeating listing ("Sri Lanka vs Pakistan" every day) must not stand in for the right day's slot.
        val bestDay = matched.minOfOrNull { it.second } ?: 0
        val named = matched.filter { it.second == bestDay }.map { it.first }
            .mapNotNull { slot -> EventMatcher.slotState(slot.name, slot.categoryName).takeUnless { it == SlotState.IDLE || it == SlotState.ENDED }
                ?.let(::stateOf)?.let { slot to it } }
            // The same event is often copied into several regions' slot groups: one option per slot group and state.
            .groupBy { (slot, state) -> slotGroup(slot, event.sport) to state }
            .map { (group, members) ->
                val ordered = members.map { it.first }.sortedByDescending { it.region in regions }
                WatchOption(group.first, ordered.first().region, WatchEvidence.NAMED_BY_PROVIDER, ordered.map { it.id }, group.second)
            }
        // The guide: a programme naming both sides during the game. For a race weekend (one session at a time),
        // any live programme of that sport during the session: Fox Sports 506 listing "Formula 1".
        val window = (event.startUtc - 30 * 60_000)..(event.endUtc ?: (event.startUtc + 4 * 3_600_000L))
        val meeting = Sports.byKey(event.sport)?.shape == SportShape.MEETING
        val guided = if (event.finished) emptyList() else airings.filter { a ->
            !a.replay && a.startUtc < window.last && a.endUtc > window.first &&
                (EventMatcher.guideNames(a.title, event) || (meeting && a.sport == event.sport))
        }.map { WatchOption(it.channel.name, it.channel.region, WatchEvidence.GUIDE, it.channel.streamIds, if (live) WatchState.LIVE else WatchState.UPCOMING) }
        // ESPN broadcasters are US networks carrying the game live; after it ends they show something else.
        val broadcast = if (event.finished) emptyList() else event.broadcasters.flatMap { name ->
            EventMatcher.broadcasterKeys(name).flatMap { channelsByKey[it].orEmpty() }.filter { it.region == "US" }
                .map { WatchOption(it.name, it.region, WatchEvidence.BROADCASTER, it.streamIds, if (live) WatchState.LIVE else WatchState.UPCOMING) }
        }.distinctBy { it.title }
        // A series' own channel (Sky Sports F1, F1 TV) carries every session of a race weekend, even before the
        // provider names an event slot for it. Not for cricket: Willow and Fox Cricket carry only some series.
        // Any region (Sky Sports F1 is in the provider's UK groups), the profile's regions first.
        val dedicated = if (event.finished || !meeting) emptyList()
            else seriesChannels.getOrPut(event.sport to regions) { sportChannels(event.sport, regions).sortedByDescending { it.region in regions }.take(3) }
                .map { WatchOption(it.name, it.region, WatchEvidence.SPORT_CHANNEL, it.streamIds, if (live) WatchState.LIVE else WatchState.UPCOMING) }
        // A named service ("FloSports") before the generic "Event stream" (a slot filed under another sport's family).
        // Race weekends: a channel named for the series (Sky Sports F1, F1 Channel) before a general one the guide lists,
        // whatever its region. Fox Sports 506's "Formula 1" listing showed a Red Bull feature during Practice 2.
        val ownWords = if (meeting) EventMatcher.SPORT_CHANNELS[event.sport] else null
        return (named + guided + broadcast + dedicated).distinctBy { it.title }.sortedWith(compareBy<WatchOption> { it.state == WatchState.REPLAY }
            .thenByDescending { o -> ownWords?.containsMatchIn(o.title) == true }
            .thenByDescending { it.region in regions }.thenBy { it.title == GENERIC_SLOT }.thenBy { it.evidence.ordinal }).take(8)
    }

    /** Provider slot categories carrying any of these cards, for a quick name refresh. */
    fun slotCategories(cards: List<EventCard>): Set<Pair<Long, String>> {
        val ids = cards.flatMap { card -> card.options.filter { it.evidence == WatchEvidence.NAMED_BY_PROVIDER }.flatMap { it.streamIds } }.toSet()
        return index.slots.filter { it.id in ids }.mapNotNull { slot -> slot.categoryId?.let { slot.sourceId to it } }.toSet()
    }

    /**
     * Channels showing [sport] now by the guide, games before highlights, the profile's regions first; then
     * provider event slots naming the sport that no schedule card took (a T10 league the schedules don't list).
     */
    fun onNow(sport: String, regions: Set<String>, cards: List<EventCard>, now: Long = System.currentTimeMillis()): List<FindChannel> {
        // The programme's own title must name the sport: a category alone ("Sport") or a magazine filed under it isn't it.
        val words = SportsGuide.WORDS[sport]
        val guide = airings.filter { it.sport == sport && it.startUtc <= now && it.endUtc > now && words?.containsMatchIn(it.title) != false }
            .sortedWith(compareBy<GuideAiring> { it.replay }.thenByDescending { it.channel.region in regions })
            .map { it.channel }
        val pattern = EventMatcher.SPORT_CHANNELS[sport]
        val competitions = SLOT_COMPETITIONS[sport]
        val used = cards.flatMap { c -> c.options.flatMap { it.streamIds } }.toSet()
        val loose = if (pattern == null) emptyList() else slots.map { it.first }.filter { slot ->
            // The event part must name the sport or an event, not just the slot's service tag: "F1: ALEXANDER ALBON |
            // WILLIAMS" is a driver's onboard feed, which reads as a person, not a channel showing Formula 1.
            val event = SLOT_TAG.replace(slot.name, "")
            val named = pattern.containsMatchIn(event) || competitions?.containsMatchIn(event) == true ||
                (pattern.containsMatchIn(slot.name) && EVENTISH.containsMatchIn(event))
            slot.id !in used && named && !isSideFeed(slot.name) &&
                EventMatcher.slotState(slot.name, slot.categoryName) !in setOf(SlotState.IDLE, SlotState.ENDED, SlotState.REPLAY)
        }.sortedByDescending { it.region in regions }.map { slot ->
            FindChannel("slot:${slot.id}", slot.name, slot.logoUrl, slot.region, null, LiveGenre.SPORTS, listOf(slot.id), null, slot.categoryName.orEmpty())
        }
        return (guide + loose).distinctBy { it.key }
    }

    /** Dedicated channels for a sport (F1 TV, Willow…), any region, the profile's regions first. */
    fun sportChannels(sport: String, regions: Set<String>): List<FindChannel> {
        val pattern = EventMatcher.SPORT_CHANNELS[sport] ?: return emptyList()
        // Name match, or a plain sports category ("WT| WORLD CRICKET"), never PPV/team/replay feeds.
        val candidates = index.channels.filter { channel ->
            val theme = channel.category.substringAfter('|')
            (pattern.containsMatchIn(channel.name) || (pattern.containsMatchIn(theme) && !FEED_CATEGORY.containsMatchIn(theme))) &&
                !REPLAYISH.containsMatchIn(channel.name) && !isSideFeed(channel.name)
        }
        // Other regions only when identified, or when the profile's regions have little.
        val home = candidates.filter { it.region in regions || it.identity != null }
        val pool = if (home.size >= 4) home else candidates
        // Real channels first: identified, in the profile's regions, then numbered feeds ("League Pass 10") last.
        return pool.sortedWith(compareBy<FindChannel> { NUMBERED.containsMatchIn(it.name) }.thenByDescending { it.identity != null }
            // Every match, best first: the Sports tab's row shows a screenful and "See all" opens the rest.
            .thenByDescending { it.region in regions }.thenBy { it.name }).take(80)
    }

    companion object {
        /** What a watch option is called when its slot family names another sport. */
        const val GENERIC_SLOT = "Event stream"
        /** A slot name's leading service tag ("F1:", "flolive:", "UFC 00 :"). */
        private val SLOT_TAG = Regex("""^\s*[A-Za-z0-9&+]{1,10}(?:\s*\d{1,3})?\s*:\s*""")
        /** A game or a session: "Lakers vs Suns", "Las Vegas GP", "Qualifying". */
        private val EVENTISH = Regex("""\b(vs?\.?|at)\s+\S|\s@\s|\bGP\b|GRAND PRIX|\bRACE\b|QUALI|PRACTICE|SPRINT|\bFP\d|\bMATCH\b|\bFINAL""", RegexOption.IGNORE_CASE)

        /**
         * Event slots often name only the competition ("Abu Dhabi T10 League 2026 - 22nd Match", "IPL", "Big Bash"),
         * filed under another sport's family ("US| SOCCER PPV"). Slot names only: as channel names some of these words
         * mean other things.
         */
        private val SLOT_COMPETITIONS: Map<String, Regex> = mapOf(
            "cricket" to Regex("""\bT10\b|\bT20|\bIPL\b|\bW?BBL\b|BIG BASH|\bSA20\b|\bILT20\b|\bODI\b|SHEFFIELD SHIELD|RANJI TROPHY""", RegexOption.IGNORE_CASE),
        )

        /** "US| ESPN+ PPV" → "ESPN+ PPV": the provider's name for the slot family. */
        private val FEED_CATEGORY = Regex("""PPV|TEAM|REPLAY|EVENT|PASS""", RegexOption.IGNORE_CASE)
        private val REPLAYISH = Regex("""\b(REPLAY|HIGHLIGHTS|INFO)\b""", RegexOption.IGNORE_CASE)

        /** A series' side feeds, not channels: onboard and driver cameras, pit lane, timing and tracker data. */
        private val SESSION = Regex("""\b(?:(?:FREE\s+)?PRACTICE|FP)\s*(\d)|\b(SPRINT\s+QUALI\w*|SPRINT|QUALI\w*|RACE|GRAND\s+PRIX\s+RACE)\b""", RegexOption.IGNORE_CASE)
        /** A race weekend's session named in [text]: "P1", "P3", "QUALI", "SPRINT", "RACE"; null when none is named. */
        fun sessionOf(text: String): String? = SESSION.find(text)?.let { m ->
            m.groupValues[1].takeIf { it.isNotEmpty() }?.let { "P$it" } ?: m.groupValues[2].uppercase().let {
                when { it.startsWith("SPRINT Q") -> "SQ"; it.startsWith("SPRINT") -> "SPRINT"; it.startsWith("QUALI") -> "QUALI"; else -> "RACE" }
            }
        }
        private val SIDE_FEED = Regex("""ON[ -]?B+OARD|\bBONUS\b|PIT ?LANE|DRIVER (TRACKER|CAM)|\bTRACKER\b|\bTRACKING\b|LIVE TIMING|\bTIMING\b|DATA CHANNEL|-DATA\b|\bF1 CAR\b|\bCAR CAM|\bON ?CAM\b|HELICOPTER|\bAMBIENT\b|360 ?CAM""", RegexOption.IGNORE_CASE)
        /** "F1: OLIVER BEARMAN | HAAS F1 TEAM | BEA": a driver's own feed (name, team, three-letter code). */
        // The middle part must be a team ("HAAS F1 TEAM", "RED BULL RACING"): a channel that merely ends "| ABC" stays.
        private val DRIVER_FEED = Regex("""\|\s*[^|]*\b(TEAM|RACING|SCUDERIA|MOTORSPORTS?|F1)\b[^|]*\|\s*[A-Z]{3}\s*$""", RegexOption.IGNORE_CASE)

        /** Whether a stream is a side feed (an onboard camera, a driver's channel) rather than a channel to watch the sport on. */
        fun isSideFeed(name: String): Boolean = SIDE_FEED.containsMatchIn(name) || DRIVER_FEED.containsMatchIn(name.trim())
        private val NUMBERED = Regex("""\s\d{1,3}$""")
        private val SLOT_SUFFIX = Regex("""\s+(PPV|VIP|RAW|HD|60FPS|EVENTS?)\b.*$""", RegexOption.IGNORE_CASE)

        /**
         * Sport words a provider files slot families under ("US| SOCCER PPV"), and the sports each fits. Providers put
         * other sports' events in these slots (the Abu Dhabi T10 in "SOCCER PPV"), so for any other sport the family
         * name would mislabel the game.
         */
        private val FAMILY_SPORTS: List<Pair<Regex, Set<String>>> = listOf(
            Regex("""\b(SOCCER|FUTBOL|FÚTBOL|FUSSBALL|CALCIO)\b""", RegexOption.IGNORE_CASE) to setOf("epl", "ucl", "aleague"),
            // "Football 8K": the provider files F1 and NBA slots there too. Either code of football keeps the name.
            Regex("""\bFOOTBALL\b""", RegexOption.IGNORE_CASE) to setOf("epl", "ucl", "aleague", "nfl", "ncaaf"),
            Regex("""\bCRICKET\b""", RegexOption.IGNORE_CASE) to setOf("cricket"),
            Regex("""\b(NBA|BASKETBALL)\b""", RegexOption.IGNORE_CASE) to setOf("nba", "ncaab"),
            Regex("""\bNFL\b""", RegexOption.IGNORE_CASE) to setOf("nfl"),
            Regex("""\b(NHL|HOCKEY)\b""", RegexOption.IGNORE_CASE) to setOf("nhl"),
            Regex("""\b(MLB|BASEBALL)\b""", RegexOption.IGNORE_CASE) to setOf("mlb"),
            Regex("""\b(UFC|MMA)\b""", RegexOption.IGNORE_CASE) to setOf("ufc"),
            Regex("""\bF1\b|FORMULA 1|FORMULA ONE""", RegexOption.IGNORE_CASE) to setOf("f1"),
            Regex("""MOTO ?GP""", RegexOption.IGNORE_CASE) to setOf("motogp"),
            Regex("""\bNASCAR\b""", RegexOption.IGNORE_CASE) to setOf("nascar"),
            Regex("""INDY ?CAR""", RegexOption.IGNORE_CASE) to setOf("indycar"),
            Regex("""\bAFL\b""", RegexOption.IGNORE_CASE) to setOf("afl"),
            Regex("""\bNRL\b""", RegexOption.IGNORE_CASE) to setOf("nrl"),
        )

        /** The slot family's name for a watch option. [sport]: the event's; a family named for another sport says only "Event stream". */
        fun slotGroup(slot: SlotStream, sport: String? = null): String {
            val raw = ChannelNameNormalizer.foldSuperscripts(slot.categoryName.orEmpty()).substringAfter('|').trim()
                .replace(SLOT_SUFFIX, "").trim()
            if (raw.isBlank()) return "Event stream"
            if (sport != null && FAMILY_SPORTS.any { (words, sports) -> sport !in sports && words.containsMatchIn(raw) }) return GENERIC_SLOT
            // "PEACOCK" → "Peacock"; keep short codes (ESPN+, NBA, UFC) as written.
            return raw.split(' ').joinToString(" ") { w -> if (w.length <= 4 || !w.all(Char::isLetter)) w else w.lowercase().replaceFirstChar(Char::uppercase) }
        }
    }
}
