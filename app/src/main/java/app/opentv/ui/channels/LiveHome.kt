package app.opentv.ui.channels

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
internal fun MatchupArt(event: SportEvent, modifier: Modifier, logoSize: Dp, content: @Composable BoxScope.() -> Unit = {}) {
    val (a, b) = event.colours()
    // A smooth blend between the two sides (a hard seam read as a rendering fault).
    Box(modifier.background(Brush.linearGradient(0f to a, 0.3f to a, 0.7f to b, 1f to b))) {
        // A soft vignette so white type and logos hold on bright brand colours.
        Box(Modifier.matchParentSize().background(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .45f)))))
        val sides = event.competitors.take(2)
        if (sides.size == 2 && sides.any { it.logo != null }) {
            Row(Modifier.align(Alignment.Center).padding(bottom = logoSize * 0.35f), verticalAlignment = Alignment.CenterVertically,
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
            AsyncImage(event.leagueLogo, event.league, Modifier.align(Alignment.Center).padding(bottom = logoSize * 0.35f).size(logoSize * 1.4f),
                contentScale = ContentScale.Fit)
        } else Text(event.league, Modifier.align(Alignment.Center).padding(bottom = logoSize * 0.35f), color = Color.White,
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        content()
    }
}

/** Swipeable hero of followed games, like the Movies home's featured hero. Advances every 10 seconds. */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LiveHero(cards: List<EventCard>, previewEnabled: Boolean, onWatch: (EventCard) -> Unit, onOpen: (EventCard) -> Unit) {
    val pager = rememberPagerState { cards.size }
    val settled = cards.getOrNull(pager.settledPage)
    app.opentv.ui.JaskAmbientFrom(settled?.event?.let { it.competitors.firstNotNullOfOrNull { c -> c.logo } ?: it.leagueLogo })
    LaunchedEffect(pager.settledPage, cards.size) {
        delay(10_000)
        if (!pager.isScrollInProgress && cards.size > 1)
            pager.animateScrollToPage((pager.settledPage + 1) % cards.size, animationSpec = androidx.compose.animation.core.tween(900))
    }
    val wash = app.opentv.ui.LocalJaskAmbient.current?.wash ?: Canvas
    val tail = with(androidx.compose.ui.platform.LocalDensity.current) { 200.dp.toPx() }
    // As on the Movies hero: below its edge the hero's colour fades into the page over [tail], under the rows.
    // A phone's hero is nearly square; on a landscape screen it leaves room for What's on below the fold's edge.
    val heroSize = if (androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp })
        Modifier.fillMaxWidth().height((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.72f).dp)
        else Modifier.fillMaxWidth().aspectRatio(0.92f)
    Box(heroSize.drawBehind {
        drawRect(Brush.verticalGradient(listOf(wash, wash.copy(alpha = 0f)), startY = size.height - 1f, endY = size.height + tail),
            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 1f), size = androidx.compose.ui.geometry.Size(size.width, tail))
    }) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { cards[it].event.id }) { page ->
            val card = cards[page]
            val event = card.event
            val live = EventMatcher.isLive(event, System.currentTimeMillis())
            MatchupArt(event, Modifier.fillMaxSize().clickable { onOpen(card) }, logoSize = 112.dp) {
                // Live games show the actual broadcast, muted, once it is settled on this page.
                if (live && previewEnabled && page == pager.settledPage) LivePreview((card.options.firstOrNull { it.state == WatchState.LIVE } ?: card.options.first()).streamIds,
                    enabled = true, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .5f), 0.22f to Color.Transparent,
                    0.55f to Color.Transparent, 0.82f to wash.copy(alpha = .8f), 1f to wash)))
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(heroTitle(event), color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (live) Chip(LiveRed.copy(alpha = .9f)) { Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                        else Chip { Text(when { event.finished -> "Ended"; else -> whenLabel(event.startUtc) }, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                        Chip { Text(leagueLabel(event), color = Color(0xFFEDEDED), style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                        card.options.firstOrNull()?.let { Chip { Text(it.title, color = Color(0xFFEDEDED), style = MaterialTheme.typography.labelMedium, maxLines = 1) } }
                        if (card.options.size > 1) Chip { Text("+${card.options.size - 1} more", color = Color(0xFFD6D6D6), style = MaterialTheme.typography.labelMedium) }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.height(46.dp).clip(RoundedCornerShape(50)).background(Color.White).clickable { onWatch(card) }.padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (live) "Watch live" else "Watch", color = Color.Black, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (cards.size > 1) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(cards.size) { i ->
                Box(Modifier.size(if (i == pager.currentPage) 7.dp else 5.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = .4f)))
            }
        }
    }
}

/** 16:9 game card in the home's wide-card style; tap for where to watch. */
@Composable
internal fun EventTile(card: EventCard, modifier: Modifier, onClick: () -> Unit) {
    val event = card.event
    val live = EventMatcher.isLive(event, System.currentTimeMillis())
    Column(modifier.clickable(onClick = onClick)) {
        MatchupArt(event, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)), logoSize = 46.dp) {
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = .75f))))
            Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (live) SmallChip(LiveRed) { Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                else if (event.finished) SmallChip { Text("Replay", color = Color.White, style = MaterialTheme.typography.labelSmall) }
            }
            Text(card.options.firstOrNull()?.title.orEmpty() + if (card.options.size > 1) "  +${card.options.size - 1}" else "",
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
    Column(modifier.clickable(onClick = onClick)) {
        val art = programme?.iconUrl?.takeIf(String::isNotBlank)
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp))) {
            if (art != null) {
                AsyncImage(art, null, Modifier.fillMaxSize().background(Color(0xFF26231F)), contentScale = ContentScale.Crop)
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = .7f))))
                LogoPlate(channel.logoUrl, channel.name.substringBefore(" · "), Modifier.align(Alignment.TopStart).padding(6.dp).size(46.dp, 29.dp), corner = 6.dp)
            } else LogoPlate(channel.logoUrl, channel.name.substringBefore(" · "), Modifier.matchParentSize())
            programme?.let { p ->
                val now = System.currentTimeMillis()
                val done = ((now - p.startUtcMillis).toFloat() / (p.endUtcMillis - p.startUtcMillis).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = .35f))) {
                    Box(Modifier.fillMaxWidth(done).fillMaxHeight().background(Color.White.copy(alpha = .9f)))
                }
            }
        }
        Text(channel.name, Modifier.padding(top = 5.dp), color = Color(0xFFF6F3EF), style = MaterialTheme.typography.labelLarge,
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
        MatchupArt(card.event, Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(2.2f).clip(RoundedCornerShape(14.dp)), logoSize = 56.dp)
        Text(heroTitle(card.event), Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), color = Color.White,
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(listOf(if (EventMatcher.isLive(card.event, System.currentTimeMillis())) "Live now" else whenLabel(card.event.startUtc), leagueLabel(card.event)).joinToString(" · "),
            Modifier.padding(horizontal = 20.dp), color = JaskDim, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        card.options.forEach { option -> WatchRow(option, regions) { onDismiss(); onPlay(option) } }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun WatchRow(option: WatchOption, regions: Set<String>, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(if (option.state == WatchState.LIVE) LiveRed else Color.White.copy(alpha = .12f)),
            contentAlignment = Alignment.Center) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(option.title, color = JaskInk, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val state = when (option.state) { WatchState.LIVE -> "Live"; WatchState.REPLAY -> "Replay"; WatchState.UPCOMING -> null }
            Text(listOfNotNull(state, LiveClassifier.regionLabel(option.region).takeIf { option.region !in regions }, option.evidence.label).joinToString(" · "),
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

internal fun whenLabel(utc: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val at = java.time.Instant.ofEpochMilli(utc).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val day = when (at.toLocalDate()) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> at.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))
    }
    return day + " " + at.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
}


/**
 * A Browse card: the genre's name over a colour taken from its own channels' logos, with three of those
 * logos fanned in the corner. Opens the genre's page.
 */
@Composable
internal fun GenreCard(label: String, count: Int, channels: List<FindChannel>, modifier: Modifier, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val logos = remember(channels) { channels.mapNotNull { c -> c.logoUrl?.let { c to it } }.take(3) }
    var colours by remember(logos) { mutableStateOf<List<Color>?>(null) }
    LaunchedEffect(logos) {
        colours = logos.mapNotNull { (_, url) -> app.opentv.ui.artworkAccent(context, url) }
            .map { a -> app.opentv.ui.JaskColor.toColor(app.opentv.ui.JaskColor.Lch(0.36f, (a.c * 0.8f).coerceAtMost(0.14f), a.h)) }
            .takeIf { it.isNotEmpty() }
    }
    // Always three stops, animated, so the card eases from neutral into its channels' colours instead of snapping.
    val goal = colours.orEmpty().let { c -> if (c.isEmpty()) listOf(Color(0xFF34302C), Color(0xFF2C2925), Color(0xFF26231F))
        else List(3) { i -> c.getOrElse(i) { lerp(c.last(), Color.Black, .3f * i) } } }
    val fill = goal.mapIndexed { i, c -> androidx.compose.animation.animateColorAsState(c, androidx.compose.animation.core.tween(500), label = "genre$i").value }
    Box(modifier.aspectRatio(1.45f).clip(RoundedCornerShape(14.dp))
        .background(Brush.linearGradient(fill + lerp(fill.last(), Color.Black, .4f)))
        .clickable(onClick = onClick)) {
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = .45f))))
        // Fanned logos, top-right, each on its own plate.
        logos.reversed().forEachIndexed { i, (channel, url) ->
            val n = logos.size - 1 - i
            // Spread like cards in a hand: the best-known in front on the right, the others stepping left and up.
            LogoPlate(url, channel.name, Modifier.align(Alignment.TopEnd).padding(top = 12.dp + (n * 10).dp, end = 10.dp + (n * 40).dp)
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
            (regionOrder.filter { it in byRegion } + byRegion.keys.filterNot { it in regionOrder }).distinct().forEach { region ->
                if (byRegion.size > 1) item(key = "r:$region") {
                    Text(LiveClassifier.regionLabel(region), Modifier.padding(start = 16.dp, top = 16.dp, bottom = 2.dp), color = Color(0xFFF4F4F4),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                byRegion.getValue(region).chunked(columns).forEachIndexed { row, chunk ->
                    item(key = "t:$region:$row") {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            chunk.forEach { ChannelTile(it, Modifier.weight(1f)) { onPlay(it) } }
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
internal fun LivePreview(streamIds: List<Long>, enabled: Boolean, modifier: Modifier) {
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
    LaunchedEffect(engine, streamIds) {
        delay(1_200) // only for a hero that stays on screen
        val channel = graph.database.channels().byIds(streamIds.take(3)).let { found -> streamIds.firstNotNullOfOrNull { id -> found.firstOrNull { it.id == id } } }
            ?: return@LaunchedEffect
        val source = graph.sourceRepository.byId(channel.sourceId)
        val url = runCatching { graph.catalogRepository.resolvePlaybackUrl(channel, source) }.getOrNull() ?: return@LaunchedEffect
        engine.setVolume(if (sound) 1f else 0f)
        engine.play(app.opentv.player.PlaybackRequest(url, channel.name, source?.userAgent ?: "OpenTV/0.1 (Android)", isLive = true), debounce = false)
    }
    val state by engine.state.collectAsState()
    LaunchedEffect(state) { android.util.Log.i("jaskPreview", "streams=${streamIds.take(3)} state=${state::class.simpleName}") }
    // Shown only once a frame has actually rendered, then faded in.
    var rendered by remember(engine) { mutableStateOf(false) }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (rendered) 1f else 0f, androidx.compose.animation.core.tween(700), label = "preview")
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
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx -> android.view.TextureView(ctx).also { exo.setVideoTextureView(it) } },
                modifier = Modifier.align(Alignment.Center).requiredSize(w, h),
            )
        }
    }
}
