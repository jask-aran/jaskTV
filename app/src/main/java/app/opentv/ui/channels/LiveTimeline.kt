package app.opentv.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.opentv.livetv.find.EventCard
import app.opentv.livetv.find.EventMatcher
import app.opentv.livetv.find.SportEvent
import app.opentv.livetv.find.SportShape
import app.opentv.livetv.find.Sports
import app.opentv.ui.jaskFocusLift
import app.opentv.ui.settings.JaskDim
import coil.compose.AsyncImage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LiveRed = Color(0xFFE5484D)
private val Rail = Color.White.copy(alpha = .14f)
private val TimeColumn = 64.dp
private const val HOUR = 3_600_000L
private const val PER_SPORT = 8

/** How long a game usually runs, when the schedule gives no end. */
private fun typicalLength(event: SportEvent): Long = when (event.sport) {
    "nba", "nhl", "ncaab" -> 5 * HOUR / 2
    "nfl", "ncaaf" -> 7 * HOUR / 2
    "mlb", "afl" -> 3 * HOUR
    "ufc" -> 5 * HOUR
    // A Test's day of play runs about seven hours; a one-day game about four.
    "cricket" -> if (event.meeting != null) 7 * HOUR else 4 * HOUR
    "nascar", "indycar" -> 3 * HOUR
    else -> 2 * HOUR
}

private fun SportEvent.span(): Pair<Long, Long> =
    startUtc to (endUtc ?: (startUtc + typicalLength(this))).coerceIn(startUtc + HOUR / 2, startUtc + 8 * HOUR)

/** The timeline's choices: what's been opened up. */
internal data class TimelineView(
    val earlier: Boolean = false, val full: Boolean = false,
    val everything: Boolean = false, val openSlots: Set<String> = emptySet(),
)

/**
 * One thing on the timeline: a game, or a race weekend standing for all its sessions. A weekend sits at its
 * session that matters now (the live one, else the next), so it appears once, not five times.
 */
private class Entry(val focus: EventCard, val sessions: List<EventCard>) {
    val key: String get() = focus.event.meeting ?: focus.event.id
    val start: Long get() = focus.event.startUtc
    val watchable: Boolean get() = sessions.any { it.options.isNotEmpty() }
    val weekend: Boolean get() = sessions.size > 1
}

private fun entries(cards: List<EventCard>, now: Long): List<Entry> {
    val (grouped, single) = cards.partition { it.event.meeting != null && Sports.byKey(it.event.sport)?.shape == SportShape.MEETING }
    val weekends = grouped.groupBy { it.event.meeting }.values.map { sessions ->
        val sorted = sessions.sortedBy { it.event.startUtc }
        val focus = sorted.firstOrNull { EventMatcher.isLive(it.event, now) } ?: sorted.firstOrNull { !it.event.finished } ?: sorted.last()
        Entry(focus, sorted)
    }
    return weekends + single.map { Entry(it, listOf(it)) }
}

private fun seriesName(league: String) = app.opentv.livetv.find.SportCalendar.seriesName(league)

/**
 * What's on when, as a vertical timeline: a time rail down the left, days as headings and a red "Now" marker
 * between what has started and what hasn't. How a sport is laid out follows its [SportShape]:
 *  - leagues (NBA): games that start together form a grid, compact when there are many; big slots fold to two rows.
 *  - race weekends (F1, MotoGP): one card per weekend with its sessions in a strip, placed at the session that's next.
 *  - series (cricket): each game names its match and series ("2nd Test · Day 4"); a sport's series can be picked.
 * Each sport shows its next 36 hours (at most [PER_SPORT] items, always at least its next one) until the whole
 * week is opened, so a busy NBA night can't push F1 off the list. Built from plain rows so a D-pad moves
 * predictably between them on TV.
 */
internal fun LazyListScope.sportsTimeline(
    cards: Map<String, List<EventCard>>, followed: Set<String>, now: Long, view: TimelineView,
    onView: (TimelineView) -> Unit, onOpen: (EventCard) -> Unit, wide: Boolean = false, onSport: (String) -> Unit = {},
) {
    val sports = Sports.all.filter { it.key in followed }
    val zone = ZoneId.systemDefault()
    val inSport = sports.flatMap { cards[it.key].orEmpty() }
    // Games none of your channels carry are hidden unless asked for: a cricket day can hold dozens of them.
    val all = entries(inSport, now)
    val hiddenCount = all.count { !it.watchable }
    val shown = if (view.everything) all else all.filter { it.watchable }
    val live = shown.filter { EventMatcher.isLive(it.focus.event, now) }
        .sortedWith(compareByDescending<Entry> { it.watchable }.thenBy { it.start })
    val earlier = shown.filter { it.focus.event.finished && it !in live }.sortedByDescending { it.start }
    val upcoming = shown.filter { !it.focus.event.finished && it !in live }.sortedBy { it.start }

    // The followed sports as tiles; each opens its own page (its week as a calendar, then a day's timeline).
    item(key = "tl-sports") {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(bottom = 8.dp)) {
            items(sports, key = { it.key }) { s ->
                // Live games known to have a picture (see NoPicture.showing): no count that flashes and then drops.
                SportTile(s, cards[s.key].orEmpty().count { it.options.isNotEmpty() && EventMatcher.isLive(it.event, now) && NoPicture.showing(it) }) { onSport(s.key) }
            }
        }
    }
    val toggleText = if (hiddenCount == 0) null else if (view.everything) "Only what's on your channels"
        else "$hiddenCount more not on your channels · Show"
    if (shown.isEmpty()) {
        item(key = "tl-none") { FindNote(if (hiddenCount > 0) "None of the next few days' games are on your channels." else "Nothing scheduled in the next few days.") }
        if (toggleText != null) item(key = "tl-all") { TimelineLink(toggleText, 16.dp) { onView(view.copy(everything = !view.everything)) } }
        return
    }
    val soon = if (view.full) upcoming else upcoming.groupBy { it.focus.event.sport }.values.flatMap { ofSport ->
        ofSport.filter { it.start < now + 36 * HOUR }.take(PER_SPORT).ifEmpty { ofSport.take(1) }
    }.sortedBy { it.start }
    val earlierText = if (earlier.isEmpty()) null else if (view.earlier) "Hide earlier games" else "Earlier · ${earlier.size} " + if (earlier.size == 1) "replay" else "replays"
    val moreText = if (soon.size < upcoming.size || view.full) (if (view.full) "Show less" else "Show the whole week · ${upcoming.size - soon.size} more") else null
    if (wide) {
        item(key = "tl-strip") {
            TimelineStrip(if (view.earlier) earlier.reversed() else emptyList(), live, soon, now, view, onView, onOpen,
                links = listOfNotNull(
                    earlierText?.let { it to { onView(view.copy(earlier = !view.earlier)) } },
                    moreText?.let { it to { onView(view.copy(full = !view.full)) } },
                    toggleText?.let { it to { onView(view.copy(everything = !view.everything)) } }))
        }
        return
    }
    if (earlierText != null) item(key = "tl-earlier") { TimelineLink(earlierText, 16.dp) { onView(view.copy(earlier = !view.earlier)) } }
    if (view.earlier) groups(earlier.reversed(), zone, "e", now, view, onView, onOpen)
    item(key = "tl-now") { NowMarker(if (live.isNotEmpty()) "On now" else "Now") }
    if (live.isNotEmpty()) item(key = "tl-live") { Slot("live", null, live, now, view, onView, onOpen) }
    groups(soon, zone, "u", now, view, onView, onOpen)
    // Toggles stay where they are when used, so a D-pad keeps its place.
    if (moreText != null) item(key = "tl-more") { TimelineLink(moreText, 16.dp + TimeColumn) { onView(view.copy(full = !view.full)) } }
    if (toggleText != null) item(key = "tl-all") { TimelineLink(toggleText, 16.dp + TimeColumn) { onView(view.copy(everything = !view.everything)) } }
}

private val StripHeight = 352.dp
private val ColumnWidth = 264.dp
private const val GAME_DP = 72
/** A race weekend's card: its header, then a row per session (see [WeekendBlock]). */
private fun weekendDp(e: Entry) = 62 + 28 * e.sessions.size
private const val GAP_DP = 8

/** One column of the sideways timeline: a moment and what starts then (a long slot runs over several columns). */
private class StripColumn(val id: String, val slot: String, val start: Long?, val items: List<Entry>, val first: Boolean, val fold: String?)

/**
 * The timeline laid sideways for TV and other wide screens: time runs left to right along a rail, each moment a
 * column with its games stacked under it, so left and right on a D-pad move through time and up and down through
 * a moment's games. Earlier games, then what's on now, then what's next. A moment with more games than fit folds
 * to a "+N more" tile; opened, it runs on over further columns. The links (earlier, whole week, every game) end the row.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TimelineStrip(earlier: List<Entry>, live: List<Entry>, soon: List<Entry>, now: Long, view: TimelineView,
    onView: (TimelineView) -> Unit, onOpen: (EventCard) -> Unit, links: List<Pair<String, () -> Unit>>) {
    val capacity = StripHeight.value.toInt() - 48
    fun height(e: Entry) = (if (e.weekend) weekendDp(e) else GAME_DP) + GAP_DP
    fun columns(id: String, start: Long?, entries: List<Entry>): List<StripColumn> {
        val open = id in view.openSlots
        val chunks = arrayListOf(mutableListOf<Entry>())
        var used = 0
        entries.forEach { e ->
            if (chunks.last().isNotEmpty() && used + height(e) > capacity) { chunks += mutableListOf<Entry>(); used = 0 }
            chunks.last() += e; used += height(e)
        }
        if (chunks.size <= 1) return listOf(StripColumn(id, id, start, chunks.firstOrNull().orEmpty(), true, null))
        if (!open) {
            // Folded: as many as fit above the fold tile.
            val shown = ArrayList<Entry>(); var h = GAME_DP + GAP_DP
            for (e in entries) { if (h + height(e) > capacity) break; shown += e; h += height(e) }
            return listOf(StripColumn(id, id, start, shown, true, "+${entries.size - shown.size} more"))
        }
        return chunks.mapIndexed { i, c -> StripColumn("$id-$i", id, start, c, i == 0, if (i == chunks.lastIndex) "Fewer" else null) }
    }
    val zone = ZoneId.systemDefault()
    val all = earlier.groupBy { it.start / 60_000 }.flatMap { (m, e) -> columns("e-$m", m * 60_000, e) } +
        (if (live.isNotEmpty()) columns("live", null, live) else emptyList()) +
        soon.groupBy { it.start / 60_000 }.flatMap { (m, e) -> columns("u-$m", m * 60_000, e) }
    val today = LocalDate.now(zone)
    val lanes = remember { StripLanes() }
    val focus = LocalFocusManager.current
    // As tall as its fullest column, so a quiet day doesn't leave a band of empty space.
    val tallest = all.maxOfOrNull { c -> c.items.sumOf { height(it) } + (if (c.fold != null) GAME_DP + GAP_DP else 0) } ?: GAME_DP
    LazyRow(Modifier.fillMaxWidth().height((tallest + 44 + 4).dp.coerceAtMost(StripHeight)), contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        var lastDay: LocalDate? = null
        all.forEach { col ->
            val day = col.start?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            val dayLabel = if (day != null && day != lastDay && col.first) when (day) {
                today -> "Today"; today.plusDays(1) -> "Tomorrow"; today.minusDays(1) -> "Yesterday"
                else -> DateTimeFormatter.ofPattern("EEE d MMM").format(day)
            } else null
            if (day != null) lastDay = day
            item(key = col.id) {
                // Focus anywhere in the column keeps its head (the time, "On now") in view below the top bar, not only
                // the focused game: on entering the column and after each Up/Down inside it.
                val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                val density = androidx.compose.ui.platform.LocalDensity.current
                val above = app.opentv.ui.LocalJaskContentPadding.current.calculateTopPadding() + 8.dp
                val reveal: () -> Unit = {
                    scope.launch {
                        runCatching { with(density) { bring.bringIntoView(androidx.compose.ui.geometry.Rect(0f, -above.toPx(), ColumnWidth.toPx(), 44.dp.toPx())) } }
                    }
                }
                Column(Modifier.width(ColumnWidth).fillMaxHeight()
                    .bringIntoViewRequester(bring)
                    .onFocusChanged { if (it.hasFocus) reveal() }.lane(lanes, col.id, focus, reveal)) {
                    StripHead(col, dayLabel, now)
                    Column(verticalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
                        val gate = Modifier.laneGate(lanes, col.id)
                        col.items.forEach { e ->
                            key(e.key) {
                                if (e.weekend) WeekendBlock(e, now, onOpen, gate)
                                else GameBlock(e.focus, now, false, gate.fillMaxWidth().height(GAME_DP.dp)) { onOpen(e.focus) }
                            }
                        }
                        col.fold?.let { label ->
                            FoldTile(label, gate.fillMaxWidth().height(GAME_DP.dp)) {
                                onView(view.copy(openSlots = if (col.slot in view.openSlots) view.openSlots - col.slot else view.openSlots + col.slot))
                            }
                        }
                    }
                }
            }
        }
        if (links.isNotEmpty()) item(key = "links") {
            Column(Modifier.width(260.dp).padding(top = 44.dp).lane(lanes, "links", focus), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                links.forEach { (text, action) -> TimelineLink(text, 8.dp, action) }
            }
        }
    }
}

/**
 * Up and Down in the sideways timeline stay in the focused moment's column, and from its last game leave the strip for
 * the row below. Left to 2D search, Down from a short column picked a lower game in another column (often far left)
 * over the row underneath. While a key is handled only that column's items can take focus.
 */
private class StripLanes { var only: String? = null }

/** On each focusable in a column too: a scroll container (the session strip) stops properties set further up. */
private fun Modifier.laneGate(lanes: StripLanes, id: String): Modifier =
    focusProperties { canFocus = lanes.only == null || lanes.only == id }

private fun Modifier.lane(lanes: StripLanes, id: String, focus: FocusManager, after: () -> Unit = {}): Modifier =
    laneGate(lanes, id)
        .onPreviewKeyEvent { e ->
            val native = e.nativeKeyEvent
            val direction = when (native.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                android.view.KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                else -> null
            }
            if (direction == null) return@onPreviewKeyEvent false
            // Both halves of the press are ours, so nothing else moves focus a second time on key up.
            if (native.action == android.view.KeyEvent.ACTION_DOWN) {
                lanes.only = id
                val moved = try { focus.moveFocus(direction) } finally { lanes.only = null }
                if (moved) after()
            }
            true
        }

/** A column's head: the day where it changes, the time (or "On now"), and the rail running through. */
@Composable
private fun StripHead(col: StripColumn, dayLabel: String?, now: Long) {
    Box(Modifier.fillMaxWidth().height(44.dp).drawBehind {
        // The rail runs across every column (through the gaps too), a dot at each moment.
        drawLine(if (col.start == null) LiveRed else Rail, Offset(-6.dp.toPx(), size.height - 9.dp.toPx()),
            Offset(size.width + 6.dp.toPx(), size.height - 9.dp.toPx()), 1.5.dp.toPx())
    }) {
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
            if (col.start == null) Text("On now", Modifier.clip(RoundedCornerShape(50)).background(LiveRed).padding(horizontal = 8.dp, vertical = 2.dp),
                color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            // The same words as everywhere else (liveWhen): the day where it changes ("Tomorrow 12:30 am"), else the time.
            else if (col.first) Text(if (dayLabel != null) liveWhen(col.start) else liveClock(col.start), color = Color.White,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (col.first) Box(Modifier.align(Alignment.BottomStart).padding(bottom = 4.dp).size(10.dp).clip(CircleShape)
            .background(if (col.start == null) LiveRed else Color(0xFF8D8985)))
    }
}

@Composable
private fun TimelineLink(text: String, start: Dp, onClick: () -> Unit) =
    Box(Modifier.fillMaxWidth().padding(start = start - 8.dp, end = 16.dp)) {
        Text(text, Modifier.jaskFocusLift(1.06f).clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
            color = JaskDim, style = MaterialTheme.typography.labelLarge)
    }

/** Day headings, then one row per start time. */
private fun LazyListScope.groups(entries: List<Entry>, zone: ZoneId, prefix: String, now: Long, view: TimelineView,
    onView: (TimelineView) -> Unit, onOpen: (EventCard) -> Unit) {
    val today = LocalDate.now(zone)
    val dayName = DateTimeFormatter.ofPattern("EEEE d MMMM")
    entries.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }.forEach { (day, ofDay) ->
        item(key = "tl-$prefix-d$day") {
            val label = when (day) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; today.minusDays(1) -> "Yesterday"; else -> dayName.format(day) }
            Text(label, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), color = Color.White,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        ofDay.groupBy { it.start / 60_000 }.forEach { (minute, together) ->
            val id = "$prefix-$minute"
            item(key = "tl-$id") { Slot(id, minute * 60_000, together, now, view, onView, onOpen) }
        }
    }
}

@Composable
private fun NowMarker(label: String) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.clip(RoundedCornerShape(50)).background(LiveRed).padding(horizontal = 8.dp, vertical = 2.dp),
            color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Box(Modifier.weight(1f).height(1.5.dp).background(LiveRed))
    }
}

/**
 * One moment on the rail: its time (or a live dot), then what starts then. Race weekends take a full row each;
 * games share rows as a grid that fits the screen (2 across on a phone, 4–6 on a TV), compact when there are
 * two or more. A slot of more than two rows folds, with a tile that opens it.
 */
@Composable
private fun Slot(id: String, start: Long?, entries: List<Entry>, now: Long, view: TimelineView,
    onView: (TimelineView) -> Unit, onOpen: (EventCard) -> Unit) {
    val railX = 16.dp + 54.dp
    Row(Modifier.fillMaxWidth().drawBehind {
        // The rail runs through every row; each row's dot sits on it beside its time.
        drawLine(Rail, Offset(railX.toPx(), 0f), Offset(railX.toPx(), size.height), 1.5.dp.toPx())
    }) {
        Box(Modifier.width(16.dp + TimeColumn).height(64.dp)) {
            if (start != null) Column(Modifier.padding(start = 16.dp, top = 18.dp)) {
                // One clock everywhere ("9:00 am"); the day is the heading above.
                // Small enough to stay clear of the rail's dot at 70dp.
                Text(liveClock(start), color = Color.White, style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
            Box(Modifier.padding(start = railX - 5.dp, top = 26.dp).size(10.dp).clip(CircleShape)
                .background(if (start == null) LiveRed else Color(0xFF8D8985)))
        }
        BoxWithConstraints(Modifier.weight(1f).padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
            val weekends = entries.filter { it.weekend }
            val games = entries.filterNot { it.weekend }
            val compact = games.size >= 2
            val columns = (maxWidth / (if (compact) 150.dp else 260.dp)).toInt().coerceIn(1, 6)
            val open = id in view.openSlots
            val folds = games.size > columns * 2
            val visible = if (folds && !open) games.take(columns * 2 - 1) else games
            val tall = if (compact) 64.dp else 72.dp
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                weekends.forEach { key(it.key) { WeekendBlock(it, now, onOpen) } }
                // The fold tile keeps its place at the end of the grid whether open or not.
                val tiles: List<Entry?> = if (folds) visible + null else visible
                tiles.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { entry ->
                            if (entry == null) key("fold") {
                                FoldTile(if (open) "Fewer" else "+${games.size - visible.size} more", Modifier.weight(1f).height(tall)) {
                                    onView(view.copy(openSlots = if (open) view.openSlots - id else view.openSlots + id))
                                }
                            } else key(entry.key) {
                                GameBlock(entry.focus, now, compact, Modifier.weight(1f).height(tall)) { onOpen(entry.focus) }
                            }
                        }
                        // Keep a short last row's tiles the same width as the rest.
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoldTile(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.jaskFocusLift(1.04f).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .08f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** The line under a game's title: its match and series for cricket, else its sport. */
private fun subtitle(event: SportEvent, replay: Boolean = false): String = when (Sports.byKey(event.sport)?.shape) {
    SportShape.SERIES -> listOfNotNull(event.session, seriesName(event.league)).joinToString(" · ")
    else -> Sports.byKey(event.sport)?.label.orEmpty()
} + if (event.finished) (if (replay) " · Replay" else " · Ended") else ""

/** A game on the timeline: its teams' colours, logos, title, and where it's on (or how far in, when live). */
@Composable
private fun GameBlock(card: EventCard, now: Long, compact: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val event = card.event
    val live = EventMatcher.isLive(event, now)
    val watchable = card.options.isNotEmpty()
    val (a, b) = event.colours()
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.jaskFocusLift(1.04f).alpha(if (watchable) 1f else .5f).clip(shape)
        .background(Brush.horizontalGradient(listOf(lerp(a, Color.Black, .2f), lerp(b, Color.Black, .5f))))
        .border(if (live) 1.5.dp else 1.dp, if (live) LiveRed else Color.White.copy(alpha = .08f), shape)
        .clickable(onClick = onClick)) {
        Row(Modifier.fillMaxSize().padding(horizontal = if (compact) 8.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val logos = event.competitors.mapNotNull { it.logo }.take(2).ifEmpty { listOfNotNull(event.leagueLogo) }
            if (logos.isNotEmpty()) {
                val size = if (compact) 22.dp else 28.dp
                Box(Modifier.size(width = if (logos.size == 2) size + 18.dp else size + 4.dp, height = size + 8.dp)) {
                    logos.forEachIndexed { i, url ->
                        AsyncImage(url, null, Modifier.padding(start = (i * if (compact) 14 else 18).dp, top = (i * 8).dp).size(size), contentScale = ContentScale.Fit)
                    }
                }
                Spacer(Modifier.width(if (compact) 6.dp else 10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(heroTitle(event), color = Color.White, style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold, maxLines = if (compact) 2 else 1, overflow = TextOverflow.Ellipsis,
                    lineHeight = if (compact) MaterialTheme.typography.labelMedium.fontSize * 1.15f else androidx.compose.ui.unit.TextUnit.Unspecified)
                val where = card.leadOption()?.title?.let { liveName(it) + if (card.options.size > 1) " +${card.options.size - 1}" else "" }
                    ?: "Not on your channels"
                Text(where, color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (live) LiveProgress(event, now, Modifier.padding(top = 5.dp))
                else if (!compact || event.sport == "cricket") {
                    // Not when it only repeats the line above ("NBA Pass" over "NBA").
                    val sub = subtitle(event, card.options.any { it.state == app.opentv.livetv.find.WatchState.REPLAY })
                    if (!where.contains(sub, ignoreCase = true)) Text(sub, color = Color.White.copy(alpha = .5f),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun LiveProgress(event: SportEvent, now: Long, modifier: Modifier) {
    val (s, e) = event.span()
    val done = ((now - s).toFloat() / (e - s)).coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .2f))) {
        Box(Modifier.fillMaxWidth(done).fillMaxHeight().background(LiveRed))
    }
}

/** A short name for a session chip: "Practice 1"/"FP1" → "P1", "Qualifying 2" → "Q2", "Qual" → "Quali". */
private fun shortSession(name: String?): String {
    val n = name.orEmpty().trim()
    Regex("""^(?:FP|Practice\s*)(\d)$""", RegexOption.IGNORE_CASE).find(n)?.let { return "P" + it.groupValues[1] }
    Regex("""^Qualifying\s*(\d)$""", RegexOption.IGNORE_CASE).find(n)?.let { return "Q" + it.groupValues[1] }
    return when {
        n.equals("Qual", true) || n.equals("Qualifying", true) -> "Quali"
        n.equals("Sprint qualifying", true) || n.equals("Sprint shootout", true) -> "Sprint Q"
        n.isBlank() -> "Race"
        else -> n.replaceFirstChar(Char::uppercase)
    }
}

/** "Heineken Las Vegas Grand Prix · Race" → "Las Vegas Grand Prix" (title sponsors dropped). */
internal fun weekendTitle(event: SportEvent): String =
    event.title.substringBefore(" · ").replace(SPONSOR, "")

private val SPONSOR = Regex("""^(Heineken|Pirelli|Aramco|Qatar Airways|MSC Cruises|Crypto\.com|Lenovo|AWS|Etihad Airways|Louis Vuitton|Gulf Air|STC|Tissot|Motul|Repsol|Petronas)\s+""", RegexOption.IGNORE_CASE)

/**
 * A race weekend as one card: the series' colour and logo, the Grand Prix, where it's on, and every session in a
 * strip (done ones ticked, the live one red, the next one lit). Each session opens its own ways to watch.
 */
@Composable
private fun WeekendBlock(entry: Entry, now: Long, onOpen: (EventCard) -> Unit, gate: Modifier = Modifier) {
    val event = entry.focus.event
    val (a, b) = event.colours()
    val shape = RoundedCornerShape(14.dp)
    val live = EventMatcher.isLive(event, now)
    // Down from the weekend lands on its first session, not whichever chip sits under the card's middle.
    val firstSession = remember { FocusRequester() }
    Column(Modifier.fillMaxWidth().alpha(if (entry.watchable) 1f else .5f).clip(shape)
        .background(Brush.horizontalGradient(listOf(lerp(a, Color.Black, .15f), lerp(b, Color.Black, .45f))))
        .border(if (live) 1.5.dp else 1.dp, if (live) LiveRed else Color.White.copy(alpha = .08f), shape)) {
        Row(gate.focusProperties { down = firstSession }.jaskFocusLift(1.02f).fillMaxWidth().clickable { onOpen(entry.focus) }.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (event.leagueLogo != null) AsyncImage(event.leagueLogo, null, Modifier.size(34.dp), contentScale = ContentScale.Fit)
            else Text(event.league, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(weekendTitle(event), color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val where = rankWays(event.sport, entry.sessions.flatMap { it.options }.distinctBy { it.title })
                val label = (if (live) "${shortSession(event.session)} live" else "Next: " + shortSession(event.session)) +
                    " · " + (where.firstOrNull()?.title?.let { liveName(it) + if (where.size > 1) " +${where.size - 1}" else "" } ?: "Not on your channels")
                Text(label, color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // A row per session, all in view: nothing scrolled out of sight can take focus. Down from the header lands on
        // the first; the next one is marked by an outline and "Next" (never a white fill, which reads as focus).
        Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val zone = ZoneId.systemDefault()
            entry.sessions.forEachIndexed { i, s ->
                val sLive = EventMatcher.isLive(s.event, now)
                val done = s.event.finished && !sLive
                val next = s === entry.focus && !sLive
                val rowShape = RoundedCornerShape(8.dp)
                Row(gate.then(if (i == 0) Modifier.focusRequester(firstSession) else Modifier).jaskFocusLift(1.03f).fillMaxWidth().height(24.dp)
                    .clip(rowShape).background(if (sLive) LiveRed else Color.Black.copy(alpha = if (next) .4f else .22f))
                    .border(1.dp, if (next) Color.White.copy(alpha = .85f) else Color.Transparent, rowShape)
                    .clickable { onOpen(s) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val ink = Color.White.copy(alpha = if (done) .55f else 1f)
                    Text((if (done) "✓ " else "") + shortSession(s.event.session), Modifier.weight(1f), color = ink,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (next) Text("Next  ", color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Text(if (sLive) "Live" else liveWhen(s.event.startUtc, zone), color = ink.copy(alpha = if (done) .55f else .85f),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}
