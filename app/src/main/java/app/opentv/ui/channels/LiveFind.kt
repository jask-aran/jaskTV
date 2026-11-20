package app.opentv.ui.channels

import app.opentv.ui.jaskFocusLift
import androidx.compose.foundation.layout.aspectRatio
import app.opentv.ui.jaskCardArt
import app.opentv.ui.jaskCard
import app.opentv.ui.jaskFocusRing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.livetv.find.FindChannel
import app.opentv.livetv.find.LiveClassifier
import app.opentv.livetv.find.LiveFindIndex
import app.opentv.livetv.find.LiveFinder
import app.opentv.livetv.find.LiveGenre
import app.opentv.livetv.find.Sports
import app.opentv.ui.JaskSheet
import app.opentv.ui.settings.JaskDim
import app.opentv.ui.settings.JaskInk
import app.opentv.ui.settings.JaskPill
import kotlinx.coroutines.launch

/**
 * Live TV's Find page: channels in the profile's regions by genre, followed sports, and 24/7 loops
 * kept out of the way. [query] is the header search, filtering in place.
 */
@Composable
internal fun LiveFind(query: String, onPlay: (Channel) -> Unit, sheetRequest: Int,
    myTv: List<FindChannel> = emptyList(), onGuide: (() -> Unit)? = null, sports: Boolean = false) {
    val context = LocalContext.current
    val graph = remember(context) { ServiceLocator.get(context) }
    val finder = remember(context) { LiveFinder(context) }
    val finderRefresh = finder
    val settings = graph.settings
    val regions by settings.liveRegions.collectAsState()
    val hidden by settings.hiddenCategories.collectAsState()
    val unlocked by settings.hiddenUnlocked.collectAsState()
    // Show the last index straight away; the check below only replaces it when the channels changed.
    var index by remember { mutableStateOf(finder.lastIndex()) }
    // A fresh launch: show the index saved last time at once; the check below replaces it only if channels changed.
    LaunchedEffect(Unit) { if (index == null) finder.storedIndex()?.let { if (index == null) index = it } }
    var sheet by remember { mutableStateOf(false) }
    LaunchedEffect(sheetRequest) { if (sheetRequest > 0) sheet = true }
    LaunchedEffect(regions) { if (regions == null) sheet = true }
    LaunchedEffect(hidden, unlocked) {
        val keys = if (unlocked) emptySet() else hidden
        finder.index(keys).let { fresh -> if (fresh !== index) index = fresh }
        // First use without the IPTV-org catalogue: show provider genres now, refine once it arrives.
        if (!finder.identitiesReady && finder.prepareIdentities()) index = finder.index(keys)
    }
    val scope = rememberCoroutineScope()
    // A channel is often several copies (regions, qualities): the first copy sending video, else the first copy.
    // A channel is often several copies (regions, qualities). Best first by their record (StreamHealth): a copy that
    // keeps working plays at once; otherwise the first copy sending video (a quick check), else the first copy.
    fun play(channel: FindChannel) = scope.launch {
        val streams = graph.database.channels().byIds(channel.streamIds.take(20)).associateBy { it.id }
        val copies = rankCopies(channel.streamIds.mapNotNull { streams[it] })
        val first = copies.firstOrNull() ?: return@launch
        val trusted = first.takeIf { !NoPicture.parked(it.id) && app.opentv.livetv.StreamHealth.of(it).trusted }
        val working = trusted ?: copies.filterNot { NoPicture.parked(it.id) }.take(3).firstOrNull { c ->
            (NoPicture.seen(c.id) || StreamProbe.sending(graph, c)).also { ok ->
                if (ok) NoPicture.confirm(c.id) else { NoPicture.mark(c.id); app.opentv.livetv.StreamHealth.bad(c, app.opentv.livetv.StreamHealth.SILENT) }
            }
        }
        val picked = working ?: first
        android.util.Log.i("jaskPick", "'${channel.name}': ${if (trusted != null) "instant" else "checked"} → ${app.opentv.livetv.StreamHealth.of(picked)}")
        LiveFallback.set(picked.id, copies.map { it.id }.filter { it != picked.id }, channel.name)
        onPlay(picked)
    }

    val followed by settings.followedSports.collectAsState()
    val schedule = remember(context) { app.opentv.livetv.find.SportsSchedule(context) }
    val nowOn = remember(context) { app.opentv.livetv.find.NowOn(context) }
    // Start from what was on screen last time, so reopening Live TV doesn't rebuild the page in front of you.
    var cards by remember { mutableStateOf(LastHome.cards) }
    var sportChannels by remember { mutableStateOf(LastHome.sportChannels) }
    // A fresh launch: the sports cards saved last time, until the check below has run.
    LaunchedEffect(followed, regions) {
        if (cards != null || followed.isEmpty()) return@LaunchedEffect
        val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.opentv.livetv.find.LiveSnapshot.loadSports(context, followed, regions.orEmpty(), System.currentTimeMillis())
        } ?: return@LaunchedEffect
        // Saved rows keep only channels that name their sport (and slots): the rest needs the guide check below.
        if (cards == null) { cards = saved.first.groupBy { it.event.sport }
            sportChannels = saved.second.mapValues { (sport, list) -> list.filter { it.key.startsWith("slot:") || namesSport(sport, it) } } }
    }
    var savedCards by remember { mutableStateOf<List<app.opentv.livetv.find.EventCard>?>(null) }
    var slotCategories by remember { mutableStateOf<Set<Pair<Long, String>>>(emptySet()) }
    // Live / starts-at states depend on the clock: rebuild cards every minute while Find is open.
    var minute by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); minute = System.currentTimeMillis() / 60_000 } }
    LaunchedEffect(index, followed, regions, minute) {
        val built = index ?: return@LaunchedEffect
        if (followed.isEmpty()) { cards = emptyMap(); LastHome.cards = cards; return@LaunchedEffect }
        // Cards stay on screen while they are recomputed; the schedule is disk-cached, so the minute tick is cheap.
        val events = schedule.events(followed)
        // The provider's guide: games on general sports channels that no channel or slot name gives away.
        val airings = app.opentv.livetv.find.SportsGuide.load(context, built)
        val finder = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { app.opentv.livetv.find.SportsFinder(built, airings) }
        val built2 = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { finder.cards(events, regions.orEmpty()) }
        // Each sport's row: what's showing it now first (guide, then unlisted event slots), then its own channels.
        sportChannels = followed.associateWith { sport ->
            val dedicated = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { finder.sportChannels(sport, regions.orEmpty()) }
            val onAir = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { finder.onNow(sport, regions.orEmpty(), built2) }
            // A channel found through the guide stays only if what its tile shows (its trusted guide, see NowOn) is this
            // sport too: a same-named guide from another country can list a game the channel isn't showing.
            // Same for a channel matched only by its category: only a channel whose own name names the sport
            // ("beIN SP⚽RTS NBA", "Sky Sport F1") is listed whatever it is showing.
            val words = app.opentv.livetv.find.SportsGuide.WORDS[sport]
            val all = (onAir + dedicated).distinctBy { it.key }
            val own = all.filter { namesSport(sport, it) }.map { it.key }.toSet()
            val check = all.filter { !it.key.startsWith("slot:") && it.key !in own }
            val shown: Map<String, app.opentv.data.model.Programme> = if (words == null || check.isEmpty()) emptyMap() else runCatching { nowOn.lookup(check) }.getOrDefault(emptyMap())
            all.filter { c -> c.key.startsWith("slot:") || c.key in own || words == null ||
                shown[c.key]?.title?.let { words.containsMatchIn(it) } == true }
        }
        cards = built2.groupBy { it.event.sport }.also { LastHome.cards = it }
        LastHome.sportChannels = sportChannels
        if (built2 != savedCards) {
            savedCards = built2
            val channelsNow = sportChannels
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.opentv.livetv.find.LiveSnapshot.saveSports(context, followed, regions.orEmpty(), built2, channelsNow, System.currentTimeMillis())
            }
        }
        slotCategories = finder.slotCategories(built2)
    }
    // Event slots are renamed as events end and new ones are scheduled: while this page is open, take the renames
    // every 3 minutes (from core, or the provider when no core answers) and rebuild only if a name changed.
    LaunchedEffect(Unit) {
        while (true) {
            if (finderRefresh.keepSlotsFresh(slotCategories)) index = finderRefresh.index(if (unlocked) emptySet() else hidden)
            kotlinx.coroutines.delay(3 * 60_000)
        }
    }
    fun playIds(ids: List<Long>) = scope.launch {
        val streams = graph.database.channels().byIds(ids.take(20)).associateBy { it.id }
        ids.firstNotNullOfOrNull { streams[it] }?.let(onPlay)
    }
    // A way to watch a game. The chosen way first, then the game's others: an event slot counts only if its name,
    // re-read now, still names the game, and any channel only if it is sending video (StreamProbe). A provider's
    // event slot is often on air with nothing in it, and playing it buffered forever.
    fun playOption(option: app.opentv.livetv.find.WatchOption) = scope.launch {
        val health = app.opentv.livetv.StreamHealth
        val card = cards.orEmpty().values.flatten().firstOrNull { option in it.options }
        if (card == null) { playIds(option.streamIds); return@launch }
        val sport = card.event.sport
        // Backing out of another way to this game within seconds, to pick this one: that way showed the wrong thing
        // (a guide listing that was something else). Automatic switches don't count: they go through the player.
        if (LiveFallback.event == card.event.id && System.currentTimeMillis() - LiveFallback.startedAt < 25_000) {
            LiveFallback.wayOf(graph.settings.lastChannelId)
                ?.takeIf { it.title != option.title }?.let { health.wayBad(it.sport, it.title, health.LEFT) }
        }
        val tries = listOf(option) + card.rankedOptions().filter { it != option && it.state == option.state }
        val wayOfId = tries.flatMap { o -> o.streamIds.map { it to LiveFallback.Way(sport, o.title) } }.toMap()
        fun go(channel: app.opentv.data.model.Channel, o: app.opentv.livetv.find.WatchOption, how: String) {
            android.util.Log.i("jaskPick", "${card.event.title}: '${o.title}' $how → ${health.of(channel)} way ${health.ofWay(sport, o.title)}")
            LiveFallback.set(channel.id, tries.flatMap { it.streamIds.take(2) }.filter { it != channel.id && !NoPicture.parked(it) },
                card.event.title, card.event.id, wayOfId)
            onPlay(channel)
        }
        var probes = 0
        var told = false
        var firstFound: Pair<app.opentv.data.model.Channel, app.opentv.livetv.find.WatchOption>? = null
        for (o in tries) {
            val ids = if (o.evidence == app.opentv.livetv.find.WatchEvidence.NAMED_BY_PROVIDER) finderRefresh.stillShowing(o.streamIds, card.event) else o.streamIds
            val found = graph.database.channels().byIds(ids.take(6)).associateBy { it.id }
            val channels = rankCopies(ids.mapNotNull { found[it] })
            if (firstFound == null) channels.firstOrNull()?.let { firstFound = it to o }
            for (channel in channels.filterNot { NoPicture.parked(it.id) }.take(2)) {
                // Worked again and again lately: play it now (the player still checks the picture and moves on if black).
                if (health.of(channel).trusted) { go(channel, o, "instant"); index = finderRefresh.index(if (unlocked) emptySet() else hidden); return@launch }
                if (probes++ >= 6) break
                // Seen playing a moment ago (the hero preview may still be holding it, and a second connection to the
                // same channel is often refused): trust it rather than test it.
                if (NoPicture.seen(channel.id) || StreamProbe.sending(graph, channel)) {
                    NoPicture.confirm(channel.id)
                    if (o != option) android.widget.Toast.makeText(context, "No picture on ${option.title}: playing ${o.title}", android.widget.Toast.LENGTH_LONG).show()
                    go(channel, o, "checked")
                    index = finderRefresh.index(if (unlocked) emptySet() else hidden)
                    return@launch
                }
                NoPicture.mark(channel.id)
                health.bad(channel, health.SILENT); health.wayBad(sport, o.title, health.SILENT)
                if (!told) { told = true; android.widget.Toast.makeText(context, "No picture on ${o.title}: looking for another…", android.widget.Toast.LENGTH_SHORT).show() }
            }
        }
        // Nothing answered in time (a slow provider looks the same as a dead slot): play the first match anyway.
        val fallback = firstFound
        if (fallback != null) go(fallback.first, fallback.second, "unchecked")
        else android.widget.Toast.makeText(context, "Nothing is showing ${card.event.title} right now", android.widget.Toast.LENGTH_LONG).show()
        // The provider renamed slots: rebuild the cards from the fresh names.
        index = finderRefresh.index(if (unlocked) emptySet() else hidden)
    }

    // Debug builds, started with `--ez jaskAudit true`: try every way to watch each live game and log which send video.
    LaunchedEffect(cards) {
        val all = cards ?: return@LaunchedEffect
        if (!StreamProbe.auditPending) return@LaunchedEffect
        val now = System.currentTimeMillis()
        for (card in all.values.flatten()) {
            val live = app.opentv.livetv.find.EventMatcher.isLive(card.event, now)
            val liveOptions = card.options.filter { it.state == app.opentv.livetv.find.WatchState.LIVE }
            if (!live && liveOptions.isEmpty()) continue
            android.util.Log.i("jaskAudit", "EVENT '${card.event.title}' [${card.event.sport}] scheduleLive=$live options=${card.options.size} showing=${NoPicture.showing(card)}")
            for (o in card.options) {
                val found = graph.database.channels().byIds(o.streamIds.take(3)).associateBy { it.id }
                val results = o.streamIds.take(3).mapNotNull { found[it] }.map { ch -> "'${ch.name.take(70)}'=${if (StreamProbe.sending(graph, ch)) "VIDEO" else "none"}" }
                android.util.Log.i("jaskAudit", "  ${o.state} ${o.evidence} '${o.title}' (${o.streamIds.size} ch): ${results.joinToString()}")
            }
        }
        StreamProbe.auditPending = false
        android.util.Log.i("jaskAudit", "DONE")
    }

    val current = index
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    LiveFindList(current, regions.orEmpty(), query, followed, cards, sportChannels, open, { open = it }, { sheet = true },
        onPlayChannel = { play(it) }, onPlayOption = { playOption(it) }, myTv = myTv, onGuide = onGuide, sports = sports)
    if (sheet && current != null) LiveFindSheet(current, regions, onDismiss = { sheet = false }) { picked ->
        settings.setLiveRegions(picked)
        scope.launch { if (graph.epgRepository.enableGuidesForRegions(picked)) app.opentv.data.work.SyncWorker.refreshNow(context) }
    }
}

/** Whether a channel's own name names [sport] ("NBA TV", "beIN SP⚽RTS NBA", "Sky Sport F1"), by the sport's channel words. */
private fun namesSport(sport: String, channel: FindChannel): Boolean {
    val words = app.opentv.livetv.find.EventMatcher.SPORT_CHANNELS[sport] ?: return false
    return words.containsMatchIn(channel.name) || words.containsMatchIn(liveName(channel.name, channel.key.startsWith("slot:")))
}

@Composable
internal fun FindNote(text: String) =
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = JaskDim, style = MaterialTheme.typography.bodyMedium)

@Composable
internal fun SectionTitle(text: String) =
    Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp), color = JaskInk,
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

@Composable
internal fun GroupRow(label: String, count: Int?, expanded: Boolean, note: String? = null, onClick: () -> Unit) {
    Row(Modifier.jaskFocusLift(1.02f).fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = JaskInk, style = MaterialTheme.typography.titleSmall)
            note?.let { Text(it, color = JaskDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        count?.let { Text("$it", Modifier.padding(start = 12.dp), color = JaskDim, style = MaterialTheme.typography.bodyMedium) }
        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = JaskDim, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
internal fun FindRow(channel: FindChannel, onPlay: (FindChannel) -> Unit, addable: Boolean = true, showRegion: Boolean = false) {
    val context = LocalContext.current
    var best by remember(channel.key) { mutableStateOf<Channel?>(null) }
    Row(Modifier.jaskFocusLift(1.02f).fillMaxWidth().clickable { onPlay(channel) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
            channel.logoUrl?.let { coil.compose.AsyncImage(it, null, Modifier.fillMaxSize().padding(6.dp)) }
                ?: Text(initials(liveName(channel.name)), color = JaskDim, style = MaterialTheme.typography.labelMedium)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(liveName(channel.name, channel.key.startsWith("slot:")), color = JaskInk, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            val detail = listOfNotNull(LiveClassifier.regionLabel(channel.region).takeIf { showRegion }, channel.service,
                channel.streamIds.size.takeIf { it > 1 }?.let { "$it versions" }).joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, color = JaskDim, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        if (addable) {
            LaunchedEffect(channel.key) {
                best = ServiceLocator.get(context).database.channels().byIds(listOf(channel.streamIds.first())).firstOrNull()
            }
            best?.let { AddToMyTvButton(it, compact = true) }
        }
        IconButton(onClick = { onPlay(channel) }, modifier = Modifier.jaskFocusRing()) { Icon(Icons.Filled.PlayArrow, "Play ${channel.name}", tint = JaskInk) }
    }
}

/** Regions (what Browse shows) and followed sports, per profile. Opens on first visit. */
@Composable
internal fun LiveFindSheet(index: LiveFindIndex, regions: Set<String>?, onDismiss: () -> Unit, onRegions: (Set<String>) -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) { ServiceLocator.get(context).settings }
    val followed by settings.followedSports.collectAsState()
    val previews by settings.guidePreviewVideo.collectAsState()
    val previewSound by settings.guidePreviewSound.collectAsState()
    val deviceRegion = remember { LiveClassifier.normaliseRegion(java.util.Locale.getDefault().country.uppercase()) }
    var picked by remember { mutableStateOf(regions ?: setOfNotNull(deviceRegion.takeIf { it in index.regionCounts })) }
    var showAll by remember { mutableStateOf(false) }
    val ordered = remember(index) { index.regionCounts.entries.sortedByDescending { it.value }.map { it.key } }
    val shown = if (showAll) ordered else ordered.filter { it in picked || it in COMMON }.ifEmpty { ordered.take(8) }
    JaskSheet(onDismiss = { onRegions(picked); onDismiss() }) {
        LiveFindSheetContent(shown, index.regionCounts, picked, { code -> picked = if (code in picked) picked - code else picked + code },
            moreRegions = (ordered.size - shown.size).takeIf { !showAll && it > 0 }, onMoreRegions = { showAll = true },
            followed = followed, onSport = { key -> settings.setFollowedSports(if (key in followed) followed - key else followed + key) },
            onDone = { onRegions(picked); onDismiss() },
            previews = previews, onPreviews = { settings.setGuidePreviewVideo(!previews) },
            previewSound = previewSound, onPreviewSound = { settings.setGuidePreviewSound(!previewSound) })
    }
}

/** The sheet's content, pure UI (also drawn by the render test). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LiveFindSheetContent(
    shown: List<String>, counts: Map<String, Int>, picked: Set<String>, onRegion: (String) -> Unit,
    moreRegions: Int?, onMoreRegions: () -> Unit, followed: Set<String>, onSport: (String) -> Unit, onDone: () -> Unit,
    previews: Boolean = true, onPreviews: () -> Unit = {}, previewSound: Boolean = false, onPreviewSound: () -> Unit = {},
) {
    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Live TV", Modifier.weight(1f), color = JaskInk, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                JaskPill("Done", primary = true, onClick = onDone)
            }
        }
        item { SectionTitle("Sports you follow") }
        item { FindNote("See where each game is on, from any region.") }
        item {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Sports.all.forEach { sport -> app.opentv.ui.settings.JaskChoicePill(sport.label, selected = sport.key in followed, check = true) { onSport(sport.key) } }
            }
        }
        item { SectionTitle("Regions") }
        item { FindNote("Browse shows channels from these regions.") }
        items(shown, key = { "r:$it" }) { code ->
            CheckRow(LiveClassifier.regionLabel(code), "${counts[code] ?: 0} channels", code in picked) { onRegion(code) }
        }
        moreRegions?.let { more -> item {
            TextButton(onClick = onMoreRegions, Modifier.jaskFocusRing().then(Modifier.padding(horizontal = 8.dp))) { Text("$more more regions", color = JaskInk) }
        } }
        item { SectionTitle("Previews") }
        item { CheckRow("Live previews", "Play live games in the banner. Uses data and one provider connection.", previews, onPreviews) }
        if (previews) item { CheckRow("Preview sound", null, previewSound, onPreviewSound) }
    }
}

private val COMMON = setOf("AU", "US", "GB", "IN", "CA", "NZ", "IE")

@Composable
internal fun CheckRow(label: String, note: String?, checked: Boolean, onClick: () -> Unit) {
    Row(Modifier.jaskFocusLift(1.02f).fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = JaskInk, style = MaterialTheme.typography.bodyLarge)
            note?.let { Text(it, color = JaskDim, style = MaterialTheme.typography.bodySmall) }
        }
        if (checked) Icon(Icons.Filled.Check, "Selected", tint = JaskInk)
    }
}

@Composable
internal fun EventCardRow(card: app.opentv.livetv.find.EventCard, regions: Set<String>, sportLabel: String,
    onPlay: (app.opentv.livetv.find.WatchOption) -> Unit) {
    val event = card.event
    val onAir = app.opentv.livetv.find.EventMatcher.isLive(event, System.currentTimeMillis())
    // ESPN's league names are long ("National Basketball Association"): show only short, informative ones.
    val league = event.league.takeIf { it != sportLabel && (it.length <= 24 || event.sport == "cricket") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp))
        .background(Color.White.copy(alpha = if (onAir) .1f else .06f)).padding(vertical = 10.dp)) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onAir) {
                Text("LIVE", Modifier.clip(RoundedCornerShape(6.dp)).background(LiveRed).padding(horizontal = 6.dp, vertical = 1.dp),
                    color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
            }
            val time = when {
                event.finished -> "Ended · " + liveWhen(event.startUtc)
                onAir -> "Started " + liveWhen(event.startUtc)
                else -> liveWhen(event.startUtc)
            }
            Text(listOfNotNull(time, league).joinToString(" · "), color = JaskDim,
                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(event.title, Modifier.padding(horizontal = 14.dp, vertical = 3.dp), color = JaskInk,
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        card.rankedOptions().forEach { option ->
            Row(Modifier.jaskFocusLift(1.02f).fillMaxWidth().clickable { onPlay(option) }.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp), tint = JaskInk)
                Text(liveName(option.title), Modifier.weight(1f).padding(start = 8.dp), color = JaskInk, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Upcoming needs no label: the card's header already says when.
                val state = if (option.streamIds.isNotEmpty() && option.streamIds.all(NoPicture::parked)) "No picture right now" else when (option.state) {
                    app.opentv.livetv.find.WatchState.LIVE -> "Live"
                    app.opentv.livetv.find.WatchState.REPLAY -> "Replay"
                    app.opentv.livetv.find.WatchState.UPCOMING -> if (app.opentv.livetv.find.EventMatcher.isLive(event, System.currentTimeMillis())) "Live" else null
                }
                Text(listOfNotNull(state, LiveClassifier.regionLabel(option.region).takeIf { option.region !in regions }, option.evidence.label.takeUnless { it.equals(option.title, ignoreCase = true) }).joinToString(" · "),
                    Modifier.padding(start = 8.dp), color = JaskDim, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

private val LiveRed = Color(0xFFE5484D)

/**
 * Live TV's home, pure UI: everything it shows is passed in, so the app and the render test draw the same thing.
 * [open] is the one expanded group ("g:NEWS", "sc:f1", "mg:nba", "loops").
 */
@Composable
internal fun LiveFindList(
    index: LiveFindIndex?, chosen: Set<String>, query: String, followed: Set<String>,
    cards: Map<String, List<app.opentv.livetv.find.EventCard>>?, sportChannels: Map<String, List<FindChannel>>,
    open: String?, onOpen: (String?) -> Unit, onSheet: () -> Unit,
    onPlayChannel: (FindChannel) -> Unit, onPlayOption: (app.opentv.livetv.find.WatchOption) -> Unit,
    channelActions: Boolean = true, myTv: List<FindChannel> = emptyList(), onGuide: (() -> Unit)? = null,
    /** The Sports tab: games from the schedules first (see LiveHero), then where they're on. Otherwise Live TV: My TV and Browse. */
    sports: Boolean = false,
    /** Tests open a Browse page through the real path. */
    initialPage: String? = null,
    /** Tests open the timeline on a sport. */
    initialTimeline: TimelineView = TimelineView(),
) {
    val needle = query.trim()
    val inRegions = remember(index, chosen) { index?.channels.orEmpty().filter { it.region in chosen } }
    val loops = remember(index, chosen) { index?.loops.orEmpty().filter { it.region in chosen } }
    val byGenre = remember(inRegions) { inRegions.groupBy { it.genre }.toSortedMap(compareBy { it.ordinal }) }
    fun toggle(id: String) = onOpen(if (open == id) null else id)
    // Each region once: a duplicate here becomes a duplicate list key, which crashes the genre page.
    val regionOrder = remember(chosen) { (listOf("AU", "US", "GB", "IN").filter { it in chosen } + chosen.sorted()).distinct() }
    var watching by remember { mutableStateOf<app.opentv.livetv.find.EventCard?>(null) }
    val now = System.currentTimeMillis()
    val featured = remember(cards, followed) {
        cards.orEmpty().filterKeys { it in followed }.values.flatten().filter { !it.event.finished && it.options.isNotEmpty() &&
            // Live or still to come: a session past its end isn't flagged finished for hours, and showed its start time.
            (app.opentv.livetv.find.EventMatcher.isLive(it.event, now) || it.event.startUtc > now) }
            .sortedWith(compareByDescending<app.opentv.livetv.find.EventCard> { app.opentv.livetv.find.EventMatcher.isLive(it.event, now) }.thenBy { it.event.startUtc })
            .distinctBy { it.event.sport to (it.event.startUtc / 3_600_000) }.take(5)
    }
    val padding = app.opentv.ui.LocalJaskContentPadding.current
    var timeline by remember { mutableStateOf(initialTimeline) }
    // The hero's live preview holds a provider connection: release it before real playback starts.
    var previewing by remember { mutableStateOf(channelActions) }
    val previewScope = rememberCoroutineScope()
    val playOption: (app.opentv.livetv.find.WatchOption) -> Unit = { option ->
        previewing = false
        previewScope.launch { kotlinx.coroutines.delay(350); onPlayOption(option) }
    }
    val playChannel: (FindChannel) -> Unit = { channel ->
        previewing = false
        previewScope.launch { kotlinx.coroutines.delay(350); onPlayChannel(channel) }
    }
    // An opened Browse card: its genre page replaces the home until Back.
    var page by rememberSaveable { mutableStateOf(initialPage) }
    // Kept above the category/sport pages, so coming Back finds the list where it was (and the d-pad its card).
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    app.opentv.ui.JaskPageTopEffect(listState) { listState.animateScrollToItem(0) }
    // TV: "See all" / "Show less" rebuilds its row, so the tile that had focus goes away and focus fell to the top bar.
    // Focus moves on to [focusAfter] instead (a cell id, "sc:f1:<channel key>" or "sc:f1:more"), after scrolling to
    // [scrollAfter] (the section's first row, when collapsing from far down the grid).
    val moved = remember { androidx.compose.ui.focus.FocusRequester() }
    var focusAfter by remember { mutableStateOf<String?>(null) }
    var scrollAfter by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(focusAfter) {
        if (focusAfter == null) return@LaunchedEffect
        for (attempt in 0 until 3) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { moved.requestFocus() }.isSuccess) break
            // Not on screen (focus fell away and the list moved): bring the section's first row back, then try again.
            scrollAfter?.let { runCatching { listState.scrollToItem((it - 1).coerceAtLeast(0)) } }
        }
        focusAfter = null; scrollAfter = null
    }
    val onPage: (String) -> Unit = { page = it }
    val pageList = when (val p = page) {
        null -> null
        "loops" -> "24/7 shows" to loops
        else -> LiveGenre.entries.firstOrNull { it.name == p }?.let { it.label to byGenre[it].orEmpty() }
    }
    // A sport's page (from its tile in What's on): its week as a calendar, then a day's timeline.
    val sportPage = page?.removePrefix("sport:")?.takeIf { page?.startsWith("sport:") == true }?.let(app.opentv.livetv.find.Sports::byKey)
    if (sportPage != null && needle.length < 2) {
        SportPage(sportPage, cards.orEmpty()[sportPage.key].orEmpty(), now, onBack = { page = null }, backLabel = if (sports) "Sports" else "Live TV", onOpen = { watching = it }, onPlay = playOption)
        watching?.let { card -> WatchSheet(card, chosen, { watching = null }, playOption) }
        return
    }
    if (pageList != null && needle.length < 2) {
        GenrePage(pageList.first, pageList.second, regionOrder, onBack = { page = null }, onPlay = onPlayChannel)
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val columns = (maxWidth / 132.dp).toInt().coerceIn(3, 8)
    val tv = app.opentv.ui.LocalLayoutClass.current == app.opentv.ui.LayoutClass.TV
    val gameColumns = (maxWidth / 240.dp).toInt().coerceIn(2, 5)
    /** Channel tiles in rows; [more] ends the last row with a "See all"/"Show less" tile (TV: in place of a title link). */
    fun androidx.compose.foundation.lazy.LazyListScope.tiles(id: String, list: List<FindChannel>, showRegion: Boolean = false,
        more: Pair<String, () -> Unit>? = null) {
        val cells: List<FindChannel?> = if (more != null) list + null else list
        cells.chunked(columns).forEachIndexed { row, chunk ->
            item(key = "$id:r$row") {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    chunk.forEach { c ->
                        val cell = if (c != null) "$id:${c.key}" else "$id:more"
                        val target = if (cell == focusAfter) Modifier.focusRequester(moved) else Modifier
                        if (c != null) ChannelTile(c, Modifier.weight(1f).then(target), showRegion) { playChannel(c) }
                        else MoreTile(more!!.first, "$id:more", Modifier.weight(1f).then(target), more.second)
                    }
                    repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    fun androidx.compose.foundation.lazy.LazyListScope.games(id: String, list: List<app.opentv.livetv.find.EventCard>) =
        list.chunked(gameColumns).forEachIndexed { row, chunk ->
            item(key = "$id:r$row") {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    chunk.forEach { EventTile(it, Modifier.weight(1f)) { watching = it } }
                    repeat(gameColumns - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
        // The hero runs under the header, as on Movies; without one, leave the header's room.
        if (needle.length >= 2 || featured.isEmpty() || !sports) item(key = "top") { Spacer(Modifier.height(padding.calculateTopPadding())) }
        when {
            index == null -> item { FindNote("Sorting your channels…") }
            index.channels.isEmpty() -> item { FindNote("No live channels yet. Add a provider in Settings.") }
            chosen.isEmpty() -> item {
                Column(Modifier.padding(16.dp)) {
                    FindNote("Choose the regions you watch to see their channels.")
                    JaskPill("Choose regions", primary = true, onClick = onSheet)
                }
            }
            needle.length >= 2 -> {
                // Games first (followed sports), then channels: names starting with the words, identified, then the rest.
                val games = cards?.values.orEmpty().flatten().filter { !it.event.finished && it.options.isNotEmpty() &&
                    (it.event.title.contains(needle, true) || it.event.league.contains(needle, true)) }.take(10)
                val hits = inRegions.filter { it.name.contains(needle, true) }
                    .sortedWith(compareByDescending<FindChannel> { it.name.startsWith(needle, true) }
                        .thenByDescending { it.identity != null }.thenBy { it.name.length }).take(200)
                val loopHits = loops.filter { it.name.contains(needle, true) }.take(50)
                if (games.isEmpty() && hits.isEmpty() && loopHits.isEmpty()) item { FindNote("Nothing named “$needle” in your regions or followed sports.") }
                if (games.isNotEmpty()) {
                    item(key = "qg") { SectionTitle("Games") }
                    items(games, key = { "qg:" + it.event.id }) { card ->
                        EventCardRow(card, chosen, app.opentv.livetv.find.Sports.byKey(card.event.sport)?.label.orEmpty(), onPlayOption)
                    }
                }
                if (hits.isNotEmpty()) {
                    if (games.isNotEmpty()) item(key = "qcs") { SectionTitle("Channels") }
                    items(hits, key = { "qc:" + it.key }) { FindRow(it, onPlayChannel, addable = channelActions, showRegion = true) }
                }
                if (loopHits.isNotEmpty()) {
                    item(key = "qls") { SectionTitle("24/7 shows") }
                    items(loopHits, key = { "ql:" + it.key }) { FindRow(it, onPlayChannel, addable = false, showRegion = true) }
                }
            }
            else -> {
                if (sports && featured.isNotEmpty()) item(key = "hero") {
                    LiveHero(featured, previewEnabled = previewing && watching == null,
                        // Watch: the first way to watch that hasn't shown a blank picture (see NoPicture).
                        onWatch = { card -> card.leadOption()?.let(playOption) },
                        onOpen = { watching = it })
                }
                if (!sports && (myTv.isNotEmpty() || onGuide != null)) {
                    item(key = "mytv") { HomeTitle("My TV", if (onGuide != null) "Guide" else null) { onGuide?.invoke() } }
                    if (myTv.isEmpty()) item(key = "mytv0") { FindNote("Channels you add appear here, with their guide.") }
                    tiles("mytv", myTv)
                }
                if (!sports) Unit
                else if (followed.isEmpty()) item(key = "follow") {
                    HomeTitle("Sports", "Choose", onSheet)
                    FindNote("Follow NBA, F1, cricket and more to see every game and where it's on.")
                } else if (cards == null) item(key = "sports-wait") { FindNote("Checking sports schedules…") }
                else {
                    // What's on when, across every followed sport.
                    item(key = "timeline-title") { HomeTitle("What's on", "Choose sports", onSheet) }
                    sportsTimeline(cards, followed, now, timeline, { timeline = it }, { watching = it }, wide = maxWidth >= 720.dp, onSport = { page = "sport:$it" })
                    app.opentv.livetv.find.Sports.all.filter { it.key in followed }.forEach { sport ->
                        val dedicated = sportChannels[sport.key].orEmpty()
                        if (dedicated.isNotEmpty()) {
                            val sid = "sc:" + sport.key
                            val expandable = dedicated.size > columns
                            // Their tiles show what's on now from the guide: a game the schedules don't list still shows up here.
                            item(key = sid) { SubTitle("${sport.label} on TV", if (expandable && !tv) (if (open == sid) "Less" else "See all") else null) { toggle(sid) } }
                            // TV: "See all" is the row's last tile, so the d-pad meets it at the end of the row, not between rows.
                            if (tv && expandable) tiles(sid, if (open == sid) dedicated else dedicated.take(columns - 1), showRegion = true,
                                more = (if (open == sid) "Show less" else "See all ${dedicated.size}") to {
                                    // The section's first row stays where it is in the list; scroll back to it if focus left.
                                    val shown = listState.layoutInfo.visibleItemsInfo.firstOrNull { (it.key as? String)?.startsWith("$sid:r") == true }
                                    scrollAfter = shown?.let { it.index - ((it.key as String).removePrefix("$sid:r").toIntOrNull() ?: 0) }
                                    // Collapsing: back to "See all" at the end of the first row; expanding: the first tile it reveals.
                                    focusAfter = if (open == sid) "$sid:more" else "$sid:" + dedicated[columns - 1].key
                                    toggle(sid)
                                })
                            else tiles(sid, if (open == sid) dedicated else dedicated.take(columns), showRegion = true)
                        }
                    }
                }
                // Browse (Live TV only): a card per genre (and 24/7 shows), each opening its own page.
                if (!sports) {
                val genres = byGenre.entries.map { (genre, list) -> Triple(genre.name, genre.label, list) } +
                    listOfNotNull(loops.takeIf { it.isNotEmpty() }?.let { Triple("loops", "24/7 shows", it) })
                item(key = "browse") { HomeTitle("Browse", null) {} }
                genres.chunked(gameColumns).forEachIndexed { row, chunk ->
                    item(key = "gc:$row") {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            chunk.forEach { (id, label, list) ->
                                // Three different brands for the fan (not three Sky Sports feeds).
                                val showcase = list.sortedWith(compareByDescending<FindChannel> { it.identity != null && it.logoUrl != null }
                                    .thenByDescending { it.streamIds.size }).distinctBy { it.name.substringBefore(' ').lowercase() }
                                GenreCard(label, list.size, showcase, Modifier.weight(1f), icon = if (id == "loops") Icons.Filled.Repeat else null) { onPage(id) }
                            }
                            repeat(gameColumns - chunk.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                }
            }
        }
    }
    }
    watching?.let { card -> WatchSheet(card, chosen, { watching = null }, playOption) }
}

/** Section title in the Movies home's style, with an optional action on the right ("See all"). */
@Composable
private fun HomeTitle(text: String, action: String?, onAction: () -> Unit) {
    // TV: the action sits right after the title, on the left where every row starts, so Down walks the rows'
    // first items instead of being pulled to a link at the far right edge.
    val tv = app.opentv.ui.LocalLayoutClass.current == app.opentv.ui.LayoutClass.TV
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 22.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, if (tv) Modifier.padding(end = 12.dp) else Modifier.weight(1f), color = Color(0xFFF4F4F4), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        action?.let { TextButton(onClick = onAction, modifier = Modifier.jaskFocusRing()) { Text(it, color = JaskDim, style = MaterialTheme.typography.labelLarge) } }
    }
}

@Composable
private fun SubTitle(text: String, action: String?, onAction: () -> Unit) {
    val tv = app.opentv.ui.LocalLayoutClass.current == app.opentv.ui.LayoutClass.TV
    // Touch: the same height with or without "See all" (a 48dp button), so every "… on TV" starts the same distance down.
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp).then(if (tv) Modifier else Modifier.heightIn(min = 48.dp)),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, if (tv) Modifier.padding(end = 12.dp) else Modifier.weight(1f), color = JaskDim, style = MaterialTheme.typography.labelLarge)
        action?.let { TextButton(onClick = onAction, modifier = Modifier.jaskFocusRing()) { Text(it, color = JaskDim, style = MaterialTheme.typography.labelMedium) } }
    }
}

/** A few recognisable names for a group's note: identified channels first. */
private fun preview(list: List<FindChannel>): String =
    (list.filter { it.identity != null } + list).distinctBy { it.name.substringBefore(" · ") }.take(4)
        .joinToString(", ") { it.name.substringBefore(" · ") } + if (list.size > 4) "…" else ""

@Composable
private fun RegionLabel(text: String) =
    Text(text.uppercase(), Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp), color = JaskDim,
        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)

private fun initials(name: String): String =
    name.split(' ', '-', '·').filter { w -> w.firstOrNull()?.isLetterOrDigit() == true }.take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { "TV" }

/** The Live TV home's last sports content, kept for the process so a revisit opens as it was left. */
private object LastHome {
    @Volatile var cards: Map<String, List<app.opentv.livetv.find.EventCard>>? = null
    @Volatile var sportChannels: Map<String, List<FindChannel>> = emptyMap()
}

/** A "See all" / "Show less" tile, shaped like a channel tile, ending a row on TV. */
@Composable
private fun MoreTile(label: String, key: String, modifier: Modifier, onClick: () -> Unit) {
    val source = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(modifier.jaskCard(source, key = key, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = .08f)).jaskCardArt(source), contentAlignment = Alignment.Center) {
            Text(label, color = Color(0xFFF4F4F4), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}
