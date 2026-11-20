package app.opentv.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.opentv.livetv.find.CalEvent
import app.opentv.livetv.find.CalPart
import app.opentv.livetv.find.CalSeries
import app.opentv.livetv.find.EventCard
import app.opentv.livetv.find.Sport
import app.opentv.livetv.find.SportCalendar
import app.opentv.livetv.find.WatchOption
import app.opentv.ui.jaskCard
import app.opentv.ui.jaskCardArt
import app.opentv.ui.jaskFocusEntry
import app.opentv.ui.jaskFocusLift
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import app.opentv.ui.settings.JaskDim
import coil.compose.AsyncImage
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LiveRed = Color(0xFFE5484D)
private const val WINDOW_DAYS = 8

/**
 * A sport's page: its week as a calendar, then a day as a timeline. One layout for every sport, from
 * [SportCalendar]'s series → events → parts:
 *  - the day strip picks a day (yesterday to six days on), each with a count of what starts then.
 *  - "Across the week": what runs over several days (a cricket tour, a Test, a race weekend, an NBA series) as a
 *    bar over the days it covers. Opening one lists its events and parts right there.
 *  - the chosen day: when each part starts and which channels have it. A channel plays at once; the part itself
 *    opens every way to watch.
 * Games on none of your channels are left out until asked for.
 */
@Composable
internal fun SportPage(sport: Sport, cards: List<EventCard>, now: Long, onBack: () -> Unit, backLabel: String = "Live TV", onOpen: (EventCard) -> Unit,
    onPlay: (WatchOption) -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val padding = app.opentv.ui.LocalJaskContentPadding.current
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    var everything by rememberSaveable { mutableStateOf(false) }
    val all = remember(cards, now) { SportCalendar.build(cards, now, sport.shape) }
    val calendar = remember(all, everything) {
        if (everything) all else SportCalendar(all.series.map { s -> s.copy(events = s.events.filter { it.watchable }) }.filter { it.events.size >= 2 },
            all.events.filter { it.watchable })
    }
    val hidden = all.events.size - calendar.events.size
    val days = (-1 until WINDOW_DAYS - 1).map { today.plusDays(it.toLong()) }
    val counts = days.associateWith { calendar.on(it, zone).size }
    var picked by rememberSaveable { mutableStateOf(days.firstOrNull { !it.isBefore(today) && (counts[it] ?: 0) > 0 }?.toEpochDay() ?: today.toEpochDay()) }
    val day = LocalDate.ofEpochDay(picked)
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }
    // The page opens with the chosen day focused (the tile that opened it is gone, and focus would fall to the top bar);
    // Down from the back pill lands on that day too, not the strip's first.
    val dayFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val tv = app.opentv.ui.LocalLayoutClass.current == app.opentv.ui.LayoutClass.TV
    androidx.compose.runtime.LaunchedEffect(sport.key) {
        if (!tv) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        runCatching { dayFocus.requestFocus() }
    }
    app.opentv.ui.JaskAmbientFrom(sport.logo ?: calendar.events.firstNotNullOfOrNull { e -> e.sample.competitors.firstNotNullOfOrNull { it.logo } })

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gridWidth = maxWidth - 32.dp
        val column = gridWidth / WINDOW_DAYS
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
            item(key = "head") {
                Column(Modifier.fillMaxWidth().padding(top = padding.calculateTopPadding() + 8.dp, start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    Box(androidx.compose.ui.Modifier.focusProperties { down = dayFocus }) { app.opentv.ui.settings.JaskPill("‹ $backLabel", onClick = onBack) }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SportMark(sport, Modifier.size(width = 72.dp, height = 48.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(sport.label, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            val live = calendar.parts.count { it.live }
                            val coming = calendar.events.count { !it.done }
                            // Never "0 coming up" beside games that are on: say where they are instead.
                            val week = when {
                                coming > 0 -> "$coming coming up this week"
                                hidden > 0 && !everything -> "Nothing on your channels this week · $hidden more elsewhere"
                                else -> "Nothing coming up this week"
                            }
                            Text(listOfNotNull(if (live > 0) "$live on now" else null, week)
                                .joinToString(" · "), color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item(key = "days") {
                Row(Modifier.padding(horizontal = 16.dp)) {
                    days.forEach { d -> DayChip(d, today, counts[d] ?: 0, d == day,
                        Modifier.width(column).then(if (d == day) Modifier.focusRequester(dayFocus).jaskFocusEntry() else Modifier)) { picked = d.toEpochDay() } }
                }
            }
            val spans = calendar.spans(zone)
            if (spans.isNotEmpty()) {
                item(key = "spans") { PageTitle("Across the week") }
                spans.forEach { span ->
                    val key = (span as? CalSeries)?.name?.let { "s:$it" } ?: "e:" + (span as CalEvent).key
                    item(key = key) {
                        SpanRow(span, days, column, now, zone, key in open, day) { open = if (key in open) open - key else open + key }
                    }
                    if (key in open) spanParts(key, span, zone, onOpen, onPlay)
                }
            }
            item(key = "day-title") { PageTitle(dayTitle(day, today)) }
            val parts = calendar.on(day, zone)
            if (parts.isEmpty()) item(key = "day-none") { FindNote("Nothing ${if (hidden > 0) "on your channels " else ""}starts this day.") }
            parts.groupBy { it.start / 60_000 }.forEach { (minute, together) ->
                item(key = "d:$minute") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        TimeLabel(minute * 60_000, zone, Modifier.width(64.dp).padding(top = 10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            together.forEach { part -> PartRow(part, calendar.eventOf(part), null, zone, onOpen, onPlay) }
                        }
                    }
                }
            }
            if (hidden > 0 || everything) item(key = "everything") {
                Text(if (everything) "Only what's on your channels" else "$hidden more not on your channels · Show",
                    Modifier.jaskFocusLift(1.06f).padding(start = 8.dp, top = 12.dp).clip(RoundedCornerShape(50)).clickable { everything = !everything }
                        .padding(horizontal = 8.dp, vertical = 8.dp), color = JaskDim, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun SportCalendar.eventOf(part: CalPart): CalEvent? = events.firstOrNull { e -> e.parts.any { it === part } }

private fun dayTitle(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"; today.plusDays(1) -> "Tomorrow"; today.minusDays(1) -> "Yesterday"
    else -> DateTimeFormatter.ofPattern("EEEE d MMMM").format(day)
}

/** An opened bar's contents, in the same list: a series' events (with their parts when there are several), or an event's parts. */
private fun LazyListScope.spanParts(key: String, span: Any, zone: ZoneId, onOpen: (EventCard) -> Unit, onPlay: (WatchOption) -> Unit) {
    val events = (span as? CalSeries)?.events ?: listOf(span as CalEvent)
    events.forEach { event ->
        val inSeries = span is CalSeries
        if (inSeries && event.parts.size > 1) item(key = "$key:${event.key}") {
            Text(heroTitle(event.sample), Modifier.padding(start = 32.dp, top = 8.dp, bottom = 2.dp), color = Color.White,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        event.parts.forEach { part ->
            item(key = "$key:${event.key}:${part.start}") {
                Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp, top = 3.dp, bottom = 3.dp)) {
                    PartRow(part, event, partWhen(part.start, zone), zone, onOpen, onPlay,
                        titled = inSeries && event.parts.size == 1)
                }
            }
        }
    }
}

@Composable
private fun PageTitle(text: String) =
    Text(text, Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp), color = Color(0xFFF4F4F4),
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

private fun partWhen(utc: Long, zone: ZoneId): String = liveWhen(utc, zone)

@Composable
private fun TimeLabel(utc: Long, zone: ZoneId, modifier: Modifier) {
    // One clock everywhere ("9:00 am"); the day is the page's heading.
    Text(liveClock(utc, zone), modifier, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        maxLines = 1, softWrap = false)
}

/** A day in the strip: weekday, date and a dot per thing starting (up to three). */
@Composable
private fun DayChip(day: LocalDate, today: LocalDate, count: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    // Chosen: an outline on a darker fill, like the timeline's next session. A white fill would read as focus.
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.padding(horizontal = 2.dp).jaskFocusLift().clip(shape)
        .background(if (selected) Color.Black.copy(alpha = .35f) else Color.Transparent)
        .border(1.dp, if (selected) Color.White.copy(alpha = .85f) else Color.Transparent, shape)
        .clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        val ink = if (selected) Color.White else if (day.isBefore(today)) JaskDim else Color.White
        Text(if (day == today) "Today" else DateTimeFormatter.ofPattern("EEE").format(day), color = ink,
            style = MaterialTheme.typography.labelSmall, maxLines = 1)
        Text(day.dayOfMonth.toString(), color = ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.height(8.dp).padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(minOf(count, 3)) { Box(Modifier.size(4.dp).clip(CircleShape).background(LiveRed.copy(alpha = .9f))) }
        }
    }
}

/**
 * Something that runs over days, as a bar under the day strip covering its days, with a tick for each day it has
 * something on. Its name and what it holds sit above. Opens in place.
 */
@Composable
private fun SpanRow(span: Any, days: List<LocalDate>, column: Dp, now: Long, zone: ZoneId, opened: Boolean, picked: LocalDate, onClick: () -> Unit) {
    val series = span as? CalSeries
    val event = span as? CalEvent
    val sample = series?.events?.first()?.sample ?: event!!.sample
    val start = SportCalendar.day(series?.start ?: event!!.start, zone)
    val end = SportCalendar.day(series?.end ?: event!!.end, zone)
    val live = series?.live ?: event!!.live
    val partDays = ((series?.events ?: listOf(event!!)).flatMap { it.parts }).map { SportCalendar.day(it.start, zone) }.toSet()
    val title = series?.name ?: if (sample.competitors.size >= 2) heroTitle(sample) else weekendTitle(sample)
    val holds = series?.let { "${it.events.size} matches" } ?: event!!.let { e ->
        (sample.session?.substringBefore(" · Day")?.takeIf { e.parts.first().label.contains("Day") }?.let { "$it · " } ?: "") +
            "${e.parts.size} ${if (e.parts.first().label.contains("Day")) "days" else "sessions"}"
    }
    val (a, b) = sample.colours()
    val first = days.indexOfFirst { !it.isBefore(start) }.let { if (it < 0) days.size else it }
    val last = days.indexOfLast { !it.isAfter(end) }
    Column(Modifier.fillMaxWidth().jaskFocusLift(1.02f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (live) Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(LiveRed))
            Text(title, Modifier.weight(1f, fill = false), color = Color.White, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("  $holds  ${if (opened) "▴" else "▾"}", color = JaskDim, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().padding(top = 5.dp).height(14.dp)) {
            // The week's lanes, faint, so a bar reads against the days above it.
            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = .05f)))
            if (last >= first && first < days.size) {
                Box(Modifier.offset(x = column * first).width(column * (last - first + 1)).height(14.dp).clip(RoundedCornerShape(7.dp))
                    .background(Brush.horizontalGradient(listOf(lerp(a, Color.White, .1f), lerp(b, Color.Black, .2f))))
                    .border(1.dp, if (live) LiveRed else Color.White.copy(alpha = .15f), RoundedCornerShape(7.dp)))
                days.forEachIndexed { i, d ->
                    if (d in partDays) Box(Modifier.offset(x = column * i + column / 2 - 3.dp, y = 4.dp).size(6.dp).clip(CircleShape)
                        .background(if (d == picked) Color.White else Color.White.copy(alpha = .6f)))
                }
            }
        }
    }
}

/**
 * One part on a timeline: the event (logos, title), which part it is, live or replay, and its channels as chips that
 * play straight away. The row opens every way to watch it.
 */
@Composable
private fun PartRow(part: CalPart, event: CalEvent?, time: String?, zone: ZoneId, onOpen: (EventCard) -> Unit, onPlay: (WatchOption) -> Unit,
    titled: Boolean = true) {
    val e = part.card.event
    val (a, b) = e.colours()
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().alpha(if (part.watchable) 1f else .5f).clip(shape)
        .background(Brush.horizontalGradient(listOf(lerp(a, Color.Black, .45f), lerp(b, Color.Black, .7f))))
        .border(if (part.live) 1.5.dp else 1.dp, if (part.live) LiveRed else Color.White.copy(alpha = .08f), shape)) {
        Row(Modifier.fillMaxWidth().jaskFocusLift(1.02f).clickable { onOpen(part.card) }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val logos = e.competitors.mapNotNull { it.logo }.take(2).ifEmpty { listOfNotNull(e.leagueLogo) }
            if (logos.isNotEmpty()) {
                Box(Modifier.size(width = if (logos.size == 2) 40.dp else 28.dp, height = 30.dp)) {
                    logos.forEachIndexed { i, url ->
                        AsyncImage(url, null, Modifier.padding(start = (i * 14).dp, top = (i * 6).dp).size(24.dp), contentScale = ContentScale.Fit)
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                val title = if (titled) (if (e.competitors.size >= 2) heroTitle(e) else weekendTitle(e)) else part.label.ifBlank { heroTitle(e) }
                Text(title, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(time, part.label.takeIf { titled && it.isNotBlank() },
                    event?.series?.takeIf { titled },
                    if (!part.done) null else if (part.card.options.any { it.state == app.opentv.livetv.find.WatchState.REPLAY }) "Replay" else "Ended").joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, color = Color.White.copy(alpha = .6f), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (part.live) Text("LIVE", Modifier.clip(RoundedCornerShape(6.dp)).background(LiveRed).padding(horizontal = 6.dp, vertical = 1.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        val options = part.card.options
        if (options.isEmpty()) Text("Not on your channels", Modifier.padding(start = 10.dp, bottom = 8.dp), color = Color.White.copy(alpha = .6f),
            style = MaterialTheme.typography.labelSmall)
        else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.take(4).forEach { option ->
                Text("▶ " + liveName(option.title), Modifier.jaskFocusLift(1.05f).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .14f))
                    .clickable { onPlay(option) }.padding(horizontal = 10.dp, vertical = 5.dp),
                    color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
            if (options.size > 4) Text("+${options.size - 4}", Modifier.jaskFocusLift(1.05f).clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = .08f)).clickable { onOpen(part.card) }.padding(horizontal = 10.dp, vertical = 5.dp),
                color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** A sport's mark: ESPN's league logo where there is one, else its name set in its colour. */
@Composable
internal fun SportMark(sport: Sport, modifier: Modifier) {
    val colour = Color(sport.colour)
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(lerp(colour, Color.White, .08f), lerp(colour, Color.Black, .45f)))),
        contentAlignment = Alignment.Center) {
        if (sport.logo != null) AsyncImage(sport.logo, sport.label, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
        else Text(sport.label, Modifier.padding(horizontal = 6.dp), color = Color.White, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** A followed sport on the home: its mark, name and how many of its games are on now. Opens its page. */
@Composable
internal fun SportTile(sport: Sport, live: Int, onClick: () -> Unit) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // The ring goes round the mark only (jaskCard + jaskCardArt), so it never runs into the name under it.
    Column(Modifier.width(112.dp).jaskCard(source, key = "sport:${sport.key}", onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp)).jaskCardArt(source, 12.dp)) {
            SportMark(sport, Modifier.fillMaxSize())
            if (live > 0) Text("$live live", Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(6.dp)).background(LiveRed)
                .padding(horizontal = 5.dp, vertical = 1.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Text(sport.label, Modifier.padding(top = 7.dp, start = 2.dp), color = Color.White, style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
