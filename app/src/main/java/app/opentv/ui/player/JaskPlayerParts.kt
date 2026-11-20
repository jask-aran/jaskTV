package app.opentv.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opentv.player.PlayerEngine
import app.opentv.player.PlayerStats
import app.opentv.player.PlayerTrack
import app.opentv.player.PlayerTrackType
import app.opentv.ui.jaskFocusLift
import app.opentv.ui.jaskWhiteFill
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/*
 * The player chrome (design: "jaskTV player chrome" canvas): title top-left, clock and the quality
 * badge top-right, and on TV the scrubber with one row of buttons along the bottom, so the middle of
 * the picture stays clear. Touch keeps the big centre transport. Shared by the movie and live players.
 */

internal val PlayerInk = Color(0xFFF4F1ED)
internal val PlayerMuted = Color(0xFFD6D2CC)
internal val PlayerFaint = Color(0xFFB9B4AD)
internal val PlayerLiveRed = Color(0xFFD93A3F)
private val PlayerGood = Color(0xFF7FD18B)
private val PlayerWarn = Color(0xFFF2B35B)
private val PlayerCard = Color(0xEB181716)

/** Title (with an optional line above it, e.g. LIVE) on the left; clock, "Ends at" and the quality badge on the right. */
@Composable
internal fun JaskPlayerHeader(
    title: String, modifier: Modifier = Modifier, subtitle: String? = null, endsAtMillis: Long? = null,
    quality: StreamReading? = null, infoOpen: Boolean = false, onQuality: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null, overline: (@Composable () -> Unit)? = null, compact: Boolean = false,
    reserveEndsAt: Boolean = false, trailing: @Composable RowScope.() -> Unit = {},
) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (isActive) { delay(15_000); now = System.currentTimeMillis() } }
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (leading != null) { leading(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            overline?.invoke()
            Text(title, color = PlayerInk, fontSize = if (compact) 17.sp else 28.sp, fontWeight = FontWeight.Bold,
                maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf(String::isNotBlank)?.let {
                Text(it, color = PlayerMuted, fontSize = if (compact) 13.sp else 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(16.dp))
        if (compact) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (quality != null && onQuality != null) JaskQualityBadge(quality, infoOpen, onQuality, small = true)
            trailing()
        } else Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(timeFormat.format(java.util.Date(now)), color = PlayerInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            // [reserveEndsAt]: the line keeps its height while the length is unknown (buffering), so the pills below don't jump.
            if (endsAtMillis != null || reserveEndsAt)
                Text(endsAtMillis?.let { "Ends at " + timeFormat.format(java.util.Date(it)) }.orEmpty(), color = PlayerMuted, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                trailing()
                if (quality != null && onQuality != null) JaskQualityBadge(quality, infoOpen, onQuality)
            }
        }
    }
}

/**
 * "1080p  [Stream info]": the resolution as plain text once it is known, then a pill whose label never changes
 * (the live bitrate is in the card it opens). One quality label only: no separate HD/4K chip.
 */
@Composable
internal fun JaskQualityBadge(reading: StreamReading, active: Boolean, onClick: () -> Unit, small: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        resolutionLabel(reading.stats)?.let {
            Text(it, color = PlayerMuted, fontSize = if (small) 12.sp else 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        val inside: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            Icon(Icons.Filled.Info, null, tint = PlayerInk, modifier = Modifier.size(if (small) 18.dp else 16.dp))
            Text("Stream info", color = PlayerInk, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        // Touch: plain like the header's other labelled controls (Version); TV: the glass of the player's pills.
        if (small) Row(Modifier.height(44.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = if (active) 0.18f else 0f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp), content = inside)
        else app.opentv.ui.JaskGlassSurface(Modifier.jaskFocusLift(1.06f).height(36.dp), RoundedCornerShape(50), showFocus = true) {
            Row(Modifier.fillMaxHeight().background(Color.White.copy(alpha = if (active) 0.18f else 0f)).clickable(onClick = onClick)
                .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = inside)
        }
    }
}

/**
 * A labelled glass pill for the bottom row (Audio & subtitles, Channels…). [plain] drops the glass (phone). [header]: the
 * TV header's smaller size (36dp, as the Stream info badge beside it).
 */
@Composable
internal fun JaskPlayerPill(icon: ImageVector, label: String, onClick: () -> Unit, active: Boolean = false, plain: Boolean = false,
    leadingDot: Color? = null, trailingText: String? = null, header: Boolean = false) {
    val inside: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (leadingDot != null) Box(Modifier.size(10.dp).clip(CircleShape).background(leadingDot))
        else Icon(icon, null, tint = PlayerInk, modifier = Modifier.size(when { plain -> 20.dp; header -> 16.dp; else -> 22.dp }))
        Text(label, color = PlayerInk, fontSize = if (plain || header) 13.sp else 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        trailingText?.let { Text(it, color = PlayerMuted, fontSize = 15.sp) }
    }
    if (plain) Row(Modifier.jaskFocusLift(1.06f).height(44.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick)
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = inside)
    // The same glass pill as the circles beside it (and the media page's Sources button); brighter while active.
    else app.opentv.ui.JaskGlassSurface(Modifier.jaskFocusLift(1.06f).height(if (header) 36.dp else 48.dp), RoundedCornerShape(50), showFocus = true) {
        Row(Modifier.fillMaxHeight().background(Color.White.copy(alpha = if (active) 0.18f else 0f)).clickable(onClick = onClick)
            .padding(horizontal = if (header) 14.dp else 18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (header) 6.dp else 10.dp), content = inside)
    }
}

/**
 * Hosts in-window popovers ([app.opentv.ui.JaskMenu]: display size, orientation, Play on) for the player chrome.
 * The player routes sit outside any [app.opentv.ui.JaskSurfaceHost], so without this those menus fell back to
 * Material's DropdownMenu. Only the overlay, not the glass host: there is no page behind the video to blur, so the
 * glass keeps its tinted look.
 */
@Composable
internal fun JaskPlayerOverlayHost(content: @Composable BoxScope.() -> Unit) {
    val overlay = remember { app.opentv.ui.JaskOverlay() }
    androidx.compose.runtime.CompositionLocalProvider(app.opentv.ui.LocalJaskOverlay provides overlay) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { overlay.origin = it.positionInRoot() }) {
            content()
            overlay.layers.forEach { (key, layer) -> androidx.compose.runtime.key(key) { layer() } }
        }
    }
}

/** Size of every round button in the player's bottom row, so seek, cast, display and record match. */
internal val PlayerCircleSize = 48.dp

/** A round glass button for the bottom row (±seek, cast, record): the same glass circle as the display controls. */
@Composable
internal fun JaskPlayerCircle(icon: ImageVector, label: String, onClick: () -> Unit) =
    app.opentv.ui.JaskGlassCircle(icon, label, size = PlayerCircleSize, onClick = onClick)

/** TV: the white Play/Pause pill that leads the bottom row; dimmed until focused, labelled when it is. */
@Composable
internal fun JaskPlayPausePill(paused: Boolean, buffering: Boolean, focus: FocusRequester, onClick: () -> Unit) {
    Row(Modifier.jaskFocusLift(1.08f).focusRequester(focus).height(48.dp).clip(RoundedCornerShape(50))
        .jaskWhiteFill(RoundedCornerShape(50)).clickable(onClick = onClick).padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (buffering) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.5.dp)
        else Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Text(if (paused) "Play" else "Pause", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The small Audio & subtitles menu: two short lists, the current choice ticked. Choosing applies and closes. */
@Composable
internal fun JaskTracksMenu(engine: PlayerEngine, tracks: List<PlayerTrack>, firstFocus: FocusRequester, modifier: Modifier = Modifier,
    onSubtitlesChosen: (Boolean) -> Unit = {}, onDone: () -> Unit) {
    val audio = tracks.filter { it.type == PlayerTrackType.AUDIO && it.isSupported }
    val text = tracks.filter { it.type == PlayerTrackType.TEXT && it.isSupported }
    var first = true
    @Composable fun row(label: String, selected: Boolean, onClick: () -> Unit) {
        val isFirst = first; first = false
        Row(Modifier.fillMaxWidth().jaskFocusLift(1.03f).then(if (isFirst) Modifier.focusRequester(firstFocus) else Modifier)
            .height(40.dp).clip(RoundedCornerShape(10.dp)).clickable { onClick(); onDone() }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(26.dp)) { if (selected) Icon(Icons.Filled.Check, null, tint = PlayerInk, modifier = Modifier.size(16.dp)) }
            Text(label, color = if (selected) PlayerInk else PlayerMuted, fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    @Composable fun heading(t: String) = Text(t, color = PlayerFaint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp))
    Column(modifier.width(260.dp).clip(RoundedCornerShape(18.dp)).background(PlayerCard).padding(10.dp)
        .heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        heading("AUDIO")
        if (audio.isEmpty()) row("Default", true) {}
        audio.forEachIndexed { i, t -> row(trackLabel(t, i), t.isSelected) { engine.selectTrack(t) } }
        Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
        heading("SUBTITLES")
        row("Off", text.none { it.isSelected }) { engine.disableText(); onSubtitlesChosen(false) }
        text.forEachIndexed { i, t -> row(trackLabel(t, i), t.isSelected) { engine.selectTrack(t); onSubtitlesChosen(true) } }
    }
}

/** Stream info: live bitrate with the last minute drawn, then what is playing and how healthily. Not focusable; the badge toggles it. */
@Composable
internal fun JaskStreamInfoCard(reading: StreamReading, buffering: Boolean, modifier: Modifier = Modifier,
    behindLiveMillis: Long? = null, source: String? = null) {
    val s = reading.stats
    val (health, healthColor) = when {
        buffering -> "Buffering" to PlayerWarn
        reading.recentDrops > 5 -> "Dropping frames" to PlayerWarn
        s.bufferAheadMillis in 1..1_999 -> "Low buffer" to PlayerWarn
        else -> "Smooth" to PlayerGood
    }
    // Wide and short, so it fits between the header and the bottom controls on a 540dp-tall TV screen.
    Column(modifier.width(600.dp).clip(RoundedCornerShape(18.dp)).background(PlayerCard).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Stream info", color = PlayerInk, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(Modifier.size(8.dp).clip(CircleShape).background(healthColor))
            Spacer(Modifier.width(6.dp))
            Text(health, color = healthColor, fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Bitrate", color = PlayerFaint, fontSize = 13.sp)
                Text(reading.bitrateLabel() ?: "Measuring…", color = PlayerInk, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                val history = reading.history
                Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                    if (history.size < 2) return@Canvas
                    val max = (history.maxOrNull() ?: 1f).coerceAtLeast(1f) * 1.15f
                    val step = size.width / (history.size - 1)
                    val line = Path()
                    history.forEachIndexed { i, v ->
                        val p = Offset(i * step, size.height - v / max * size.height)
                        if (i == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y)
                    }
                    val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
                    drawPath(fill, PlayerInk.copy(alpha = 0.12f))
                    drawPath(line, PlayerInk, style = Stroke(width = 2.dp.toPx()))
                }
                Text(if (history.isEmpty()) "last minute" else "last minute · avg ${mbps(history.average().toFloat())} · peak ${mbps(history.max())}",
                    color = PlayerFaint, fontSize = 12.sp)
            }
            val rows = buildList {
                resolutionLabel(s)?.let { add("Resolution" to (if (s.width > 0) "${s.width}×${s.height}" else it) + (if (s.frameRate > 0f) " · ${s.frameRate.toInt()} fps" else "")) }
                codecName(s.videoCodec)?.let { add("Video" to it) }
                codecName(s.audioCodec)?.let { add("Audio" to listOfNotNull(it, channelsLabel(s.audioChannels), s.audioBitrate.takeIf { b -> b > 0 }?.let { b -> "${b / 1000} kb/s" }).joinToString(" · ")) }
                add("Buffer" to "%.1f s ahead".format(s.bufferAheadMillis / 1000f))
                if (reading.downloadBitsPerSecond > 0f) add("Download" to mbps(reading.downloadBitsPerSecond) + " Mb/s")
                if (s.renderedFrames > 0) add("Dropped frames" to "%,d of %,d".format(s.droppedFrames, s.renderedFrames + s.droppedFrames))
                behindLiveMillis?.takeIf { it > 1_000 }?.let { add("Behind live" to formatClock(it)) }
                source?.takeIf(String::isNotBlank)?.let { add("Source" to it) }
                s.format?.let { add("Format" to it + if (s.variantCount > 1) " · ${s.variantCount} qualities" else "") }
            }
            Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                rows.forEach { (k, v) ->
                    Row {
                        Text(k, color = PlayerFaint, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(v, color = PlayerInk, fontSize = 13.sp, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * One reading of the stream, refreshed each second. [bitsPerSecond] is the stream's own bitrate: bits received per
 * second of video received, so it stays right while the player has a full buffer and stops downloading.
 * [downloadBitsPerSecond] is how fast the network is delivering right now. [history] keeps a minute of bitrate.
 */
internal class StreamReading(val stats: PlayerStats, val bitsPerSecond: Float, val downloadBitsPerSecond: Float,
    val history: List<Float>, val recentDrops: Int) {
    fun bitrateLabel(): String? = bitsPerSecond.takeIf { it > 0f }?.let { mbps(it) + " Mb/s" }
}

/** Polls [engine] once a second. */
@Composable
internal fun rememberStreamReading(engine: PlayerEngine): State<StreamReading> {
    val state = remember { mutableStateOf(StreamReading(engine.stats(), 0f, 0f, emptyList(), 0)) }
    LaunchedEffect(engine) {
        val media = ArrayDeque<Pair<Long, Long>>()     // (buffered media ms, bytes) while loading moves forward
        val wall = ArrayDeque<Pair<Long, Long>>()      // (wall ms, bytes)
        val drops = ArrayDeque<Int>()
        val history = ArrayDeque<Float>()
        var rate = 0f
        while (isActive) {
            val s = engine.stats()
            val t = System.currentTimeMillis()
            wall.addLast(t to s.bytesLoaded); while (wall.size > 6) wall.removeFirst()
            drops.addLast(s.droppedFrames); while (drops.size > 11) drops.removeFirst()
            val last = media.lastOrNull()
            // A seek or a new stream jumps the loaded position: start the window again.
            if (last != null && (s.bufferedPositionMillis < last.first || s.bufferedPositionMillis - last.first > 120_000 || s.bytesLoaded < last.second)) media.clear()
            if (media.isEmpty() || s.bufferedPositionMillis > media.last().first) media.addLast(s.bufferedPositionMillis to s.bytesLoaded)
            while (media.size > 12) media.removeFirst()
            if (media.size >= 2) {
                val (m0, b0) = media.first(); val (m1, b1) = media.last()
                if (m1 - m0 >= 1_000) rate = (b1 - b0).coerceAtLeast(0) * 8_000f / (m1 - m0)
            }
            if (rate > 0f) { history.addLast(rate); while (history.size > 60) history.removeFirst() }
            val (t0, w0) = wall.first()
            val download = if (t > t0) (s.bytesLoaded - w0).coerceAtLeast(0) * 8_000f / (t - t0) else 0f
            state.value = StreamReading(s, rate, download, history.toList(), drops.last() - drops.first())
            delay(1_000)
        }
    }
    return state
}

private fun mbps(bitsPerSecond: Float): String = "%.1f".format(bitsPerSecond / 1_000_000f)

/** Named by the larger of height and width: a 1920×800 widescreen film is 1080p, not "800p". */
private fun resolutionLines(s: PlayerStats): Int = maxOf(s.height, s.width * 9 / 16)

private fun resolutionLabel(s: PlayerStats): String? {
    val lines = resolutionLines(s)
    val name = when {
        lines <= 0 -> return null
        lines >= 2000 -> "4K"
        lines >= 1000 -> "1080p"
        lines >= 700 -> "720p"
        lines >= 560 -> "576p"
        else -> "${s.height}p"
    }
    return name + if (s.frameRate >= 48f) s.frameRate.toInt().toString() else ""
}

private fun channelsLabel(n: Int): String? = when (n) { 0 -> null; 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$n ch" }

internal fun codecName(codec: String?): String? {
    val c = codec?.lowercase() ?: return null
    return when {
        "avc" in c -> "H.264"
        "hev" in c || "hvc" in c -> "HEVC"
        "av01" in c || "/av1" in c -> "AV1"
        "vp09" in c || "vp9" in c -> "VP9"
        "mp4a" in c || "aac" in c -> "AAC"
        "ec-3" in c || "eac3" in c -> "Dolby Digital Plus"
        "ac-3" in c || "ac3" in c -> "Dolby Digital"
        "opus" in c -> "Opus"
        "mpeg" in c || "mp2" in c -> "MPEG"
        else -> codec
    }
}

private fun trackLabel(t: PlayerTrack, index: Int): String {
    if (!t.label.isNullOrBlank()) return t.label!!
    val lang = t.language
    if (!lang.isNullOrBlank() && lang != "und") return runCatching { java.util.Locale(lang).displayLanguage.ifBlank { lang } }.getOrDefault(lang)
    return "Track ${index + 1}"
}
