package app.opentv.ui.channels

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.opentv.livetv.StreamHealth
import app.opentv.livetv.find.EventCard
import app.opentv.livetv.find.WatchOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A still from each of a game's ways to watch, so you can see which one is actually showing it before you pick:
 * a guide can list a channel that is showing something else, and an event slot can carry a promo slate. Used when
 * the choice is open (several ways live, none with a strong record in StreamHealth).
 *
 * One provider connection at a time (accounts often allow one or two): each way is opened muted, its first frame
 * kept, and closed again before the next. At most [LIMIT]. Stops as soon as the sheet closes. What each grab finds
 * (a picture, a black screen, nothing) goes to the record like any other check.
 */
internal class SheetFrames {
    val stills = mutableStateMapOf<String, ImageBitmap>()
    val failed = mutableStateMapOf<String, Boolean>()
    var grabbing by mutableStateOf<String?>(null)
    var player by mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null)
    var texture: android.view.TextureView? = null

    companion object {
        const val LIMIT = 3
        private const val KEEP = 3 * 60_000L
        /** Stills already taken, so reopening the sheet doesn't open every stream again. Key: event + way title. */
        private val cache = HashMap<String, Pair<ImageBitmap?, Long>>()
        fun cached(key: String): Pair<ImageBitmap?, Long>? = cache[key]?.takeIf { System.currentTimeMillis() - it.second < KEEP }
        fun keep(key: String, still: ImageBitmap?) { cache[key] = still to System.currentTimeMillis() }
    }
}

/** Whether a game's ways to watch are an open choice worth showing stills for. */
internal fun framesWorthShowing(card: EventCard, live: Boolean): Boolean {
    if (!live) return false
    val usable = card.options.filter { o -> o.streamIds.isNotEmpty() && !o.streamIds.all(NoPicture::parked) }
    return usable.size >= 2 && usable.none { StreamHealth.ofWay(card.event.sport, it.title).trusted }
}

@Composable
internal fun rememberSheetFrames(card: EventCard, options: List<WatchOption>): SheetFrames {
    val context = androidx.compose.ui.platform.LocalContext.current
    val graph = remember(context) { app.opentv.core.ServiceLocator.get(context) }
    val frames = remember(card.event.id) { SheetFrames() }
    val scope = rememberCoroutineScope()
    val engine = remember(card.event.id) {
        graph.playerEngines.create(scope, app.opentv.player.PlayerEngineConfig(subtitlesEnabled = false, preview = true))
    }
    DisposableEffect(engine) {
        frames.player = (engine as? app.opentv.player.PlayerController)?.player
        onDispose { engine.stop(); engine.release(); frames.player = null }
    }
    var rendered by remember { mutableStateOf(false) }
    DisposableEffect(frames.player) {
        val exo = frames.player ?: return@DisposableEffect onDispose { }
        val l = object : androidx.media3.common.Player.Listener { override fun onRenderedFirstFrame() { rendered = true } }
        exo.addListener(l); onDispose { exo.removeListener(l) }
    }
    LaunchedEffect(card.event.id) {
        engine.setVolume(0f)
        val finder = app.opentv.livetv.find.LiveFinder(context)
        val sport = card.event.sport
        for (o in options.take(SheetFrames.LIMIT)) {
            val key = card.event.id + "|" + o.title
            val known = SheetFrames.cached(key)
            if (known != null) { val still = known.first; if (still != null) frames.stills[o.title] = still else frames.failed[o.title] = true; continue }
            val ids = if (o.evidence == app.opentv.livetv.find.WatchEvidence.NAMED_BY_PROVIDER)
                runCatching { finder.stillShowing(o.streamIds, card.event) }.getOrDefault(o.streamIds) else o.streamIds
            val found = graph.database.channels().byIds(ids.take(6)).associateBy { it.id }
            val channel = rankCopies(ids.mapNotNull { found[it] }).firstOrNull { !NoPicture.parked(it.id) } ?: continue
            val source = graph.sourceRepository.byId(channel.sourceId)
            // The raw TS form where there is one: it draws its first frame sooner than HLS.
            val url = runCatching { graph.catalogRepository.previewUrl(channel, source) }.getOrNull() ?: continue
            if (!app.opentv.data.repo.DownloadEngine.mayConnect(url)) continue // a download holds the connection
            rendered = false
            frames.grabbing = o.title
            engine.play(app.opentv.player.PlaybackRequest(url, channel.name, source?.userAgent ?: "OpenTV/0.1 (Android)", isLive = true), debounce = false)
            val first = withTimeoutOrNull(9_000) { snapshotFlow { rendered }.first { it } }
            var still: ImageBitmap? = null
            if (first != null) {
                delay(900) // past the first frame, which is often a grey or black keyframe
                val bitmap = frames.texture?.takeIf { it.isAvailable }?.getBitmap(320, 180)
                if (bitmap != null && !app.opentv.ui.player.FrameCheck.flat(bitmap)) still = bitmap.asImageBitmap()
                else android.util.Log.i("jaskFrames", "'${o.title}': flat")
            } else android.util.Log.i("jaskFrames", "'${o.title}': no frame in 9 s")
            engine.stop()
            if (still != null) {
                frames.stills[o.title] = still
                NoPicture.confirm(channel.id)
                StreamHealth.good(channel); StreamHealth.wayGood(sport, o.title)
            } else {
                frames.failed[o.title] = true
                NoPicture.mark(channel.id)
                val w = if (first == null) StreamHealth.NO_FRAME else StreamHealth.FLAT
                StreamHealth.bad(channel, w); StreamHealth.wayBad(sport, o.title, w)
            }
            SheetFrames.keep(key, still)
            frames.grabbing = null
        }
    }
    return frames
}

/** A row's picture: the still once taken, the live grab while it's being taken, else a play button. */
@Composable
internal fun FrameSlot(frames: SheetFrames?, title: String, modifier: Modifier) {
    val still = frames?.stills?.get(title)
    val grabbing = frames?.grabbing == title
    val failed = frames?.failed?.get(title) == true
    if (frames == null) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        return
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
        when {
            still != null -> Image(still, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            grabbing -> {
                val exo = frames.player
                if (exo != null) androidx.compose.runtime.key(exo) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx -> android.view.TextureView(ctx).also { frames.texture = it; exo.setVideoTextureView(it) } },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { exo.clearVideoTextureView(it); if (frames.texture === it) frames.texture = null },
                    )
                }
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            }
            failed -> Text("No picture", color = Color.White.copy(alpha = .6f), style = MaterialTheme.typography.labelSmall)
            else -> Icon(Icons.Filled.PlayArrow, null, tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(20.dp))
        }
    }
}
