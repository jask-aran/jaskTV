package app.opentv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.opentv.account.AccountDevice
import app.opentv.account.AccountManager
import app.opentv.account.sync.LanLink
import app.opentv.remote.RemoteCommand
import kotlinx.coroutines.delay

private val Ink = Color(0xFFF4F1ED)
private val Dim = Color(0xFFA6A29E)
private val Live = Color(0xFF7BD88F)

/**
 * The Devices button in the header, beside the account circle: shown when signed in with other devices. A dot
 * when one of them is playing something. Opens [DevicesSheet].
 */
@Composable
internal fun JaskDevicesButton() {
    val context = LocalContext.current
    val account = remember { AccountManager.get(context) }
    val status by account.status.collectAsState()
    val ready = status as? AccountManager.Status.Ready ?: return
    if (ready.devices.size < 2) return
    val playing by account.nowPlaying.collectAsState()
    var open by remember { mutableStateOf(false) }
    val busy = playing.values.any { it.device != ready.deviceId && it.title != null && fresh(it) }
    Box {
        JaskGlassCircle(Icons.Filled.Devices, "Devices and remote") { open = true }
        if (busy) Box(Modifier.align(Alignment.TopEnd).offset((-6).dp, 6.dp).size(9.dp).clip(CircleShape).background(Live))
    }
    if (open) DevicesSheet(ready) { open = false }
}

/** Presence counts as current for 3 minutes (the server path updates once a minute while playing). */
private fun fresh(p: LanLink.Presence) = System.currentTimeMillis() - p.at < 3 * 60_000

/**
 * Your other devices: what each is playing (live over the Wi-Fi link, a little later through the account
 * server when away), and for ones on this Wi-Fi: the remote, "Play on" and "Watch here".
 */
@Composable
private fun DevicesSheet(ready: AccountManager.Status.Ready, onClose: () -> Unit) {
    val context = LocalContext.current
    val account = remember { AccountManager.get(context) }
    val playing by account.nowPlaying.collectAsState()
    val lanReady by account.lanReady.collectAsState()
    val linked by remember(lanReady) { account.lanLinked }.collectAsState()
    val here by app.opentv.remote.NowPlaying.session.collectAsState()
    var remoteFor by remember { mutableStateOf<AccountDevice?>(null) }
    val rtt by remember(lanReady) { account.lanRtt }.collectAsState()
    // While this is open the phone's Wi-Fi stays awake: in power saving it holds incoming packets for up to ~300 ms,
    // which would make the TV's state lag behind the remote.
    androidx.compose.runtime.DisposableEffect(Unit) {
        val wifi = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
        val lock = runCatching { wifi.createWifiLock(mode, "jaskTV remote").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        onDispose { runCatching { lock?.release() } }
    }
    // "Watch here" ready before it's pressed (a show this device hasn't opened gets its episodes now).
    LaunchedEffect(playing.values.mapNotNull { it.item }.toSet()) { playing.values.filter { it.item != null }.forEach(account::prepare) }
    // Ticks so progress bars and "seen" times move.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); tick++ } }

    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(16.dp).widthIn(max = 520.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xFF1C1B1A))
            .padding(20.dp).verticalScroll(rememberScrollState())) {
            val remote = remoteFor
            if (remote != null) {
                LaunchedEffect(remote.id) { account.probe(remote.id) }
                RemotePad(remote, playing[remote.id], remote.id in linked, rtt[remote.id], onBack = { remoteFor = null }) { account.sendCommand(remote.id, it) }
                return@Column
            }
            Text("Devices", color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("The remote works when both are on the same Wi-Fi.", color = Dim, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            for (d in ready.devices.filter { it.id != ready.deviceId }) {
                val p = playing[d.id]?.takeIf(::fresh)
                val near = d.id in linked
                DeviceRow(d, p, near, rtt[d.id], tick)
                if (near) Row(Modifier.padding(start = 52.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Remote") { remoteFor = d }
                    if (here != null) Chip("Play on ${d.name.take(18)}") { account.castTo(d.id); onClose() }
                    if (p?.item != null) Chip("Watch here") { account.watchHere(p); onClose() }
                } else Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun DeviceRow(d: AccountDevice, p: LanLink.Presence?, near: Boolean, rtt: Long?, @Suppress("UNUSED_PARAMETER") tick: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (d.kind == "tv") Icons.Filled.Tv else Icons.Filled.PhoneAndroid, null, Modifier.size(28.dp), tint = if (near) Ink else Dim)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, color = Ink, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val where = if (near) "On this Wi-Fi" + (rtt?.let { " · $it ms" } ?: "") else "Away · seen " + android.text.format.DateUtils.getRelativeTimeSpanString(maxOf(d.lastSeen, p?.at ?: 0))
            val what = p?.title?.let { (if (p.playing || p.kind == "live") "Playing " else "Paused · ") + it + (p.detail?.let { d -> " · $d" } ?: "") }
            Text(what ?: where, color = if (what != null) Ink else Dim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (what != null) {
                Text(where, color = Dim, style = MaterialTheme.typography.labelSmall)
                progressOf(p)?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp).clip(CircleShape), color = Ink, trackColor = Color.White.copy(alpha = .15f)) }
            }
        }
    }
}

/** How far through, moving on from the last report while it plays. */
private fun progressOf(p: LanLink.Presence): Float? {
    val dur = p.durationMs?.takeIf { it > 0 } ?: return null
    val pos = (p.positionMs ?: return null) + if (p.playing) System.currentTimeMillis() - p.at else 0
    return (pos.toFloat() / dur).coerceIn(0f, 1f)
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) =
    Text(label, Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .1f)).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 8.dp), color = Ink, style = MaterialTheme.typography.labelLarge)

/** The remote: arrows and OK, back and menu, play/pause and skip, volume and channel. Each press is one sealed frame over Wi-Fi. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.RemotePad(d: AccountDevice, reported: LanLink.Presence?, near: Boolean, rtt: Long?,
    onBack: () -> Unit, send: (RemoteCommand) -> Boolean) {
    val haptics = LocalHapticFeedback.current
    var lost by remember { mutableStateOf(false) }
    // Shown at once on a press, before the TV confirms; replaced by the TV's own report when it arrives (milliseconds later).
    var guess by remember(reported?.at) { mutableStateOf(reported) }
    val p = guess
    // Redrawn four times a second so the progress bar moves smoothly between reports.
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(250); frame++ } }
    @Suppress("UNUSED_EXPRESSION") frame
    fun press(c: RemoteCommand) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        lost = !send(c)
        val g = guess ?: return
        val now = System.currentTimeMillis()
        val pos = (g.positionMs ?: 0) + if (g.playing) now - g.at else 0
        guess = when (c.type) {
            "playPause" -> g.copy(playing = !g.playing, positionMs = pos, at = now)
            "seek" -> g.copy(positionMs = (pos + (c.ms ?: 0)).coerceAtLeast(0), at = now)
            else -> g
        }
    }
    fun key(code: Int) = press(RemoteCommand("key", key = code))
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Devices", tint = Ink) }
        Column(Modifier.weight(1f)) {
            Text(d.name + (rtt?.let { "  ·  $it ms" } ?: ""), color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(p?.title?.let { it + (p.detail?.let { x -> " · $x" } ?: "") } ?: "Nothing playing", color = Dim,
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    p?.let { progressOf(it) }?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth().padding(vertical = 8.dp).height(3.dp).clip(CircleShape), color = Ink, trackColor = Color.White.copy(alpha = .15f)) }
    if (!near || lost) {
        Text("Not on this Wi-Fi right now. The remote comes back as soon as both are on the same network.",
            Modifier.padding(vertical = 8.dp), color = Color(0xFFFFB4A9), style = MaterialTheme.typography.bodySmall)
    }
    if (p != null && !p.remote && near) Text("Open jaskTV on ${d.name} to use the arrows.", color = Dim, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(16.dp))
    // D-pad
    Box(Modifier.align(Alignment.CenterHorizontally).size(232.dp).clip(CircleShape).background(Color.White.copy(alpha = .07f))) {
        PadKey(Icons.Filled.KeyboardArrowUp, "Up", Modifier.align(Alignment.TopCenter)) { key(KeyEvent.KEYCODE_DPAD_UP) }
        PadKey(Icons.Filled.KeyboardArrowDown, "Down", Modifier.align(Alignment.BottomCenter)) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        PadKey(Icons.Filled.KeyboardArrowLeft, "Left", Modifier.align(Alignment.CenterStart)) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
        PadKey(Icons.Filled.KeyboardArrowRight, "Right", Modifier.align(Alignment.CenterEnd)) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        Box(Modifier.align(Alignment.Center).size(92.dp).clip(CircleShape).background(Color.White.copy(alpha = .14f))
            .clickable { key(KeyEvent.KEYCODE_DPAD_CENTER) }, contentAlignment = Alignment.Center) {
            Text("OK", color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        RoundKey(Icons.AutoMirrored.Filled.ArrowBack, "Back") { key(KeyEvent.KEYCODE_BACK) }
        RoundKey(Icons.Filled.Menu, "Menu") { key(KeyEvent.KEYCODE_MENU) }
        RoundKey(Icons.Filled.Cast, "Guide") { key(KeyEvent.KEYCODE_GUIDE) }
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        RoundKey(Icons.Filled.FastRewind, "Back 10 seconds") { press(RemoteCommand("seek", ms = -10_000)) }
        RoundKey(if (p?.playing == true) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", big = true) { press(RemoteCommand("playPause")) }
        RoundKey(Icons.Filled.FastForward, "Forward 10 seconds") { press(RemoteCommand("seek", ms = 10_000)) }
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        RoundKey(Icons.Filled.VolumeDown, "Volume down") { press(RemoteCommand("volume", ms = -1)) }
        RoundKey(Icons.Filled.VolumeUp, "Volume up") { press(RemoteCommand("volume", ms = 1)) }
        RoundKey(Icons.Filled.KeyboardArrowDown, "Channel down") { key(KeyEvent.KEYCODE_CHANNEL_DOWN) }
        RoundKey(Icons.Filled.KeyboardArrowUp, "Channel up") { key(KeyEvent.KEYCODE_CHANNEL_UP) }
    }
}

@Composable
private fun PadKey(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) =
    Box(modifier.size(72.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(36.dp), tint = Ink)
    }

@Composable
private fun RoundKey(icon: ImageVector, label: String, big: Boolean = false, onClick: () -> Unit) =
    Box(Modifier.size(if (big) 68.dp else 56.dp).clip(CircleShape).background(Color.White.copy(alpha = if (big) .18f else .08f))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(if (big) 34.dp else 26.dp), tint = Ink)
    }
