/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import kotlinx.coroutines.flow.first
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.unit.sp
import app.opentv.ui.jaskRowEnds
import app.opentv.ui.jaskWhiteFill
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import app.opentv.ui.jaskFocusLift
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import app.opentv.ui.LocalLayoutClass
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.core.SleepTimer
import app.opentv.core.findActivity
import app.opentv.core.requestIgnoreBatteryOptimizations
import app.opentv.data.model.Channel
import app.opentv.data.model.shownName
import app.opentv.player.PlaybackQueue
import app.opentv.player.PlaybackRequest
import app.opentv.player.PlaybackState
import app.opentv.player.PlayerEngine
import app.opentv.player.PlayerEngineConfig
import app.opentv.player.PlayerTrack
import app.opentv.player.PlayerTrackType
import app.opentv.player.VideoScale
import app.opentv.ui.RecordingBackgroundDialog
import app.opentv.ui.RecordingBackgroundPrompt
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Full-screen live playback.
 *
 * The video fills the screen with no chrome. A single control bar slides up from the bottom and
 * holds everything — transport (play/pause, rewind, forward), and pickers for subtitles, audio,
 * quality and aspect ratio. It hides after a few seconds and any remote button brings it back.
 * Nothing is ever left permanently painted over the picture.
 *
 * Subtitles and audio come from the actual tracks in the stream ([PlayerEngine.tracks]) and
 * are selected explicitly — that is the fix for "captions on but nothing shows", which happens
 * when the renderer is merely enabled and left to guess a language.
 */
@Composable
fun PlayerScreen(
    channelId: Long?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val graph = remember { ServiceLocator.get(context) }
    val settings = remember { graph.settings }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val subtitlesDefault by settings.subtitlesEnabled.collectAsState()
    val controller = remember {
        graph.playerEngines.create(
            scope,
            PlayerEngineConfig(
                subtitlesEnabled = settings.subtitlesEnabled.value,
                // Opt-in shallow DVR so the transport's pause/rewind actually holds on a live stream.
                dvr = settings.livePauseEnabled.value,
            ),
        )
    }
    val state by controller.state.collectAsState()
    val tracks by controller.tracks.collectAsState()
    val playback = BindPlayerPlayback(controller)
    // What's recording right now, so the Record button can show as armed for this channel.
    val activeRecordings by graph.recordingRepository.observeActive().collectAsState(initial = emptyList())

    // Hold the screen awake while the player is on screen. A view-level keepScreenOn flag isn't
    // reliable on every TV box, so we set the window flag on the Activity directly — that's what
    // actually stops the system screensaver from firing mid-programme. keepScreenOn stays on too,
    // as a belt-and-braces backstop.
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view.keepScreenOn = true
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view.keepScreenOn = false
            controller.release()
            scope.cancel()
        }
    }

    var variants by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var currentId by remember { mutableStateOf<Long?>(null) }
    // The channel on screen, for presence and the remote (zapping changes it).
    LaunchedEffect(currentId) { currentId?.let { app.opentv.remote.NowPlaying.set(app.opentv.remote.NowPlaying.Item(channelId = it), controller) } }
    // The channel we were on before this one — powers the "Last channel" recall in the list.
    var previousId by remember { mutableStateOf<Long?>(null) }
    // Digits typed on the remote accumulate here, then jump to that channel number after a beat.
    var numberEntry by remember { mutableStateOf("") }
    val paused = !playback.playWhenReady
    val resizeMode by settings.playerResizeMode.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    var displayMenuOpen by remember { mutableStateOf(false) }
    var playOnOpen by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var channelListVisible by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    // Offered once per session the first time the user records here while OpenTV isn't exempt from
    // battery optimisation, so the capture survives the screen sleeping. Never blocks recording.
    var showBackgroundPrompt by remember { mutableStateOf(false) }

    // Picture-in-picture. While the player is on it is "eligible" to shrink to a floating window
    // (pressing Home does it, handled in MainActivity); [inPip] drives hiding all the chrome.
    val inPip by app.opentv.core.PipState.inPip.collectAsState()
    val pipSupported = remember {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE,
            )
    }
    val barFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }

    // The channel list you were browsing, for channel up/down and the in-player list. Snapshotted
    // on entry so it doesn't shift under you mid-session.
    val queue = remember { PlaybackQueue.items }
    // Touch devices get swipe gestures, a back arrow and an on-screen channel-list button —
    // the d-pad paths (Left for the list, Back to leave) don't exist there.
    val touch = LocalLayoutClass.current.isTouch

    fun reveal() {
        controlsVisible = true
        interaction++
    }

    fun tuneTo(channel: Channel) {
        currentId = channel.id
        settings.lastChannelId = channel.id
        settings.addRecentChannel(channel.id)
        scope.launch {
            val source = graph.sourceRepository.byId(channel.sourceId)
            // Xtream/M3U carry a ready URL; a Stalker channel's URL is minted here from its cmd.
            val url = graph.catalogRepository.resolvePlaybackUrl(channel, source)
            controller.play(
                PlaybackRequest(
                    url = url,
                    title = channel.shownName,
                    userAgent = source?.userAgent ?: "OpenTV/0.1 (Android)",
                    isLive = true,
                ),
                debounce = false,
            )
        }
    }

    fun playChannelId(id: Long) {
        // Remember where we came from so "Last channel" can bounce straight back. Quality switches
        // go through tuneTo directly, so they never count as a channel change here.
        currentId?.let { if (it != id) previousId = it }
        scope.launch {
            val channel = graph.catalogRepository.channel(id) ?: return@launch
            variants = graph.catalogRepository.variants(channel)
            tuneTo(variants.firstOrNull { it.id == channel.id } ?: channel)
        }
    }

    // A black picture: some channels are "on" and send pure black (ESPN 2 on 2026-11-20). A few seconds after it
    // starts, look at the frame; flat every time means no picture. The channel is marked (so Live TV and Sports skip
    // it) and, when it came from a Live TV or Sports pick, the next channel weighed for that pick plays instead.
    // What it found goes to the channel's record (StreamHealth), and to its way to watch a game when it came from a
    // Sports pick: a picture, a black screen, no start at all, and later, minutes watched.
    LaunchedEffect(currentId) {
        val id = currentId ?: return@LaunchedEffect
        val pc = controller as? app.opentv.player.PlayerController ?: return@LaunchedEffect
        val health = app.opentv.livetv.StreamHealth
        val channel = graph.catalogRepository.channel(id) ?: return@LaunchedEffect
        val way = app.opentv.ui.channels.LiveFallback.wayOf(id)
        val name = channel.shownName
        fun moveOn(why: String) {
            app.opentv.ui.channels.NoPicture.mark(id)
            val next = app.opentv.ui.channels.LiveFallback.next(id)
            android.util.Log.i("jaskPlayer", "$why on $id; next=$next")
            if (next != null) {
                android.widget.Toast.makeText(context, "No picture on $name: trying another", android.widget.Toast.LENGTH_SHORT).show()
                playChannelId(next)
            } else android.widget.Toast.makeText(context, if (why == "flat picture") "$name is showing a black screen" else "$name isn't starting", android.widget.Toast.LENGTH_LONG).show()
        }
        val started = kotlinx.coroutines.withTimeoutOrNull(20_000) { controller.state.first { it is PlaybackState.Playing } }
        if (started == null) {
            health.bad(channel, health.NO_FRAME); way?.let { health.wayBad(it.sport, it.title, health.NO_FRAME) }
            moveOn("no start"); return@LaunchedEffect
        }
        kotlinx.coroutines.delay(1_500)
        var looked = 0
        var picture = false
        for (attempt in 0 until 5) {
            when (FrameCheck.flat(pc.videoView)) { true -> looked++; false -> { picture = true; break }; null -> Unit }
            kotlinx.coroutines.delay(800)
        }
        if (picture) {
            android.util.Log.i("jaskPlayer", "picture on $id")
            app.opentv.ui.channels.NoPicture.confirm(id)
            health.good(channel); way?.let { health.wayGood(it.sport, it.title) }
            // Still on it three minutes later: the strongest sign it's the right channel and it holds up.
            val picked = app.opentv.ui.channels.LiveFallback.picked.also { app.opentv.ui.channels.LiveFallback.picked = false }
            kotlinx.coroutines.delay(3 * 60_000)
            health.good(channel, health.WATCHED)
            way?.let { health.wayGood(it.sport, it.title, health.WATCHED + if (picked) health.PICKED else 0f) }
            return@LaunchedEffect
        }
        if (looked < 3) { android.util.Log.i("jaskPlayer", "couldn't look at $id"); return@LaunchedEffect }
        health.bad(channel, health.FLAT); way?.let { health.wayBad(it.sport, it.title, health.FLAT) }
        moveOn("flat picture")
    }

    fun zapBy(delta: Int) {
        if (queue.isEmpty()) return
        val cur = queue.indexOfFirst { it.id == currentId }.let { if (it < 0) 0 else it }
        val next = (cur + delta).coerceIn(0, queue.size - 1)
        if (next != cur) playChannelId(queue[next].id)
    }

    fun toggleRecord() {
        val active = activeRecordings.firstOrNull { it.channelId == currentId }
        if (active != null) {
            graph.recordingEngine.stop(active.id)
        } else {
            val channel = variants.firstOrNull { it.id == currentId } ?: return
            app.opentv.core.NotificationPermission.askIfNeeded(context)
            scope.launch { graph.recordingEngine.startChannel(channel) }
            if (RecordingBackgroundPrompt.shouldShow(context)) {
                RecordingBackgroundPrompt.markShown()
                showBackgroundPrompt = true
            }
        }
        interaction++
    }

    LaunchedEffect(channelId) {
        val id = channelId ?: return@LaunchedEffect
        val channel = graph.catalogRepository.channel(id) ?: return@LaunchedEffect
        variants = graph.catalogRepository.variants(channel)
        tuneTo(variants.firstOrNull { it.id == channel.id } ?: channel)
    }

    // Sleep timer: when the armed deadline passes, stop and leave the player. Re-arming from
    // settings restarts this effect with the new deadline.
    val sleepDeadline by SleepTimer.deadline.collectAsState()
    LaunchedEffect(sleepDeadline) {
        val d = sleepDeadline ?: return@LaunchedEffect
        val wait = d - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        SleepTimer.clear()
        controller.stop()
        onBack()
    }

    // Apply the saved captions default once the stream's tracks arrive. Only auto-selects when
    // nothing is chosen yet, so it never overrides a track the user picked by hand.
    LaunchedEffect(tracks) {
        val hasText = tracks.any { it.type == PlayerTrackType.TEXT }
        val textChosen = tracks.any { it.type == PlayerTrackType.TEXT && it.isSelected }
        if (subtitlesDefault && hasText && !textChosen) controller.setSubtitlesEnabled(true)
    }

    // Auto-hide the bar after a few seconds — but never while paused or with a picker open.
    // The TV switches to a refresh rate that suits the video while this player is open.
    app.opentv.ui.player.JaskFrameRateMatch(controller, key = currentId, rewind = false)

    LaunchedEffect(controlsVisible, interaction, state, paused, panel, displayMenuOpen, playOnOpen) {
        if (controlsVisible && !displayMenuOpen && !playOnOpen && !paused && panel == Panel.NONE &&
            state is PlaybackState.Playing
        ) {
            delay(CONTROLS_TIMEOUT_MILLIS)
            controlsVisible = false
        }
    }

    // Focus: a picker's first row when one is open, otherwise the bar, otherwise the full-screen
    // catcher (so the next remote press brings the bar back).
    LaunchedEffect(controlsVisible, panel) {
        if (controlsVisible) {
            delay(40)
            // Stream info is a card, not a menu: focus stays on the button that opened it.
            runCatching { when (panel) { Panel.NONE -> barFocus.requestFocus(); Panel.INFO -> Unit; else -> panelFocus.requestFocus() } }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    // Back steps back out one layer at a time — channel list, then picker, then the control bar —
    // and only leaves the player once nothing is on screen. From immersive it's a single press out,
    // so it never traps you, but it also no longer throws you all the way to the guide just because
    // you wanted to dismiss the bar.
    fun handleBack() {
        when {
            channelListVisible -> channelListVisible = false
            panel != Panel.NONE -> panel = Panel.NONE
            controlsVisible -> controlsVisible = false
            else -> {
                controller.stop()
                onBack()
            }
        }
    }
    BackHandler { handleBack() }
    // A remote's Back is taken in the key preview below (down consumed, action on up): with a control focused,
    // Compose otherwise turns Back into "move focus out" to the focusable root, which ate the press and hid the ring.
    var backArmed by remember { mutableStateOf(false) }

    // Number entry: once digits stop coming, jump to that channel number in the browsing list.
    LaunchedEffect(numberEntry) {
        if (numberEntry.isEmpty()) return@LaunchedEffect
        delay(NUMBER_ENTRY_TIMEOUT_MILLIS)
        val num = numberEntry.toIntOrNull()
        numberEntry = ""
        val target = num?.let { n -> queue.firstOrNull { it.number == n } }
        if (target != null) playChannelId(target.id)
    }

    // Focus the channel list when it opens; hand focus back to the video catcher when it closes.
    LaunchedEffect(channelListVisible) {
        if (channelListVisible) {
            delay(40)
            runCatching { listFocus.requestFocus() }
        } else if (!controlsVisible) {
            runCatching { rootFocus.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                // Back/Escape run the same steps as the back handler; an open menu (display size, Play on) keeps its own.
                if (event.key == Key.Back || event.key == Key.Escape) return@onPreviewKeyEvent when {
                    displayMenuOpen || playOnOpen -> { backArmed = false; false }
                    event.type == KeyEventType.KeyDown -> { backArmed = true; true }
                    event.type == KeyEventType.KeyUp && backArmed -> { backArmed = false; handleBack(); true }
                    else -> false
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = keyToDigit(event.key)
                when {
                    // The remote's play/pause (live pause holds when "Pause live TV" is on).
                    event.key == Key.MediaPlayPause || event.key == Key.MediaPlay || event.key == Key.MediaPause -> {
                        controller.setPlaying(when (event.key) { Key.MediaPlay -> true; Key.MediaPause -> false; else -> !controller.status.value.isPlaying })
                        reveal(); true
                    }
                    // Typing a channel number jumps to it, TiviMate-style.
                    digit != null -> {
                        numberEntry = (numberEntry + digit).take(4)
                        reveal(); interaction++; true
                    }
                    // A picker, the channel list or an open menu (display size, Play on: drawn in this window, so
                    // their keys pass through here first) owns the whole d-pad while it's up.
                    channelListVisible || panel != Panel.NONE || displayMenuOpen || playOnOpen -> {
                        interaction++
                        false
                    }
                    // Channel up/down works whether or not the bar is showing — this remote has no
                    // CH+/CH- keys, so up/down IS the channel changer. Each zap flashes the bar as a
                    // channel banner, then it auto-hides.
                    event.key == Key.DirectionUp || event.key == Key.ChannelUp -> { zapBy(-1); reveal(); true }
                    event.key == Key.DirectionDown || event.key == Key.ChannelDown -> { zapBy(1); reveal(); true }
                    // With the bar up, left/right drive its buttons; let them through.
                    controlsVisible -> {
                        interaction++
                        false
                    }
                    // Immersive: left opens the channel list, right the quality picker.
                    event.key == Key.DirectionLeft -> { if (queue.isNotEmpty()) channelListVisible = true; true }
                    event.key == Key.DirectionRight -> {
                        if (variants.size > 1) { reveal(); panel = Panel.QUALITY }; true
                    }
                    else -> { reveal(); true }
                }
            }
            .focusRequester(rootFocus)
            .focusable()
            // Touch devices take taps in PlayerGestureLayer (under the controls) instead.
            .then(
                if (touch) Modifier
                else Modifier.pointerInput(Unit) {
                    detectTapGestures { if (controlsVisible) controlsVisible = false else reveal() }
                },
            ),
    ) {
        controller.surface.Content(
            modifier = Modifier.fillMaxSize(),
            scale = VideoScale.fromStored(resizeMode),
            transparent = false,
        )

        if (touch) ImmersiveSystemBars(hidden = !controlsVisible || inPip)

        // Swipe left for the next channel, right for the previous — like turning pages.
        PlayerGestureLayer(
            enabled = touch && !inPip,
            dragsEnabled = !channelListVisible && panel == Panel.NONE,
            onTap = {
                when {
                    channelListVisible -> channelListVisible = false
                    controlsVisible -> controlsVisible = false
                    else -> reveal()
                }
            },
        ) { dir ->
            zapBy(-dir)
            reveal()
        }

        // The channel number as you type it, top-right, until it resolves.
        if (numberEntry.isNotEmpty() && !inPip) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.82f))
                    .padding(horizontal = 28.dp, vertical = 16.dp),
            ) {
                Text(numberEntry, color = Color.White, style = MaterialTheme.typography.displaySmall)
            }
        }

        if (!inPip) when (val current = state) {
            is PlaybackState.Buffering -> if (!controlsVisible) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(16.dp))
                        Text(current.title, color = Color.White)
                    }
                }
            }

            is PlaybackState.Error -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(48.dp),
                    ) {
                        Text(current.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            current.message,
                            color = Color.White.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        app.opentv.ui.settings.JaskPill(stringResource(R.string.common_try_again), primary = true) { controller.retry() }
                        variants.firstOrNull { it.id != currentId }?.let { alternative ->
                            // A neutral pill, not a Material TextButton (that drew in the theme's accent colour).
                            Spacer(Modifier.height(10.dp))
                            app.opentv.ui.settings.JaskPill("Try alternative source") { tuneTo(alternative) }
                        }
                    }
                }
            }

            else -> Unit
        }

        val channelTitle = when (val s = state) {
            is PlaybackState.Buffering -> s.title
            is PlaybackState.Playing -> s.title
            is PlaybackState.Error -> s.title
            else -> ""
        }

        if (!inPip) app.opentv.ui.vod.JaskTheme { JaskPlayerOverlayHost {
            val reading by rememberStreamReading(controller)
            val buffering = state is PlaybackState.Buffering
            AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    JaskPlayerScrims()
                    val top = Modifier.windowInsetsPadding(PlayerChromeInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                    val bottom = Modifier.windowInsetsPadding(PlayerChromeInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    fun toggle(p: Panel) { panel = if (panel == p) Panel.NONE else p; interaction++ }
                    // Title top-left with LIVE; clock and the quality badge top-right (design: player chrome canvas).
                    JaskPlayerHeader(channelTitle, Modifier.align(Alignment.TopCenter).then(top)
                            .padding(if (touch) androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 14.dp)
                                else androidx.compose.foundation.layout.PaddingValues(start = 48.dp, end = 48.dp, top = 36.dp)),
                        quality = reading, infoOpen = panel == Panel.INFO, onQuality = { toggle(Panel.INFO) },
                        leading = if (touch) ({ app.opentv.ui.JaskGlassCircle(Icons.AutoMirrored.Filled.ArrowBack, "Back", size = 40.dp) { controller.stop(); onBack() } }) else null,
                        overline = {
                            Text("LIVE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(PlayerLiveRed).padding(horizontal = 9.dp, vertical = 3.dp))
                        }, compact = touch) {
                        if (touch && pipSupported) JaskPlayerTool(Icons.Filled.PictureInPictureAlt, stringResource(R.string.player_pop_out)) {
                            (context.findActivity() as? app.opentv.MainActivity)?.enterPipNow(); interaction++
                        }
                    }
                    if (panel == Panel.INFO) JaskStreamInfoCard(reading, buffering,
                        Modifier.align(Alignment.TopEnd).then(top).padding(top = if (touch) 64.dp else 150.dp, end = if (touch) 20.dp else 36.dp),
                        source = variants.firstOrNull { it.id == currentId }?.qualityLabel?.takeIf(String::isNotBlank))
                    if (panel == Panel.QUALITY) OptionPanel(panel, controller, settings, tracks, variants, currentId, { tuneTo(it) }, panelFocus,
                        Modifier.align(Alignment.BottomEnd).then(bottom).padding(end = if (touch) 24.dp else 48.dp, bottom = 104.dp)) { panel = Panel.NONE; interaction++ }
                    if (panel == Panel.TRACKS) JaskTracksMenu(controller, tracks, panelFocus,
                        Modifier.align(Alignment.BottomEnd).then(bottom).padding(end = if (touch) 24.dp else 48.dp, bottom = 104.dp),
                        onSubtitlesChosen = settings::setSubtitlesEnabled) { panel = Panel.NONE; interaction++ }
                    // One row along the bottom: Left/Right move along it on a remote (Up/Down change channel).
                    Row(Modifier.align(Alignment.BottomCenter).then(bottom).fillMaxWidth()
                        .padding(horizontal = if (touch) 24.dp else 48.dp, vertical = if (touch) 14.dp else 36.dp)
                        .then(if (touch) Modifier.horizontalScroll(rememberScrollState()) else Modifier.jaskRowEnds()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        JaskPlayPausePill(paused, buffering, barFocus) { controller.setPlaying(paused); interaction++ }
                        if (controller.isSeekable) {
                            JaskPlayerCircle(Icons.Filled.FastRewind, stringResource(R.string.player_rewind)) { controller.seekBackward(); interaction++ }
                            JaskPlayerCircle(Icons.Filled.FastForward, stringResource(R.string.player_forward)) { controller.seekForward(); interaction++ }
                        }
                        if (!touch) Spacer(Modifier.weight(1f))
                        if (queue.isNotEmpty()) JaskPlayerPill(Icons.AutoMirrored.Filled.List, stringResource(R.string.common_channels), {
                            channelListVisible = true; interaction++
                        })
                        JaskPlayerPill(Icons.Filled.Subtitles, "Audio & subtitles", { toggle(Panel.TRACKS) }, active = panel == Panel.TRACKS)
                        if (variants.size > 1) JaskPlayerCircle(Icons.Filled.HighQuality, stringResource(R.string.player_quality)) { toggle(Panel.QUALITY) }
                        // Up changes channel here, so stream info gets its own button on a remote.
                        if (!touch) JaskPlayerCircle(Icons.Filled.Info, "Stream info") { toggle(Panel.INFO) }
                        // Play this channel on another device; this one stops (one-stream providers).
                        app.opentv.ui.JaskPlayOn(null, onSent = { controller.stop(); onBack() }, onOpenChange = { playOnOpen = it }) { open ->
                            JaskPlayerCircle(Icons.Filled.Cast, "Play on another device") { open(); interaction++ }
                        }
                        PlayerDisplayControls(settings, onMenuVisibilityChanged = { displayMenuOpen = it }) { interaction++ }
                        val recordingThis = activeRecordings.any { it.channelId == currentId }
                        Box {
                            JaskPlayerCircle(Icons.Filled.FiberManualRecord,
                                if (recordingThis) stringResource(R.string.common_stop) else stringResource(R.string.player_record)) { toggleRecord() }
                            // A red dot marks the record button, solid while this channel records.
                            Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(10.dp).clip(CircleShape)
                                .background(if (recordingThis) PlayerLiveRed else PlayerLiveRed.copy(alpha = 0.45f)))
                        }
                        if (!touch && pipSupported) JaskPlayerCircle(Icons.Filled.PictureInPictureAlt, stringResource(R.string.player_pop_out)) {
                            (context.findActivity() as? app.opentv.MainActivity)?.enterPipNow(); interaction++
                        }
                    }
                }
            }
        } }

        // Left-side transparent channel list — d-pad Left opens it, pick a channel to switch.
        if (!inPip) AnimatedVisibility(
            visible = channelListVisible && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            val currentIndex = queue.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
            val listState = rememberLazyListState()
            LaunchedEffect(Unit) { runCatching { listState.scrollToItem(currentIndex) } }
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(380.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.88f), Color.Black.copy(alpha = 0.55f))))
                    .windowInsetsPadding(PlayerChromeInsets.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
                    .padding(vertical = 16.dp),
            ) {
                Text(
                    stringResource(R.string.common_channels),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                // A quick "jump back to the channel you just left" pin, TiviMate-style.
                val lastItem = previousId?.let { pid -> queue.firstOrNull { it.id == pid } }
                if (lastItem != null && lastItem.id != currentId) {
                    ChannelListRow(
                        item = lastItem,
                        playing = false,
                        focusRequester = null,
                        leadingLabel = stringResource(R.string.player_last),
                        onClick = {
                            playChannelId(lastItem.id)
                            channelListVisible = false
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                }
                LazyColumn(state = listState) {
                    itemsIndexed(queue, key = { _, item -> item.id }) { index, item ->
                        ChannelListRow(
                            item = item,
                            playing = item.id == currentId,
                            focusRequester = if (index == currentIndex) listFocus else null,
                            onClick = {
                                playChannelId(item.id)
                                channelListVisible = false
                            },
                        )
                    }
                }
            }
        }
    }

    if (showBackgroundPrompt) {
        RecordingBackgroundDialog(
            onAllow = {
                showBackgroundPrompt = false
                context.requestIgnoreBatteryOptimizations()
            },
            onDismiss = { showBackgroundPrompt = false },
        )
    }
}

@Composable
private fun ChannelListRow(
    item: PlaybackQueue.Item,
    playing: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    leadingLabel: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White.copy(alpha = 0.18f)
        playing -> Color.White.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val fg = Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A "Last" tag, or the channel number if the provider gives one — a fixed-width slot so
        // the logos and names line up down the list.
        Text(
            leadingLabel ?: item.number?.toString().orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = fg.copy(alpha = 0.7f),
            maxLines = 1,
            modifier = Modifier.width(40.dp),
        )
        AsyncImage(
            model = item.logoUrl,
            contentDescription = null,
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Text(item.name, style = MaterialTheme.typography.titleMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private enum class Panel { NONE, SUBTITLES, AUDIO, QUALITY, TRACKS, INFO }

@Composable
private fun OptionPanel(
    panel: Panel,
    controller: PlayerEngine,
    settings: app.opentv.core.AppSettings,
    tracks: List<PlayerTrack>,
    variants: List<Channel>,
    currentId: Long?,
    onTune: (Channel) -> Unit,
    firstFocus: FocusRequester,
    modifier: Modifier = Modifier,
    onDone: () -> Unit,
) {
    val offLabel = stringResource(R.string.player_subtitles_off)
    val standardLabel = stringResource(R.string.player_quality_standard)
    val options: List<Option> = when (panel) {
        Panel.SUBTITLES -> buildSubtitleOptions(controller, settings, tracks, offLabel, onDone)
        Panel.AUDIO -> buildAudioOptions(controller, tracks, onDone)
        Panel.QUALITY -> variants.map { v ->
            Option(v.qualityLabel.ifEmpty { standardLabel }, v.id == currentId) { onTune(v); onDone() }
        }
        Panel.NONE, Panel.TRACKS, Panel.INFO -> emptyList()
    }

    val title = when (panel) {
        Panel.SUBTITLES -> stringResource(R.string.player_subtitles)
        Panel.AUDIO -> stringResource(R.string.player_audio)
        Panel.QUALITY -> stringResource(R.string.player_quality)
        Panel.NONE, Panel.TRACKS, Panel.INFO -> ""
    }

    JaskPlayerPanel(title, options.map { Triple(it.label, it.selected, it.onClick) }, stringResource(R.string.player_none_available), firstFocus, modifier)
}

private data class Option(val label: String, val selected: Boolean, val onClick: () -> Unit)

private fun buildSubtitleOptions(
    controller: PlayerEngine,
    settings: app.opentv.core.AppSettings,
    tracks: List<PlayerTrack>,
    offLabel: String,
    onDone: () -> Unit,
): List<Option> {
    val textTracks = tracks.filter { it.type == PlayerTrackType.TEXT }
    val anySelected = textTracks.any { it.isSelected }
    val list = mutableListOf<Option>()
    list += Option(offLabel, !anySelected) {
        controller.disableText()
        settings.setSubtitlesEnabled(false)
        onDone()
    }
    textTracks.filter { it.isSupported }.forEach { track ->
        list += Option(trackLabel(track.label, track.language, list.size), track.isSelected) {
            controller.selectTrack(track)
            settings.setSubtitlesEnabled(true)
            onDone()
        }
    }
    return list
}

private fun buildAudioOptions(
    controller: PlayerEngine,
    tracks: List<PlayerTrack>,
    onDone: () -> Unit,
): List<Option> {
    val audioTracks = tracks.filter { it.type == PlayerTrackType.AUDIO }
    val list = mutableListOf<Option>()
    audioTracks.filter { it.isSupported }.forEach { track ->
        list += Option(trackLabel(track.label, track.language, list.size), track.isSelected) {
            controller.selectTrack(track)
            onDone()
        }
    }
    return list
}

private fun trackLabel(label: String?, language: String?, index: Int): String {
    if (!label.isNullOrBlank()) return label
    if (!language.isNullOrBlank() && language != "und") {
        return runCatching { Locale(language).displayLanguage.ifBlank { language } }.getOrDefault(language)
    }
    return "Track ${index + 1}"
}

private const val CONTROLS_TIMEOUT_MILLIS = 5_000L
private const val NUMBER_ENTRY_TIMEOUT_MILLIS = 2_000L

/** Maps a remote's number keys (top row and numeric keypad) to a digit, or null for other keys. */
private fun keyToDigit(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}
