package app.opentv.livetv.find

import app.opentv.data.parser.ChannelNameNormalizer

/** A followed event with every way we found to watch it, strongest evidence first. */
@kotlinx.serialization.Serializable
data class EventCard(val event: SportEvent, val options: List<WatchOption>)

/** Joins schedule events to provider streams. Pure: works on a [LiveFindIndex] already built. */
class SportsFinder(private val index: LiveFindIndex) {
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
            live -> WatchState.LIVE
            else -> WatchState.UPCOMING
        }
        val matched = slots.filter { (slot, folded) -> EventMatcher.matchesFolded(slot.name, folded, event, now) }
            .map { it.first to EventMatcher.dayDistance(it.first.name, event) }
        // A daily-repeating listing ("Sri Lanka vs Pakistan" every day) must not stand in for the right day's slot.
        val bestDay = matched.minOfOrNull { it.second } ?: 0
        val named = matched.filter { it.second == bestDay }.map { it.first }
            .mapNotNull { slot -> EventMatcher.slotState(slot.name, slot.categoryName).takeUnless { it == SlotState.IDLE || it == SlotState.ENDED }
                ?.let(::stateOf)?.let { slot to it } }
            // The same event is often copied into several regions' slot groups: one option per slot group and state.
            .groupBy { (slot, state) -> slotGroup(slot) to state }
            .map { (group, members) ->
                val ordered = members.map { it.first }.sortedByDescending { it.region in regions }
                WatchOption(group.first, ordered.first().region, WatchEvidence.NAMED_BY_PROVIDER, ordered.map { it.id }, group.second)
            }
        // ESPN broadcasters are US networks carrying the game live; after it ends they show something else.
        val broadcast = if (event.finished) emptyList() else event.broadcasters.flatMap { name ->
            EventMatcher.broadcasterKeys(name).flatMap { channelsByKey[it].orEmpty() }.filter { it.region == "US" }
                .map { WatchOption(it.name, it.region, WatchEvidence.BROADCASTER, it.streamIds, if (live) WatchState.LIVE else WatchState.UPCOMING) }
        }.distinctBy { it.title }
        // A series' own channel (Sky Sports F1, F1 TV) carries every session of a race weekend, even before the
        // provider names an event slot for it. Not for cricket: Willow and Fox Cricket carry only some series.
        val dedicated = if (event.finished || Sports.byKey(event.sport)?.shape != SportShape.MEETING) emptyList()
            else seriesChannels.getOrPut(event.sport to regions) { sportChannels(event.sport, regions).filter { it.region in regions }.take(3) }
                .map { WatchOption(it.name, it.region, WatchEvidence.SPORT_CHANNEL, it.streamIds, if (live) WatchState.LIVE else WatchState.UPCOMING) }
        return (named + broadcast + dedicated).distinctBy { it.title }.sortedWith(compareBy<WatchOption> { it.state == WatchState.REPLAY }
            .thenByDescending { it.region in regions }.thenBy { it.evidence.ordinal }).take(8)
    }

    /** Provider slot categories carrying any of these cards, for a quick name refresh. */
    fun slotCategories(cards: List<EventCard>): Set<Pair<Long, String>> {
        val ids = cards.flatMap { card -> card.options.filter { it.evidence == WatchEvidence.NAMED_BY_PROVIDER }.flatMap { it.streamIds } }.toSet()
        return index.slots.filter { it.id in ids }.mapNotNull { slot -> slot.categoryId?.let { slot.sourceId to it } }.toSet()
    }

    /** Dedicated channels for a sport (F1 TV, Willow…), any region, the profile's regions first. */
    fun sportChannels(sport: String, regions: Set<String>): List<FindChannel> {
        val pattern = EventMatcher.SPORT_CHANNELS[sport] ?: return emptyList()
        // Name match, or a plain sports category ("WT| WORLD CRICKET"), never PPV/team/replay feeds.
        val candidates = index.channels.filter { channel ->
            val theme = channel.category.substringAfter('|')
            (pattern.containsMatchIn(channel.name) || (pattern.containsMatchIn(theme) && !FEED_CATEGORY.containsMatchIn(theme))) &&
                !REPLAYISH.containsMatchIn(channel.name)
        }
        // Other regions only when identified, or when the profile's regions have little.
        val home = candidates.filter { it.region in regions || it.identity != null }
        val pool = if (home.size >= 4) home else candidates
        // Real channels first: identified, in the profile's regions, then numbered feeds ("League Pass 10") last.
        return pool.sortedWith(compareBy<FindChannel> { NUMBERED.containsMatchIn(it.name) }.thenByDescending { it.identity != null }
            .thenByDescending { it.region in regions }.thenBy { it.name }).take(10)
    }

    companion object {
        /** "US| ESPN+ PPV" → "ESPN+ PPV": the provider's name for the slot family. */
        private val FEED_CATEGORY = Regex("""PPV|TEAM|REPLAY|EVENT|PASS""", RegexOption.IGNORE_CASE)
        private val REPLAYISH = Regex("""\b(REPLAY|HIGHLIGHTS|INFO)\b""", RegexOption.IGNORE_CASE)
        private val NUMBERED = Regex("""\s\d{1,3}$""")
        private val SLOT_SUFFIX = Regex("""\s+(PPV|VIP|RAW|HD|60FPS|EVENTS?)\b.*$""", RegexOption.IGNORE_CASE)

        fun slotGroup(slot: SlotStream): String {
            val raw = ChannelNameNormalizer.foldSuperscripts(slot.categoryName.orEmpty()).substringAfter('|').trim()
                .replace(SLOT_SUFFIX, "").trim()
            if (raw.isBlank()) return "Event stream"
            // "PEACOCK" → "Peacock"; keep short codes (ESPN+, NBA, UFC) as written.
            return raw.split(' ').joinToString(" ") { w -> if (w.length <= 4 || !w.all(Char::isLetter)) w else w.lowercase().replaceFirstChar(Char::uppercase) }
        }
    }
}
