package app.opentv.ui.channels

import kotlinx.coroutines.launch
import app.opentv.ui.jaskWhiteFill
import app.opentv.ui.jaskCard
import app.opentv.ui.jaskCardArt
import app.opentv.ui.jaskTouchOnly
import app.opentv.ui.jaskFocusLift
import app.opentv.ui.jaskFocusEntry
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import app.opentv.livetv.find.EventCard
import app.opentv.livetv.find.EventMatcher
import app.opentv.livetv.find.FindChannel
import app.opentv.livetv.find.LiveClassifier
import app.opentv.livetv.find.SportEvent
import app.opentv.livetv.find.WatchOption
import app.opentv.livetv.find.WatchState
import app.opentv.ui.JaskSheet
import app.opentv.ui.settings.JaskDim
import app.opentv.ui.settings.JaskInk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private val Canvas = Color(0xFF141312)
private val LiveRed = Color(0xFFE5484D)
private val F1Red = Color(0xFFB0120A)

/** A team's colour, lifted off black so dark brands (Spurs, Nets) still read; null → a neutral. */
private fun teamColour(value: Long?, fallback: Color): Color {
    val c = value?.let { Color(it) } ?: return fallback
    val luminance = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
    return if (luminance < 0.08f) lerp(c, Color(0xFF3A3632), 0.6f) else c
}

internal fun SportEvent.colours(): Pair<Color, Color> = when {
    competitors.size >= 2 -> teamColour(competitors[0].color, Color(0xFF34404F)) to teamColour(competitors[1].color, Color(0xFF4A3B35))
    sport == "f1" -> F1Red to Color(0xFF2A0B09)
    sport == "motogp" -> Color(0xFF3A3F47) to Color(0xFFA3171C)
    sport == "indycar" -> Color(0xFF1B3F8B) to Color(0xFF0E1A33)
    sport == "nascar" -> Color(0xFFE2A400) to Color(0xFF13306B)
    else -> Color(0xFF2E3A46) to Color(0xFF1F2328)
}

/**
 * The artwork for an event: its teams' colours meeting on a diagonal with their logos, or the league's
 * logo (F1) on its colour. Sport has no posters; this is its poster.
 */
@Composable
// logoAt: where the logos sit. A card with its title over the art (the phone hero) lifts them clear of a two-line title.
internal fun MatchupArt(event: SportEvent, modifier: Modifier, logoSize: Dp, logoAt: Alignment = Alignment.Center, content: @Composable BoxScope.() -> Unit = {}) {
    val (a, b) = event.colours()
    // A smooth blend between the two sides (a hard seam read as a rendering fault).
    Box(modifier.background(Brush.linearGradient(0f to a, 0.3f to a, 0.7f to b, 1f to b))) {
        // A soft vignette so white type and logos hold on bright brand colours.
        Box(Modifier.matchParentSize().background(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .45f)))))
        val sides = event.competitors.take(2)
        if (sides.size == 2 && sides.any { it.logo != null }) {
            Row(Modifier.align(logoAt).padding(bottom = logoSize * 0.35f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(logoSize * 0.28f)) {
                sides.forEach { side ->
                    Box(Modifier.size(logoSize), contentAlignment = Alignment.Center) {
                        side.logo?.let { AsyncImage(it, side.names.firstOrNull(), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                            ?: Text(side.names.firstOrNull().orEmpty().take(3).uppercase(), color = Color.White,
                                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                    }
                }
            }
        } else if (event.leagueLogo != null) {
            AsyncImage(event.leagueLogo, event.league, Modifier.align(logoAt).padding(bottom = logoSize * 0.35f).size(logoSize * 1.4f),
                contentScale = ContentScale.Fit)
        } else Text(event.league, Modifier.align(logoAt).padding(bottom = logoSize * 0.35f), color = Color.White,
            // Sized to the art, not a fixed headline: "MotoGP" was cut to "Moto" in a list thumbnail.
            fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { (logoSize * 0.42f).toSp() }, maxLines = 1, softWrap = false,
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        content()
    }
}

/**
 * Streams found to send only black frames (a provider's event channel before or after its event, a dead feed): not
 * really live. Parked for 10 minutes, since an event channel can come on later; previews skip them and the games
 * they belong to drop below games with a picture. Kept for this run of the app only.
 */
internal object NoPicture {
    /** Long enough not to retry on every visit, short enough to catch an event slot coming on. */
    private const val PARK_MILLIS = 10 * 60_000L
    private val marks = androidx.compose.runtime.mutableStateMapOf<Long, Long>()
    fun mark(channelId: Long) { marks[channelId] = System.currentTimeMillis() }
    fun parked(channelId: Long): Boolean = marks[channelId]?.let { System.currentTimeMillis() - it < PARK_MILLIS } == true
    /** A game whose live channels have all shown no picture. */
    fun parked(card: EventCard): Boolean =
        card.liveStreams().let { ids -> ids.isNotEmpty() && ids.all(::parked) }

    /** Channels a preview has shown a real picture on (this run of the app). */
    private val pictures = androidx.compose.runtime.mutableStateMapOf<Long, Long>()
    fun confirm(channelId: Long) { pictures[channelId] = System.currentTimeMillis(); marks.remove(channelId) }
    /** A preview (or a check) saw video on this channel in the last few minutes. */
    fun seen(channelId: Long): Boolean = pictures[channelId]?.let { System.currentTimeMillis() - it < 3 * 60_000 } == true

    /**
     * Whether a live game can be counted as on ("2 live"). A real channel (the guide or a broadcaster says so) counts
     * unless parked. A game only provider event slots carry counts once a preview has seen a picture on one: those
     * slots are often black before and after their event, and counting them first made the badge flash.
     */
    fun showing(card: EventCard): Boolean {
        if (parked(card)) return false
        val now = System.currentTimeMillis()
        // Any live way to watch counts, not just the first: a dead event slot listed first mustn't hide the guide's channels.
        return card.options.filter { it.state == WatchState.LIVE }.ifEmpty { card.options }.any { o ->
            !o.streamIds.all(::parked) && (o.evidence != app.opentv.livetv.find.WatchEvidence.NAMED_BY_PROVIDER ||
                o.streamIds.any { pictures[it]?.let { t -> now - t < PARK_MILLIS } == true })
        }
    }
}

/** The way to watch a game to name and play first: the best by rankedOptions (known-good, not just shown dead). */
internal fun EventCard.leadOption(): WatchOption? = rankedOptions().firstOrNull()

/** Every live way to watch, in order: a game has "no picture" only when all of them have none, not just the first. */

private fun EventCard.liveStreams(): List<Long> =
    options.filter { it.state == WatchState.LIVE }.ifEmpty { options.take(1) }.flatMap { it.streamIds }.distinct()

/**
 * The top of Live TV: one large card for the selected game, with its live picture, and beside it (below on a phone)
 * a short list of the other games. Focusing a game in the list selects it, so there is only ever one preview stream,
 * and nothing scrolls or cycles. The large card's Watch plays the game; on touch, tapping its art lists where to watch.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LiveHero(cards: List<EventCard>, previewEnabled: Boolean, onWatch: (EventCard) -> Unit, onOpen: (EventCard) -> Unit) {
    // Games with a picture first: a parked one (see NoPicture) drops below them. The order is set when the games
    // change, not each time a preview parks one, so the list doesn't reshuffle under you while previews are tried.
    val order = remember(cards.map { it.event.id }) { cards.sortedBy { if (NoPicture.parked(it)) 1 else 0 }.map { it.event.id } }
    val shown = order.mapNotNull { id -> cards.firstOrNull { it.event.id == id } }.take(4)
    var selectedId by remember { mutableStateOf(shown.firstOrNull()?.event?.id) }
    val selected = shown.firstOrNull { it.event.id == selectedId } ?: shown.firstOrNull() ?: return
    // The preview follows the selection a moment after it settles, so moving through the list doesn't open a stream per game.
    var previewId by remember { mutableStateOf(selected.event.id) }
    LaunchedEffect(selected.event.id) { delay(500); previewId = selected.event.id }
    // What the preview may play: an event slot only while its name, re-read from the provider, still names this game
    // (slots are renamed as events end). Checked once per game per 10 minutes.
    val context = androidx.compose.ui.platform.LocalContext.current
    val finder = remember(context) { app.opentv.livetv.find.LiveFinder(context) }
    val verified = remember { androidx.compose.runtime.mutableStateMapOf<String, Pair<Long, List<Long>>>() }
    val previewWay = remember { androidx.compose.runtime.mutableStateMapOf<String, LiveFallback.Way>() }
    // How many of the game's ways have shown no picture: when the preview finds one dead, it moves to the next.
    val deadWays = shown.firstOrNull { it.event.id == previewId }?.options?.count { o -> o.streamIds.isNotEmpty() && o.streamIds.all(NoPicture::parked) } ?: 0
    LaunchedEffect(previewId, previewEnabled, deadWays) {
        if (!previewEnabled) return@LaunchedEffect
        val card = shown.firstOrNull { it.event.id == previewId } ?: return@LaunchedEffect
        val at = System.currentTimeMillis()
        if (verified[previewId]?.let { at - it.first < 10 * 60_000 && !it.second.all(NoPicture::parked) } == true) return@LaunchedEffect
        val live = rankWays(card.event.sport, card.options.filter { it.state == WatchState.LIVE }.ifEmpty { card.options })
        val option = live.firstOrNull { o -> !o.streamIds.all(NoPicture::parked) } ?: live.firstOrNull() ?: return@LaunchedEffect
        previewWay[card.event.id] = LiveFallback.Way(card.event.sport, option.title)
        val ids = if (option.evidence == app.opentv.livetv.find.WatchEvidence.NAMED_BY_PROVIDER)
            runCatching { finder.stillShowing(option.streamIds, card.event) }.getOrDefault(emptyList()) else option.streamIds
        verified[card.event.id] = at to ids
    }
    app.opentv.ui.JaskAmbientFrom(selected.event.let { it.competitors.firstNotNullOfOrNull { c -> c.logo } ?: it.leagueLogo })
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    val top = app.opentv.ui.LocalJaskContentPadding.current.calculateTopPadding()
    val big: @Composable (Modifier) -> Unit = { modifier ->
        val event = selected.event
        val parked = NoPicture.parked(selected)
        val live = EventMatcher.isLive(event, System.currentTimeMillis()) && !parked
        MatchupArt(event, modifier.clip(RoundedCornerShape(16.dp)).jaskTouchOnly().clickable { onOpen(selected) }, logoSize = if (wide) 84.dp else 56.dp,
            logoAt = if (wide) Alignment.Center else androidx.compose.ui.BiasAlignment(0f, -0.55f)) {
            if (live && previewEnabled && previewId == event.id) LivePreview(verified[event.id]?.second.orEmpty(), enabled = true, modifier = Modifier.fillMaxSize(), way = previewWay[event.id])
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = .78f))))
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(heroTitle(event), color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    // One line: the status chip, then the league and channel as plain facts.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (live) Chip(LiveRed.copy(alpha = .9f)) { Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                        else if (parked) Chip { Text("No picture yet", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                        else Chip { Text(if (event.finished) "Ended" else whenLabel(event.startUtc), color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                        val facts = listOfNotNull(leagueLabel(event), selected.leadOption()?.title?.let { liveName(it) }).joinToString(" · ")
                        Text(facts, color = Color(0xFFEDEDED), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 10.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Row(Modifier.jaskFocusLift(key = "live:watch").jaskFocusEntry().height(44.dp).clip(RoundedCornerShape(50)).jaskWhiteFill(RoundedCornerShape(50))
                    .clickable { onWatch(selected) }.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (live) "Watch live" else "Watch", color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    // Entering the list (Right from the large card) lands on the game it shows, not the nearest row.
    val entry = remember { androidx.compose.ui.focus.FocusRequester() }
    val list: @Composable (Modifier, Boolean) -> Unit = { modifier, fill ->
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        Column(modifier.focusProperties { enter = { entry } }.focusGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEach { card ->
                val isSelected = card.event.id == selected.event.id
                // Wide: the rows share the large card's height, so all of them fit without scrolling.
                LiveListRow(card, Modifier.then(if (fill) Modifier.weight(1f) else Modifier.height(58.dp))
                    .then(if (isSelected) Modifier.focusRequester(entry) else Modifier),
                    selected = isSelected, onFocus = { selectedId = card.event.id }) {
                    if (card.event.id == selected.event.id) onWatch(card) else { selectedId = card.event.id; if (!wide) onOpen(card) }
                }
            }
        }
    }
    if (wide) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = top + 4.dp, bottom = 8.dp)
        .height((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.42f).dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        big(Modifier.weight(1.6f).fillMaxHeight())
        if (shown.size > 1) list(Modifier.weight(1f).fillMaxHeight(), true)
    } else Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = top + 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        big(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        if (shown.size > 1) list(Modifier.fillMaxWidth(), false)
    }
}

/** One game in the Live TV top's list: a small matchup, its title and whether it's live. Focus selects it. */
@Composable
private fun LiveListRow(card: EventCard, modifier: Modifier, selected: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    val event = card.event
    val parked = NoPicture.parked(card)
    val live = EventMatcher.isLive(event, System.currentTimeMillis()) && !parked
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }
    // The usual ring on the focused row; a faint fill on the one shown in the large card.
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(Color.White.copy(alpha = if (selected) .08f else 0f))
        .clickable(source, app.opentv.ui.JaskIndication, onClick = onClick).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        MatchupArt(event, Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)), logoSize = 18.dp)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(heroTitle(event), color = Color(0xFFF6F3EF), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (live) SmallChip(LiveRed) { Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                Text(if (live) leagueLabel(event) else if (parked) "No picture yet" else if (event.finished) "Ended" else whenLabel(event.startUtc), color = JaskDim,
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 16:9 game card in the home's wide-card style; tap for where to watch. */
@Composable
internal fun EventTile(card: EventCard, modifier: Modifier, onClick: () -> Unit) {
    val event = card.event
    val live = EventMatcher.isLive(event, System.currentTimeMillis())
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(modifier.jaskCard(source, key = "event:${event.id}", onClick = onClick)) {
        MatchupArt(event, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).jaskCardArt(source), logoSize = 46.dp) {
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = .75f))))
            Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (live) SmallChip(LiveRed) { Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                else if (event.finished) SmallChip { Text(if (card.options.any { it.state == WatchState.REPLAY }) "Replay" else "Ended", color = Color.White, style = MaterialTheme.typography.labelSmall) }
            }
            Text(card.leadOption()?.title?.let { liveName(it) }.orEmpty() + if (card.options.size > 1) "  +${card.options.size - 1}" else "",
                Modifier.align(Alignment.BottomStart).padding(8.dp), color = Color(0xFFE6E6E6), style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(heroTitle(event), Modifier.padding(top = 6.dp), color = Color(0xFFF6F3EF), style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (live) "Live now" else if (event.finished) "Ended" else whenLabel(event.startUtc), color = JaskDim,
            style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/**
 * A channel as a poster. With a trusted guide listing it shows what is on now: the programme's own picture
 * (logo as a badge) when the guide has one, else the logo plate with a progress line. Name underneath.
 */
@Composable
internal fun ChannelTile(channel: FindChannel, modifier: Modifier, showRegion: Boolean = false, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val nowOn = remember(context) { app.opentv.livetv.find.NowOn(context) }
    var programme by remember(channel.key) { mutableStateOf<app.opentv.data.model.Programme?>(null) }
    LaunchedEffect(channel.key) {
        while (true) {
            programme = runCatching { nowOn.lookup(listOf(channel))[channel.key] }.getOrNull()
            delay(60_000)
        }
    }
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // Hold OK (press and hold on a phone): add the channel to My TV, its best copy, as the search results' Add does.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val addToMyTv: () -> Unit = {
        scope.launch {
            val graph = app.opentv.core.ServiceLocator.get(context)
            val best = graph.database.channels().byIds(channel.streamIds.take(1)).firstOrNull()
            val message = if (best == null) "Couldn't add ${channel.name}" else runCatching {
                app.opentv.livetv.MyTvRepository(context).add(best); "Added ${channel.name} to My TV"
            }.getOrElse { "Couldn't add ${channel.name}" }
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    Column(modifier.jaskCard(source, key = "channel:${channel.key}", onLongClick = addToMyTv, onClick = onClick)) {
        val art = programme?.iconUrl?.takeIf(String::isNotBlank)
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)).jaskCardArt(source)) {
            // The big logo plate until the programme's picture has actually loaded: a slow or broken picture otherwise
            // left a dark box with a small badge, next to tiles showing a full logo.
            var artShown by remember(art) { mutableStateOf(false) }
            val label = liveName(channel.name.substringBefore(" · "), channel.key.startsWith("slot:"))
            if (!artShown) LogoPlate(channel.logoUrl, label, Modifier.matchParentSize())
            if (art != null) {
                AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                    onSuccess = { artShown = true }, onError = { artShown = false })
                if (artShown) {
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = .7f))))
                    LogoPlate(channel.logoUrl, label, Modifier.align(Alignment.TopStart).padding(6.dp).size(46.dp, 29.dp), corner = 6.dp)
                }
            }
            programme?.let { p ->
                val now = System.currentTimeMillis()
                val done = ((now - p.startUtcMillis).toFloat() / (p.endUtcMillis - p.startUtcMillis).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = .35f))) {
                    Box(Modifier.fillMaxWidth(done).fillMaxHeight().background(Color.White.copy(alpha = .9f)))
                }
            }
        }
        Text(liveName(channel.name, channel.key.startsWith("slot:")), Modifier.padding(top = 5.dp), color = Color(0xFFF6F3EF), style = MaterialTheme.typography.labelLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        // What's on beats metadata; otherwise the region when several are chosen. Service bundles ("AT&T") are never shown.
        val detail = programme?.title ?: LiveClassifier.regionLabel(channel.region).takeIf { showRegion && channel.region.isNotEmpty() }
        if (detail != null) Text(detail, color = JaskDim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A logo on its plate. The plate's colour comes from the logo (its brand colour, its own background, or a light
 * plate for dark wordmarks), and the logo is trimmed and sized by shape so wide wordmarks and square badges
 * read at the same weight. The name stands in until the logo arrives, and stays when it never does.
 */
@Composable
internal fun LogoPlate(url: String?, name: String, modifier: Modifier, corner: Dp = 10.dp) {
    var look by remember(url) { mutableStateOf(LogoLook.cached(url)) }
    val shown = look ?: LogoLook.Neutral
    // Settle into the logo's colour rather than jumping from grey (already-known logos start in it).
    val top by androidx.compose.animation.animateColorAsState(shown.top, androidx.compose.animation.core.tween(400), label = "plateTop")
    val bottom by androidx.compose.animation.animateColorAsState(shown.bottom, androidx.compose.animation.core.tween(400), label = "plateBottom")
    BoxWithConstraints(modifier.clip(RoundedCornerShape(corner)).background(Brush.verticalGradient(listOf(top, bottom)))
        .border(1.dp, Color.White.copy(alpha = if (shown.light) 0f else .07f), RoundedCornerShape(corner)), contentAlignment = Alignment.Center) {
        if (look == null) Text(name, Modifier.padding(8.dp), color = JaskInk.copy(alpha = .85f),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (url != null) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val request = remember(url) {
                coil.request.ImageRequest.Builder(context).data(url).allowHardware(false).transformations(TrimTransparent).build()
            }
            // Optical sizing: square badges take ~60% of the height, wide wordmarks ~78% of the width.
            val a = shown.aspect
            val logoModifier = when {
                a >= 2.6f -> Modifier.fillMaxWidth(0.78f)
                a >= 1.4f -> Modifier.fillMaxWidth(0.66f)
                else -> Modifier.fillMaxHeight(0.6f)
            }
            AsyncImage(request, null, logoModifier.then(Modifier.aspectRatio(a.coerceIn(0.4f, 6f))), contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    val bitmap = (state.result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    look = if (bitmap != null) LogoLook.of(url, bitmap) else LogoLook.Neutral
                })
        }
    }
}

/** Where to watch one game: its art, then every option with its state and evidence. */
@Composable
internal fun WatchSheet(card: EventCard, regions: Set<String>, onDismiss: () -> Unit, onPlay: (WatchOption) -> Unit) {
    JaskSheet(onDismiss) {
        // The sheet's own scroll (JaskSheet caps it at 85% of the screen): a game on many channels lists them all, and a
        // swipe on the sheet moves the sheet, not the page behind it.
        Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
        MatchupArt(card.event, Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(2.2f).clip(RoundedCornerShape(14.dp)), logoSize = 56.dp)
        Text(heroTitle(card.event), Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), color = Color.White,
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(listOf(if (EventMatcher.isLive(card.event, System.currentTimeMillis())) "Live now" else whenLabel(card.event.startUtc), leagueLabel(card.event)).joinToString(" · "),
            Modifier.padding(horizontal = 20.dp), color = JaskDim, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        // Ways known to have no picture right now (NoPicture) go last, so the top row is one that plays.
        val liveNow = EventMatcher.isLive(card.event, System.currentTimeMillis())
        // Order and whether to show stills are settled when the sheet opens, so rows don't move while stills arrive.
        val ordered = remember(card.event.id) { card.rankedOptions() }
        val withFrames = remember(card.event.id) { framesWorthShowing(card, liveNow) }
        val frames = if (withFrames) rememberSheetFrames(card, ordered.filter { o -> o.streamIds.isNotEmpty() && !o.streamIds.all(NoPicture::parked) }) else null
        ordered.forEach { option ->
            WatchRow(option, regions, liveNow, frames) { LiveFallback.picked = frames != null; onDismiss(); onPlay(option) }
        }
        Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
// liveNow: the game is on by the clock now. Cards saved before kick-off still call their ways "upcoming" until rebuilt.
private fun WatchRow(option: WatchOption, regions: Set<String>, liveNow: Boolean, frames: SheetFrames? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        // A neutral play button like everywhere else; "Live" in the line beside it (and the red LIVE chip) says it's live.
        if (frames != null) FrameSlot(frames, option.title, Modifier.size(96.dp, 54.dp))
        else Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = if (option.state == WatchState.LIVE) .2f else .12f)),
            contentAlignment = Alignment.Center) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(liveName(option.title), color = JaskInk, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val dead = option.streamIds.isNotEmpty() && option.streamIds.all(NoPicture::parked)
            val state = if (dead) "No picture right now" else when (option.state) { WatchState.LIVE -> "Live"; WatchState.REPLAY -> "Replay"; WatchState.UPCOMING -> if (liveNow) "Live" else null }
            Text(listOfNotNull(state, LiveClassifier.regionLabel(option.region).takeIf { option.region !in regions }, option.evidence.label.takeUnless { it.equals(option.title, ignoreCase = true) }).joinToString(" · "),
                color = JaskDim, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Chip(fill: Color = Color.Black.copy(alpha = .38f), content: @Composable RowScope.() -> Unit) =
    Row(Modifier.height(28.dp).clip(RoundedCornerShape(50)).background(fill).border(1.dp, Color.White.copy(alpha = .22f), RoundedCornerShape(50))
        .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, content = content)

@Composable
private fun SmallChip(fill: Color = Color.Black.copy(alpha = .55f), content: @Composable RowScope.() -> Unit) =
    Row(Modifier.clip(RoundedCornerShape(5.dp)).background(fill).padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)

/** "Detroit Pistons at San Antonio Spurs" → "Pistons at Spurs" when both short names are known. */
internal fun heroTitle(event: SportEvent): String {
    // Shorten the schedule's own title, which keeps its order ("Detroit Pistons at San Antonio Spurs" → "Pistons at Spurs").
    var title = event.title
    event.competitors.forEach { side -> val full = side.names.firstOrNull(); val short = side.shortName
        if (full != null && short != null) title = title.replace(full, short) }
    return title.replace(" Women", "")
}

private fun leagueLabel(event: SportEvent): String =
    app.opentv.livetv.find.Sports.byKey(event.sport)?.label?.takeIf { event.sport != "cricket" } ?: event.league

/** When a game starts, in Live TV's one format (see [liveWhen]): "9:00 am", "Tomorrow 9:00 am", "Fri 9:00 am". */
internal fun whenLabel(utc: Long): String = liveWhen(utc)


/**
 * A Browse card: the genre's name over a colour taken from its own channels' logos, with three of those
 * logos fanned in the corner. Opens the genre's page.
 */
@Composable
internal fun GenreCard(label: String, count: Int, channels: List<FindChannel>, modifier: Modifier,
    /** Shown on a plate when none of the channels has a logo (24/7 shows). */
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val logos = remember(channels) { channels.mapNotNull { c -> c.logoUrl?.let { c to it } }.take(3) }
    var colours by remember(logos) { mutableStateOf<List<Color>?>(null) }
    LaunchedEffect(logos) {
        // The logos' hue as a hint over neutral glass, not a coloured card: low chroma, and blues and purples (which
        // read as an accent) almost grey.
        colours = logos.mapNotNull { (_, url) -> app.opentv.ui.artworkAccent(context, url) }
            .map { a -> app.opentv.ui.JaskColor.toColor(app.opentv.ui.JaskColor.Lch(0.33f,
                (a.c * 0.35f).coerceAtMost(if (a.h in 225f..335f) 0.018f else 0.04f), a.h)) }
            .takeIf { it.isNotEmpty() }
    }
    // Always three stops, animated, so the card eases from neutral into its channels' colours instead of snapping.
    val goal = colours.orEmpty().let { c -> if (c.isEmpty()) listOf(Color(0xFF34302C), Color(0xFF2C2925), Color(0xFF26231F))
        else List(3) { i -> c.getOrElse(i) { lerp(c.last(), Color.Black, .3f * i) } } }
    val fill = goal.mapIndexed { i, c -> androidx.compose.animation.animateColorAsState(c, androidx.compose.animation.core.tween(500), label = "genre$i").value }
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(modifier.jaskCard(source, key = "genre:$label", onClick = onClick).aspectRatio(1.45f).clip(RoundedCornerShape(14.dp))
        .background(Brush.linearGradient(fill + lerp(fill.last(), Color.Black, .4f))).jaskCardArt(source, 14.dp)) {
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = .45f))))
        // No logos: the genre's icon on a plate where the logos would sit.
        if (logos.isEmpty()) Box(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp).size(66.dp, 42.dp)
            .clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = .1f))
            .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
            Icon(icon ?: Icons.Filled.LiveTv, null, tint = Color.White.copy(alpha = .85f), modifier = Modifier.size(24.dp))
        }
        // Fanned logos, top-right, each on its own plate.
        logos.reversed().forEachIndexed { i, (channel, url) ->
            val n = logos.size - 1 - i
            // Spread like cards in a hand: the best-known in front on the right, the others stepping left and up.
            LogoPlate(url, liveName(channel.name), Modifier.align(Alignment.TopEnd).padding(top = 12.dp + (n * 10).dp, end = 10.dp + (n * 40).dp)
                .size(66.dp, 42.dp).graphicsLayerRotation(-4f + n * -5f), corner = 7.dp)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("$count channels", color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun Modifier.graphicsLayerRotation(degrees: Float) = this.then(androidx.compose.ui.Modifier.graphicsLayer { rotationZ = degrees })

/** A genre's page: a colour header, then its channels as tiles grouped by region. */
@Composable
internal fun GenrePage(label: String, channels: List<FindChannel>, regionOrder: List<String>, onBack: () -> Unit, onPlay: (FindChannel) -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val padding = app.opentv.ui.LocalJaskContentPadding.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val ranked = remember(channels) { channels.sortedWith(compareByDescending<FindChannel> { it.streamIds.size }.thenByDescending { it.logoUrl != null }.thenBy { it.name }) }
    // The page takes its colour from its best-known channel, through the shell's ambient like every other page,
    // so the header and the background are one colour rather than two.
    app.opentv.ui.JaskAmbientFrom(ranked.firstOrNull { it.identity != null && it.logoUrl != null }?.logoUrl ?: ranked.firstNotNullOfOrNull { it.logoUrl })
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = (maxWidth / 132.dp).toInt().coerceIn(3, 8)
        val byRegion = ranked.groupBy { it.region }
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
            item(key = "head") {
                Box(Modifier.fillMaxWidth()
                    .padding(top = padding.calculateTopPadding() + 8.dp, start = 16.dp, end = 16.dp, bottom = 18.dp)) {
                    Column {
                        app.opentv.ui.settings.JaskPill("‹ Live TV", onClick = onBack)
                        Spacer(Modifier.height(18.dp))
                        Text(label, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("${channels.size} channels", color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            val orderedRegions = (regionOrder.filter { it in byRegion } + byRegion.keys.filterNot { it in regionOrder }).distinct()
            orderedRegions.forEach { region ->
                if (byRegion.size > 1) item(key = "r:$region") {
                    Text(LiveClassifier.regionLabel(region), Modifier.padding(start = 16.dp, top = 16.dp, bottom = 2.dp), color = Color(0xFFF4F4F4),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                byRegion.getValue(region).chunked(columns).forEachIndexed { row, chunk ->
                    item(key = "t:$region:$row") {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            chunk.forEachIndexed { i, c ->
                                // The d-pad starts on the first channel, not the back pill above it.
                                ChannelTile(c, Modifier.weight(1f).then(if (row == 0 && i == 0 && region == orderedRegions.first())
                                    Modifier.jaskFocusEntry() else Modifier)) { onPlay(c) }
                            }
                            repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A muted live picture of [streamIds] (the first that resolves), fading in over [content] once frames arrive.
 * One preview at a time: many providers allow a single connection, so the caller sets [enabled] false
 * before starting real playback. Honours Settings → Live TV → preview video and sound.
 */
@Composable
internal fun LivePreview(streamIds: List<Long>, enabled: Boolean, modifier: Modifier, way: LiveFallback.Way? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val graph = remember(context) { app.opentv.core.ServiceLocator.get(context) }
    val allowed by graph.settings.guidePreviewVideo.collectAsState()
    val sound by graph.settings.guidePreviewSound.collectAsState()
    // Only while the app is in front: leaving to the home screen must free the provider connection.
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> resumed = owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (!enabled || !allowed || !resumed || streamIds.isEmpty()) return
    val scope = rememberCoroutineScope()
    val engine = remember(streamIds) {
        graph.playerEngines.create(scope, app.opentv.player.PlayerEngineConfig(subtitlesEnabled = false, preview = true))
    }
    DisposableEffect(engine) { onDispose { engine.stop(); engine.release() } }
    // The channel this preview is trying, so a black picture parks the right one (see NoPicture).
    val tried = remember(engine) { arrayOfNulls<Long>(1) }
    val triedChannel = remember(engine) { arrayOfNulls<app.opentv.data.model.Channel>(1) }
    // What the preview finds goes to the record too (StreamHealth): the channel's, and the way's it belongs to.
    fun learned(picture: Boolean, weight: Float = 1f) {
        val c = triedChannel[0] ?: return
        val h = app.opentv.livetv.StreamHealth
        if (picture) { h.good(c); way?.let { h.wayGood(it.sport, it.title) } }
        else { h.bad(c, weight); way?.let { h.wayBad(it.sport, it.title, weight) } }
    }
    LaunchedEffect(engine, streamIds) {
        delay(300) // only for a hero that stays on screen (it's already settled on this page)
        // The lowest-quality copy of the broadcast: a muted preview in a corner of the screen doesn't need 4K, and a
        // smaller stream reaches its first frame sooner. Same order as listed otherwise.
        val found = graph.database.channels().byIds(streamIds.take(12))
        val candidates = streamIds.filterNot(NoPicture::parked).mapNotNull { id -> found.firstOrNull { it.id == id } }
        // Known-poor copies only if nothing else is left; a known-good one first, else the lightest.
        val usable = candidates.filterNot { app.opentv.livetv.StreamHealth.of(it).poor }.ifEmpty { candidates }
        val channel = usable.firstOrNull { app.opentv.livetv.StreamHealth.of(it).trusted } ?: usable.minByOrNull { previewQuality(it.name) } ?: return@LaunchedEffect
        tried[0] = channel.id
        triedChannel[0] = channel
        val source = graph.sourceRepository.byId(channel.sourceId)
        android.util.Log.i("jaskPreview", "channel '${channel.name}' stream ${channel.streamId} (of ${streamIds.size} candidates)")
        val url = runCatching { graph.catalogRepository.resolvePlaybackUrl(channel, source) }.getOrNull() ?: return@LaunchedEffect
        // A download holds the provider's connection (one-connection accounts): no preview from it meanwhile.
        if (!app.opentv.data.repo.DownloadEngine.mayConnect(url)) return@LaunchedEffect
        engine.setVolume(if (sound) 1f else 0f)
        val agent = source?.userAgent ?: "OpenTV/0.1 (Android)"
        engine.play(app.opentv.player.PlaybackRequest(url, channel.name, agent, isLive = true), debounce = false)
        // The channel's usual stream first (measured on the emulator: HLS 1.6 s on a channel whose TS form took 26 s,
        // TS 1.8 s on another); if it isn't playing within 5 seconds, or fails, try the other form.
        val other = runCatching { graph.catalogRepository.previewUrl(channel, source) }.getOrNull()
        val started = kotlinx.coroutines.withTimeoutOrNull(5_000) {
            engine.state.first { it is app.opentv.player.PlaybackState.Error || it is app.opentv.player.PlaybackState.Playing }
        }
        if (other != null && other != url && started !is app.opentv.player.PlaybackState.Playing) {
            android.util.Log.i("jaskPreview", "usual stream ${if (started == null) "slow" else "failed"}; trying TS")
            engine.play(app.opentv.player.PlaybackRequest(other, channel.name, agent, isLive = true), debounce = false)
        }
    }
    val state by engine.state.collectAsState()
    LaunchedEffect(state) { android.util.Log.i("jaskPreview", "streams=${streamIds.take(3)} state=${state::class.simpleName}") }
    // Shown only once a frame has actually rendered, then faded in.
    var rendered by remember(engine) { mutableStateOf(false) }
    // Shown only once a frame has a picture in it. A provider's event slot with nothing on, or a dead feed, sends a
    // frame that is one flat colour (pure video black here: every pixel the same, frame after frame). A real picture,
    // however dark, always varies across the frame. Two flat frames half a second apart: the channel is parked as
    // not really live (see NoPicture) and the connection freed. Checked on a 64x36 copy of the frame.
    var bright by remember(engine) { mutableStateOf(false) }
    val texture = remember(engine) { arrayOfNulls<android.view.TextureView>(1) }
    // No frame at all within 12 seconds (a slot that's "on" but sends nothing): the same as a flat one.
    LaunchedEffect(engine) {
        // Playing counts as alive: the first-frame callback can lag (or not fire while the card is covered).
        val playing = kotlinx.coroutines.withTimeoutOrNull(12_000) { engine.state.first { it is app.opentv.player.PlaybackState.Playing } }
        if (playing == null && !rendered) {
            android.util.Log.i("jaskPreview", "no frame in 12 s; channel parked as not really live")
            engine.stop()
            tried[0]?.let(NoPicture::mark)
            learned(false, app.opentv.livetv.StreamHealth.NO_FRAME)
        }
    }
    LaunchedEffect(rendered) {
        if (!rendered) return@LaunchedEffect
        var flat = 0
        repeat(16) {
            delay(500)
            val frame = texture[0]?.takeIf { it.isAvailable }?.getBitmap(64, 36) ?: return@repeat
            val px = IntArray(64 * 36).also { frame.getPixels(it, 0, 64, 0, 0, 64, 36) }
            frame.recycle()
            val luma = DoubleArray(px.size) { i -> val c = px[i]; 0.299 * (c shr 16 and 255) + 0.587 * (c shr 8 and 255) + 0.114 * (c and 255) }
            val mean = luma.average()
            val spread = kotlin.math.sqrt(luma.sumOf { (it - mean) * (it - mean) } / luma.size)
            if (spread >= 2.0) { bright = true; tried[0]?.let(NoPicture::confirm); learned(true); return@LaunchedEffect }
            // Three seconds of flat frames, before any real picture: a fade or a dark scene is shorter than that.
            if (++flat >= 6) {
                android.util.Log.i("jaskPreview", "flat picture (mean ${mean.toInt()}, spread ${"%.1f".format(spread)}); channel parked as not really live")
                engine.stop()
                tried[0]?.let(NoPicture::mark)
                learned(false, app.opentv.livetv.StreamHealth.FLAT)
                return@LaunchedEffect
            }
        }
    }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (rendered && bright) 1f else 0f, androidx.compose.animation.core.tween(700), label = "preview")
    val exo = (engine as? app.opentv.player.PlayerController)?.player ?: return
    DisposableEffect(exo) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onRenderedFirstFrame() { rendered = true }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener); exo.clearVideoSurface() }
    }
    // A TextureView, not PlayerView's default SurfaceView: a SurfaceView sits behind the window and is hidden by
    // the hero art drawn over its area, ignores fades, and tears while the pager scrolls.
    Box(modifier.graphicsLayer { this.alpha = alpha }.clipToBounds()) {
        var videoSize by remember { mutableStateOf<androidx.media3.common.VideoSize?>(null) }
        DisposableEffect(exo) {
            val l = object : androidx.media3.common.Player.Listener {
                override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) { if (size.width > 0) videoSize = size }
            }
            exo.addListener(l); onDispose { exo.removeListener(l) }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Crop to fill: scale the video's own aspect up until it covers the box.
            val ratio = videoSize?.let { it.width * it.pixelWidthHeightRatio / it.height } ?: (16f / 9f)
            val boxRatio = maxWidth / maxHeight
            val w = if (ratio > boxRatio) maxHeight * ratio else maxWidth
            val h = if (ratio > boxRatio) maxHeight else maxWidth / ratio
            // A view per player: the preview's player changes (another channel after a dead one), and a view bound to the
            // old player left the new one drawing nowhere.
            androidx.compose.runtime.key(exo) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx -> android.view.TextureView(ctx).also { texture[0] = it; exo.setVideoTextureView(it) } },
                    modifier = Modifier.align(Alignment.Center).requiredSize(w, h),
                )
            }
        }
    }
}

/** A channel's quality from its name tags, lowest first (SD, untagged, HD, FHD, 4K, 8K), for picking a preview copy. */
private fun previewQuality(name: String): Int {
    val n = name.uppercase()
    return when {
        "8K" in n -> 6
        "4K" in n || "UHD" in n || "2160" in n -> 5
        "FHD" in n || "1080" in n -> 4
        Regex("\\bHD\\b|720").containsMatchIn(n) -> 3
        Regex("\\bSD\\b|576|480").containsMatchIn(n) -> 1
        else -> 2
    }
}
