package app.opentv.livetv.find

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One shape for every sport's schedule: series hold events, events hold the parts you watch.
 *  - a part is one thing on air at one time: a game, a session, a day of a Test.
 *  - an event is a game (one part), a race weekend (its sessions) or a multi-day match (its days).
 *  - a series is what events belong to: a cricket tour or league, an NBA playoff series or the NBA Cup.
 * An event or series that runs over several days is "interday" (a bar on the calendar); parts are "intraday"
 * (rows on a day's timeline). Pure: no Android.
 */
data class CalPart(val card: EventCard, val label: String, val start: Long, val live: Boolean, val done: Boolean) {
    val watchable: Boolean get() = card.options.isNotEmpty()
}

data class CalEvent(val key: String, val title: String, val series: String?, val parts: List<CalPart>) {
    val start: Long get() = parts.first().start
    val end: Long get() = parts.last().start
    val live: Boolean get() = parts.any { it.live }
    val done: Boolean get() = parts.all { it.done }
    val watchable: Boolean get() = parts.any { it.watchable }
    val sample: SportEvent get() = parts.first().card.event
    fun multiDay(zone: ZoneId): Boolean = SportCalendar.day(start, zone) != SportCalendar.day(end, zone)
}

data class CalSeries(val name: String, val events: List<CalEvent>) {
    val start: Long get() = events.minOf { it.start }
    val end: Long get() = events.maxOf { it.end }
    val live: Boolean get() = events.any { it.live }
    val watchable: Boolean get() = events.any { it.watchable }
}

data class SportCalendar(val series: List<CalSeries>, val events: List<CalEvent>) {
    val parts: List<CalPart> get() = events.flatMap { it.parts }

    /** What runs across days, for the calendar's bars: series of 2+ events, and events over several days. */
    fun spans(zone: ZoneId): List<Any> {
        val inSeries = series.flatMap { it.events }.toSet()
        return (series + events.filter { it !in inSeries && it.multiDay(zone) })
            .sortedWith(compareByDescending<Any> { (it as? CalSeries)?.live ?: (it as CalEvent).live }
                .thenBy { (it as? CalSeries)?.start ?: (it as CalEvent).start })
    }

    /** A day's parts, in start order. */
    fun on(date: LocalDate, zone: ZoneId): List<CalPart> = parts.filter { day(it.start, zone) == date }.sortedBy { it.start }

    companion object {
        private const val DAY = 86_400_000L

        fun build(cards: List<EventCard>, now: Long, shape: SportShape): SportCalendar {
            val (meetings, single) = cards.partition { shape == SportShape.MEETING && it.event.meeting != null }
            val events = meetings.groupBy { it.event.meeting }.map { (key, sessions) ->
                val sorted = sessions.sortedBy { it.event.startUtc }
                CalEvent(key!!, sorted.first().event.title.substringBefore(" · "), null, sorted.map { part(it, it.event.session ?: "Race", now) })
            } + single.map { card ->
                val e = card.event
                val first = e.firstDay
                val parts = if (first != null && e.days > 1) (0 until e.days).map { d ->
                    // Each day of a Test is its own part; the feed only describes the current (or next) day.
                    val start = first + d * DAY
                    val current = start == e.startUtc
                    val past = start < e.startUtc || (current && e.finished)
                    val label = (e.session?.substringBefore(" · Day")?.let { "$it · " } ?: "") + "Day ${d + 1}"
                    val dayEvent = e.copy(startUtc = start, live = current && e.live, finished = past, session = label)
                    CalPart(card.copy(event = dayEvent), label, start, current && EventMatcher.isLive(dayEvent, now), past)
                } else listOf(part(card, e.session ?: "", now))
                CalEvent(e.meeting ?: e.id, e.title, groupOf(e, shape), parts)
            }
            val series = events.filter { it.series != null }.groupBy { it.series!! }.filterValues { it.size >= 2 }
                .map { (name, list) -> CalSeries(name, list.sortedBy { it.start }) }
            return SportCalendar(series, events.sortedBy { it.start })
        }

        private fun part(card: EventCard, label: String, now: Long) =
            CalPart(card, label, card.event.startUtc, EventMatcher.isLive(card.event, now), card.event.finished && !EventMatcher.isLive(card.event, now))

        /** "Sri Lanka tour of Pakistan 2026/27" → "Sri Lanka tour of Pakistan". */
        fun seriesName(league: String) = league.replace(Regex("""\s+\d{4}(/\d{2,4})?$"""), "").trim()

        private fun groupOf(e: SportEvent, shape: SportShape): String? = when (shape) {
            SportShape.SERIES -> seriesName(e.league)
            SportShape.LEAGUE -> e.group
            SportShape.MEETING -> null
        }

        fun day(utc: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(utc).atZone(zone).toLocalDate()
    }
}
