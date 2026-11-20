package app.opentv.ui.player

import android.app.Activity
import android.os.Build
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.opentv.core.AppSettings
import app.opentv.core.DisplayModeInfo
import app.opentv.core.findActivity
import app.opentv.core.get
import app.opentv.core.pickDisplayMode
import app.opentv.core.snapFrameRate
import app.opentv.player.PlayerEngine
import app.opentv.ui.LayoutClass
import app.opentv.ui.LocalLayoutClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import app.opentv.player.PlaybackState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

/**
 * Frame-rate matching (TV only, Settings → Playback → Match frame rate): asks the display for a refresh rate that
 * is a whole multiple of the video's frame rate, and gives the choice back to the system when the player closes.
 * The rate comes from the stream when it declares one, else from the frames' own timestamps
 * ([app.opentv.player.FrameClock], ~2 s; live MPEG-TS rarely declares it).
 *
 * Switching modes blanks the TV for a second or two. So for a film or episode ([rewind]) the start plays muted
 * behind "Matching frame rate" until the rate is known; if the display has to change it pauses, switches, waits
 * for the TV, rewinds to where it started and only then plays with sound. Live TV plays at once and shows the
 * notice only while a switch actually happens. [key] identifies the stream (a new channel or title starts over).
 */
@Composable
internal fun JaskFrameRateMatch(engine: PlayerEngine, key: Any?, rewind: Boolean) {
    if (LocalLayoutClass.current != LayoutClass.TV) return
    val context = LocalContext.current
    val on by remember(context) { AppSettings.get(context).matchFrameRate }.collectAsState()
    val activity = remember(context) { context.findActivity() } ?: return
    DisposableEffect(activity, on) { onDispose { preferMode(activity, 0) } }
    if (!on) return
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(engine, activity, key) {
        val volume = engine.volume
        if (rewind) { notice = "Matching frame rate…"; engine.setVolume(0f) }
        try {
            // Wait for this stream's first frames (the previous stream's numbers linger for a moment), then its rate.
            val since = System.currentTimeMillis()
            var startedAt = -1L
            val fps = withTimeoutOrNull(12_000) {
                while (true) {
                    delay(100)
                    if (engine.state.value !is PlaybackState.Playing || System.currentTimeMillis() - since < 600) continue
                    if (startedAt < 0) startedAt = engine.positionMillis
                    val stats = engine.stats()
                    val f = stats.frameRate.takeIf { it > 0f } ?: stats.measuredFrameRate.takeIf { it > 0f }
                    if (f != null) return@withTimeoutOrNull snapFrameRate(f)
                    // Nothing declared or measurable after 4 s of playing: leave the display alone.
                    if (System.currentTimeMillis() - since > 4_000 + 600) return@withTimeoutOrNull null
                }
                null
            }
            val target = fps?.let { pickFor(activity, it) }
            if (fps != null && target != null && target != display(activity)?.mode?.modeId) {
                notice = "Matching frame rate · ${formatFps(fps)} fps"
                engine.setPlaying(false)
                preferMode(activity, target)
                withTimeoutOrNull(3_000) { while (display(activity)?.mode?.modeId != target) delay(100) }
                // The TV re-syncs its HDMI input after the switch; anything played before then is lost.
                delay(1_500)
                if (rewind && startedAt >= 0 && engine.isSeekable) engine.seekTo(startedAt)
                engine.setPlaying(true)
            } else if (rewind && startedAt >= 0 && engine.isSeekable) {
                engine.seekTo(startedAt) // heard nothing so far: start again with sound
            }
        } finally {
            engine.setVolume(volume)
            notice = null
        }
    }
    notice?.let { MatchingNotice(it) }
}

private fun formatFps(fps: Float): String =
    if (kotlin.math.abs(fps - fps.toInt()) < 0.01f) fps.toInt().toString() else String.format(java.util.Locale.US, "%.3f", fps)

/** Covers the video (a window above it, so above the video surface) while the rate is being matched. */
@Composable
private fun MatchingNotice(text: String) {
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    androidx.compose.ui.window.Popup(properties = androidx.compose.ui.window.PopupProperties(focusable = false, clippingEnabled = false)) {
        Box(Modifier.size(conf.screenWidthDp.dp, conf.screenHeightDp.dp).background(Color(0xF2000000)), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Color(0xFFF0F0F0), strokeWidth = 2.5.dp)
                Text(text, color = Color(0xFFF0F0F0), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun display(activity: Activity): Display? =
    if (Build.VERSION.SDK_INT >= 30) runCatching { activity.display }.getOrNull()
    else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay

private fun pickFor(activity: Activity, fps: Float): Int? {
    val d = display(activity) ?: return null
    val info = { m: Display.Mode -> DisplayModeInfo(m.modeId, m.physicalWidth, m.physicalHeight, m.refreshRate) }
    return pickDisplayMode(d.supportedModes.map(info), info(d.mode), fps)?.id
}

/** 0 hands the choice back to the system (the user's own output setting). */
private fun preferMode(activity: Activity, id: Int) {
    val w = activity.window ?: return
    if (w.attributes.preferredDisplayModeId == id) return
    w.attributes = w.attributes.apply { preferredDisplayModeId = id }
}
