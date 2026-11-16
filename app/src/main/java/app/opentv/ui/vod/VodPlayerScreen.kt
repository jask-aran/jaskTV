/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import app.opentv.ui.LocalLayoutClass
import app.opentv.ui.player.PlayerGestureLayer
import app.opentv.ui.player.ImmersiveSystemBars
import app.opentv.ui.player.JaskPlayerPanel
import app.opentv.ui.player.JaskPlayerScrims
import app.opentv.ui.player.JaskPlayerTool
import app.opentv.ui.player.JaskPlayerTopBar
import app.opentv.ui.player.JaskScrubber
import app.opentv.ui.player.JaskTransport
import app.opentv.ui.player.PlayerChromeInsets
import app.opentv.ui.player.BindPlayerPlayback
import app.opentv.ui.player.PlayerDisplayControls
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.core.SleepTimer
import app.opentv.core.findActivity
import app.opentv.player.PlaybackRequest
import app.opentv.player.PlaybackState
import app.opentv.player.PlayerEngine
import app.opentv.player.PlayerEngineConfig
import app.opentv.player.PlayerTrack
import app.opentv.player.PlayerTrackType
import app.opentv.player.VideoScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays a movie or episode: a single non-live stream, with resume and a proper transport bar.
 *
 * The bar is drawn in Compose rather than left to the Media3 view's own controller, because that
 * controller needs the embedded view to hold d-pad focus to be summoned — which it doesn't, on a
 * TV inside Compose, so it reads as "no controls". This is the same self-drawn, auto-hiding bar as
 * the live player, with a seek bar added since a film needs scrubbing.
 */
@Composable
fun VodPlayerScreen(
    mediaKey: String,
    streamUrl: String,
    title: String,
    userAgent: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val graph = remember { ServiceLocator.get(context) }
    val settings = remember { graph.settings }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    // A recording that's still being written plays through the tail-following source. It behaves a
    // little differently in the transport: the "length" is how much has been recorded so far, which
    // keeps growing, and seeking is bounded to that recorded extent.
    val growingRec = remember(streamUrl) { streamUrl.startsWith("optvrec://") }
    // The SMB source lets a recording stored on a NAS play and seek in-app; harmless for the
    // http/file URLs of ordinary VOD.
    val controller = remember {
        graph.playerEngines.create(
            scope,
            PlayerEngineConfig(
                subtitlesEnabled = false,
                liveRecording = growingRec,
                smb = true,
                growingRecording = growingRec,
            ),
        )
    }
    // Skip forward/back within the recorded portion. For a growing recording ExoPlayer won't report
    // the item as seekable (no fixed length), so we seek directly, clamped to what's on disk.
    fun seekRelative(deltaMs: Long) {
        val ceiling = if (growingRec) controller.bufferedPositionMillis.coerceAtLeast(0L)
        else controller.durationMillis.takeIf { it > 0 } ?: Long.MAX_VALUE
        controller.seekTo((controller.positionMillis + deltaMs).coerceIn(0L, ceiling))
    }
    val state by controller.state.collectAsState()
    val tracks by controller.tracks.collectAsState()

    val resizeMode by settings.playerResizeMode.collectAsState()
    val inPip by app.opentv.core.PipState.inPip.collectAsState()
    val activity = context.findActivity() as? app.opentv.MainActivity
    var vodPanel by remember { mutableStateOf(VodPanel.NONE) }
    val panelFocus = remember { FocusRequester() }
    var positionMs by remember { mutableLongStateOf(0L) }
    var sessionPosition by rememberSaveable(mediaKey) { mutableLongStateOf(-1L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }

    var controlsVisible by remember { mutableStateOf(true) }
    var displayMenuOpen by remember { mutableStateOf(false) }
    val touch = LocalLayoutClass.current.isTouch
    var interaction by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val barFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }

    fun reveal() { controlsVisible = true; interaction++ }

    suspend fun savePosition(
        pos: Long = controller.positionMillis,
        duration: Long = controller.durationMillis,
    ) {
        val dur = duration.takeIf { it > 0 } ?: return
        if (pos > 5_000) {
            graph.playbackPositions.upsert(
                app.opentv.data.model.PlaybackPosition(
                    profileId = settings.activeProfileId.value,
                    mediaKey = mediaKey,
                    positionMillis = pos,
                    durationMillis = dur,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    val playback = BindPlayerPlayback(controller) {
        val pos = controller.positionMillis
        val dur = controller.durationMillis
        sessionPosition = pos
        CoroutineScope(Dispatchers.IO + kotlinx.coroutines.NonCancellable).launch { savePosition(pos, dur) }
    }
    val paused = !playback.playWhenReady

    // Keep the screen awake during playback — see the note in PlayerScreen; a film is exactly when
    // the screensaver must not fire. Window flag is the reliable path; keepScreenOn is a backstop.
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view.keepScreenOn = true
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view.keepScreenOn = false
            // Read where we are *before* releasing the player, and save on a scope that outlives
            // this screen: launching on [scope] and cancelling it on the next line meant the save
            // on exit never landed, so only the 15-second autosave ever reached the database.
            val pos = controller.positionMillis
            val dur = controller.durationMillis
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.NonCancellable).launch {
                savePosition(pos, dur)
                // Publish the final resume point once playback ends, not just on a 30-minute
                // timer. The worker waits for connectivity and does not require a running peer.
                app.opentv.sync.CloudSyncWorker.onOpen(context)
            }
            controller.release()
            scope.cancel()
        }
    }

    LaunchedEffect(mediaKey) {
        val resumeFrom = if (sessionPosition >= 0) sessionPosition else
            graph.playbackPositions.get(settings.activeProfileId.value, mediaKey)
                ?.takeIf { !it.isFinished }?.positionMillis ?: 0L
        // Downloaded? Play the file — works with no signal, and spares the provider connection.
        val url = graph.downloadRepository.localFile(mediaKey) ?: streamUrl
        controller.play(
            PlaybackRequest(
                url = url,
                title = title,
                userAgent = userAgent,
                startPositionMillis = resumeFrom,
                isLive = false,
            ),
            debounce = false,
        )
        while (isActive) {
            delay(15_000)
            savePosition()
        }
    }

    // Sleep timer: leave the film when the armed deadline passes (position is saved on dispose).
    val sleepDeadline by SleepTimer.deadline.collectAsState()
    LaunchedEffect(sleepDeadline) {
        val d = sleepDeadline ?: return@LaunchedEffect
        val wait = d - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        SleepTimer.clear()
        onBack()
    }

    // Poll position/duration for the seek bar while the film plays.
    LaunchedEffect(Unit) {
        while (isActive) {
            if (!scrubbing) {
                positionMs = controller.positionMillis.coerceAtLeast(0)
                sessionPosition = positionMs
                // A growing recording has no fixed length; the recorded-so-far extent (how far you
                // can skip ahead) is what's been read into the buffer.
                durationMs = if (growingRec) controller.bufferedPositionMillis.coerceAtLeast(0)
                else controller.durationMillis.takeIf { it > 0 } ?: 0
            }
            delay(500)
        }
    }

    // Auto-hide when playing and not paused/scrubbing and no picker open. A growing recording dips in
    // and out of Buffering as it rides the write head, so for that case Buffering counts as "playing"
    // here — otherwise a single hiccup would pin the transport bar on screen for the rest of the watch.
    LaunchedEffect(controlsVisible, interaction, state, paused, scrubbing, vodPanel, displayMenuOpen) {
        val activelyPlaying = state is PlaybackState.Playing ||
            (growingRec && state is PlaybackState.Buffering)
        if (controlsVisible && !displayMenuOpen && !paused && !scrubbing && vodPanel == VodPanel.NONE && activelyPlaying) {
            delay(5_000)
            controlsVisible = false
        }
    }

    LaunchedEffect(controlsVisible, vodPanel) {
        if (controlsVisible) {
            delay(40)
            runCatching { if (vodPanel != VodPanel.NONE) panelFocus.requestFocus() else barFocus.requestFocus() }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    BackHandler {
        val activelyPlaying = state is PlaybackState.Playing ||
            (growingRec && state is PlaybackState.Buffering)
        when {
            vodPanel != VodPanel.NONE -> vodPanel = VodPanel.NONE
            controlsVisible && activelyPlaying && !paused -> controlsVisible = false
            else -> onBack()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                when {
                    event.key == Key.Back || event.key == Key.Escape -> false
                    event.type == KeyEventType.KeyDown && !controlsVisible -> { reveal(); true }
                    event.type == KeyEventType.KeyDown -> { interaction++; false }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable()
            .pointerInput(touch) {
                // Touch devices take taps in PlayerGestureLayer (under the controls) instead.
                if (touch) return@pointerInput
                detectTapGestures { if (controlsVisible) controlsVisible = false else reveal() }
            },
    ) {
        controller.surface.Content(
            modifier = Modifier.fillMaxSize(),
            scale = VideoScale.fromStored(resizeMode),
            transparent = false,
        )

        // Touch: swipe right to skip forward, left to skip back (same step as the buttons), plus
        // brightness/volume drags. See PlayerGestureLayer.
        PlayerGestureLayer(
            enabled = touch && !inPip,
            dragsEnabled = vodPanel == VodPanel.NONE,
            onTap = { if (controlsVisible) controlsVisible = false else reveal() },
        ) { dir ->
            if (growingRec) seekRelative(dir * 15_000L)
            else if (dir > 0) controller.seekForward() else controller.seekBackward()
            reveal()
        }
        if (touch) ImmersiveSystemBars(hidden = !controlsVisible || inPip)

        if (!inPip) when (val current = state) {
            // With the controls up, the play button shows buffering instead.
            is PlaybackState.Buffering -> if (!controlsVisible) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Text(current.title, color = Color.White)
                }
            }

            is PlaybackState.Error ->
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(48.dp),
                    ) {
                        Text(current.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text(current.message, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { controller.retry() }) { Text(stringResource(R.string.common_try_again)) }
                    }
                }

            else -> Unit
        }

        if (!inPip) JaskTheme {
            AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    JaskPlayerScrims()
                    JaskPlayerTopBar(title, onBack,
                        Modifier.align(Alignment.TopCenter).windowInsetsPadding(PlayerChromeInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
                        overline = if (growingRec) ({
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.FiberManualRecord, null, tint = Color(0xFFE53935), modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.rec_watching_live_badge), style = MaterialTheme.typography.labelLarge, color = Color(0xFFFF8A80))
                            }
                        }) else null) {
                        JaskPlayerTool(Icons.Filled.ClosedCaption, stringResource(R.string.player_subtitles), active = vodPanel == VodPanel.SUBTITLES) {
                            vodPanel = if (vodPanel == VodPanel.SUBTITLES) VodPanel.NONE else VodPanel.SUBTITLES; interaction++
                        }
                        JaskPlayerTool(Icons.Filled.Audiotrack, stringResource(R.string.player_audio), active = vodPanel == VodPanel.AUDIO) {
                            vodPanel = if (vodPanel == VodPanel.AUDIO) VodPanel.NONE else VodPanel.AUDIO; interaction++
                        }
                        PlayerDisplayControls(settings, onMenuVisibilityChanged = { displayMenuOpen = it }) { interaction++ }
                        if (activity?.pipSupported() == true) JaskPlayerTool(Icons.Filled.PictureInPictureAlt, stringResource(R.string.player_pop_out)) {
                            activity.enterPipNow(); interaction++
                        }
                    }
                    if (vodPanel != VodPanel.NONE) TrackPanel(vodPanel, controller, tracks, panelFocus,
                        Modifier.align(Alignment.TopEnd).windowInsetsPadding(PlayerChromeInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                            .padding(top = 76.dp, end = 20.dp)) { vodPanel = VodPanel.NONE; interaction++ }
                    JaskTransport(paused, { controller.setPlaying(paused); interaction++ }, barFocus, Modifier.align(Alignment.Center),
                        buffering = state is PlaybackState.Buffering,
                        backIcon = Icons.Filled.FastRewind, onBack = { if (growingRec) seekRelative(-15_000) else controller.seekBackward(); interaction++ },
                        forwardIcon = Icons.Filled.FastForward, onForward = { if (growingRec) seekRelative(15_000) else controller.seekForward(); interaction++ })
                    JaskScrubber(positionMs, durationMs, onSeek = { controller.seekTo(it); positionMs = it; interaction++ },
                        Modifier.align(Alignment.BottomCenter).windowInsetsPadding(PlayerChromeInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                            .padding(horizontal = 28.dp, vertical = 18.dp),
                        onInteraction = { interaction++ }, onScrubbing = { scrubbing = it })
                }
            }
        }
    }
}

private enum class VodPanel { NONE, SUBTITLES, AUDIO }

@Composable
private fun TrackPanel(panel: VodPanel, controller: PlayerEngine, tracks: List<PlayerTrack>, firstFocus: FocusRequester,
    modifier: Modifier = Modifier, onDone: () -> Unit) {
    val trackType = if (panel == VodPanel.SUBTITLES) PlayerTrackType.TEXT else PlayerTrackType.AUDIO
    val tracksOfType = tracks.filter { it.type == trackType }
    val options = mutableListOf<Triple<String, Boolean, () -> Unit>>()
    if (panel == VodPanel.SUBTITLES) {
        val anySelected = tracksOfType.any { it.isSelected }
        options += Triple(stringResource(R.string.player_subtitles_off), !anySelected) { controller.disableText(); onDone() }
    }
    tracksOfType.filter { it.isSupported }.forEach { track ->
        options += Triple(vodTrackLabel(track.label, track.language, options.size), track.isSelected) { controller.selectTrack(track); onDone() }
    }
    JaskPlayerPanel(stringResource(if (panel == VodPanel.SUBTITLES) R.string.player_subtitles else R.string.player_audio), options,
        stringResource(R.string.player_none_available), firstFocus, modifier)
}

private fun vodTrackLabel(label: String?, language: String?, index: Int): String {
    if (!label.isNullOrBlank()) return label
    if (!language.isNullOrBlank() && language != "und") {
        return runCatching { java.util.Locale(language).displayLanguage.ifBlank { language } }.getOrDefault(language)
    }
    return "Track ${index + 1}"
}
