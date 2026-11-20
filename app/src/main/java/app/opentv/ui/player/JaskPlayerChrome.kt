package app.opentv.ui.player

import app.opentv.ui.jaskWhiteFill
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.ui.JaskGlassCircle
import app.opentv.ui.JaskGlassSurface
import app.opentv.ui.jaskFocusLift
import kotlinx.coroutines.delay

/*
 * jaskTV player chrome, shared by the movie and live players: soft scrims, a glass top bar (Back,
 * title, tool buttons), large transport in the middle, and a slim scrubber at the bottom. Glass
 * here has no backdrop to blur (video is a separate surface), so it draws as tinted glass.
 */

/** Top and bottom shading so white controls read over any picture. */
@Composable
internal fun BoxScope.JaskPlayerScrims() {
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(150.dp)
        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))))
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(240.dp)
        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
}

/** Back, title (and an optional line above/below it), then tool buttons on the right. */
@Composable
internal fun JaskPlayerTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, overline: (@Composable () -> Unit)? = null,
    subtitle: String? = null, tools: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        JaskGlassCircle(Icons.AutoMirrored.Filled.ArrowBack, "Back", size = 40.dp, onClick = onBack)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            overline?.invoke()
            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf(String::isNotBlank)?.let {
                Text(it, color = Color(0xFFD0D0D0), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = tools)
    }
}

/** A round tool button for the top bar. */
@Composable
internal fun JaskPlayerTool(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) =
    JaskGlassCircle(icon, label, size = 40.dp, active = active, onClick = onClick)

/** Big middle transport: back, play/pause (white), forward. [back]/[forward] null hides them (live without timeshift). */
@Composable
internal fun JaskTransport(paused: Boolean, onPlayPause: () -> Unit, playFocus: FocusRequester, modifier: Modifier = Modifier,
    buffering: Boolean = false, backIcon: ImageVector? = null, onBack: (() -> Unit)? = null, forwardIcon: ImageVector? = null, onForward: (() -> Unit)? = null) {
    // Sized to the thumb, not to fill the picture: the film stays the main thing on screen.
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null && backIcon != null) JaskGlassCircle(backIcon, "Back 15 seconds", size = 48.dp, onClick = onBack)
        Box(Modifier.jaskFocusLift(1.12f).size(64.dp).clip(CircleShape).jaskWhiteFill(CircleShape).focusRequester(playFocus)
            .clickable(onClick = onPlayPause), contentAlignment = Alignment.Center) {
            Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (paused) "Play" else "Pause",
                tint = Color.White, modifier = Modifier.size(34.dp))
            if (buffering) androidx.compose.material3.CircularProgressIndicator(Modifier.size(64.dp), color = Color.White.copy(alpha = .6f), strokeWidth = 3.dp)
        }
        if (onForward != null && forwardIcon != null) JaskGlassCircle(forwardIcon, "Forward 15 seconds", size = 48.dp, onClick = onForward)
    }
}

/**
 * Slim scrubber with elapsed time on the left and time remaining on the right. Drag or tap to
 * seek; with a remote, Left/Right step by [stepMs] and the seek happens once the presses stop.
 * [bufferedMs] (optional) is drawn as a lighter track ahead of the playhead.
 */
@Composable
internal fun JaskScrubber(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier,
    bufferedMs: Long = 0L, stepMs: Long = 10_000L, onInteraction: () -> Unit = {}, onScrubbing: (Boolean) -> Unit = {}) {
    var width by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var focused by remember { mutableStateOf(false) }
    var keyTarget by remember { mutableStateOf<Long?>(null) }
    val duration = durationMs.coerceAtLeast(1L)
    val shown = when {
        dragging -> (dragValue * duration).toLong()
        keyTarget != null -> keyTarget!!
        else -> positionMs
    }.coerceIn(0L, duration)
    // Remote scrubbing: OK jumps at once, Back stays where you were, and a pause in presses commits.
    LaunchedEffect(keyTarget) {
        val target = keyTarget ?: return@LaunchedEffect
        delay(1_500)
        onSeek(target); keyTarget = null; onScrubbing(false)
    }
    androidx.activity.compose.BackHandler(keyTarget != null) { keyTarget = null; onScrubbing(false); onInteraction() }
    val density = LocalDensity.current
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(28.dp).onSizeChanged { width = it.width.coerceAtLeast(1) }
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || durationMs <= 0) return@onKeyEvent false
                if ((e.key == Key.DirectionCenter || e.key == Key.Enter) && keyTarget != null) {
                    onSeek(keyTarget!!); keyTarget = null; onScrubbing(false); onInteraction(); return@onKeyEvent true
                }
                // Holding an arrow goes faster: 1×, then 3×, then 6× the step.
                val repeat = e.nativeKeyEvent.repeatCount
                val speed = when { repeat > 15 -> 6; repeat > 5 -> 3; else -> 1 }
                val step = when (e.key) { Key.DirectionLeft -> -stepMs; Key.DirectionRight -> stepMs; else -> return@onKeyEvent false } * speed
                keyTarget = ((keyTarget ?: positionMs) + step).coerceIn(0L, duration)
                onScrubbing(true); onInteraction(); true
            }
            .focusable()
            .pointerInput(durationMs) {
                detectTapGestures { o -> if (durationMs > 0) { onSeek((o.x / width * duration).toLong()); onInteraction() } }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = true; dragValue = (o.x / width).coerceIn(0f, 1f); onScrubbing(true); onInteraction() },
                    onDragEnd = { dragging = false; onSeek((dragValue * duration).toLong()); onScrubbing(false); onInteraction() },
                    onDragCancel = { dragging = false; onScrubbing(false) },
                ) { change, amount -> change.consume(); dragValue = (dragValue + amount / width).coerceIn(0f, 1f) }
            }, contentAlignment = Alignment.CenterStart) {
            val active = dragging || focused || keyTarget != null
            val track = if (active) 6.dp else 4.dp
            val fraction = shown.toFloat() / duration
            Box(Modifier.fillMaxWidth().height(track).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.22f))) {
                if (bufferedMs > 0) Box(Modifier.fillMaxHeight().fillMaxWidth((bufferedMs.toFloat() / duration).coerceIn(0f, 1f))
                    .background(Color.White.copy(alpha = 0.3f)))
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Color.White))
            }
            // Focused (remote): a large ringed knob, so it reads from across the room.
            val knob = when { focused && !dragging -> 26.dp; active -> 18.dp; else -> 12.dp }
            // While scrubbing: where you'll land, and how far that is from where you are.
            if (keyTarget != null || dragging) {
                val delta = shown - positionMs
                Column(Modifier.layout { m, c ->
                    val p = m.measure(c.copy(minWidth = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity))
                    layout(0, 0) { p.place((fraction * width - p.width / 2f).toInt(), -p.height - 18.dp.roundToPx()) }
                }.clip(RoundedCornerShape(14.dp)).background(Color(0xDB141312)).padding(horizontal = 18.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    val tabular = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum")
                    Text(formatClock(shown), color = Color.White, style = tabular, fontWeight = FontWeight.Bold)
                    Text((if (delta >= 0) "+" else "−") + formatClock(kotlin.math.abs(delta)), color = Color(0xFFD6D2CC),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
                }
            }
            Box(Modifier.offset(x = with(density) { (fraction * width).toDp() } - knob / 2).size(knob)
                .then(if (focused) Modifier.border(3.dp, Color.White.copy(alpha = 0.45f), CircleShape).padding(5.dp) else Modifier)
                .clip(CircleShape).background(Color.White))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            val tabular = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
            Text(formatClock(shown), color = Color.White, style = tabular)
            Spacer(Modifier.weight(1f))
            Text(if (durationMs > 0) "-" + formatClock(durationMs - shown) else "", color = Color(0xFFD0D0D0), style = tabular)
        }
    }
}

/** A glass list of choices (subtitles, audio), the current one ticked. */
@Composable
internal fun JaskPlayerPanel(title: String, options: List<Triple<String, Boolean, () -> Unit>>, emptyText: String,
    firstFocus: FocusRequester, modifier: Modifier = Modifier) {
    JaskGlassSurface(modifier.widthIn(min = 260.dp, max = 380.dp), RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, color = Color(0xFFBDBDBD), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 6.dp))
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                if (options.isEmpty()) Text(emptyText, color = Color(0xFF9A9A9A), modifier = Modifier.padding(10.dp))
                options.forEachIndexed { i, (label, selected, onClick) ->
                    Row(Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .clip(RoundedCornerShape(12.dp)).background(if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

internal fun formatClock(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s % 60) else "%d:%02d".format(m, s % 60)
}
