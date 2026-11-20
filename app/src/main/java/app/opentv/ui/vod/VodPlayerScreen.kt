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
import app.opentv.ui.player.JaskPlayerOverlayHost
import app.opentv.ui.player.JaskPlayerHeader
import app.opentv.ui.jaskRowEnds
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
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Layers
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
    var playOnOpen by remember { mutableStateOf(false) }
    val touch = LocalLayoutClass.current.isTouch
    var interaction by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val barFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    // Closing the Audio & subtitles menu hands focus back to its button.
    fun closeTracks() {
        vodPanel = VodPanel.NONE; interaction++
        scope.launch { delay(40); runCatching { audioFocus.requestFocus() } }
    }

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

    val playback = BindPlayerPlayback(controller, app.opentv.remote.NowPlaying.Item(mediaKey = mediaKey, title = title)) {
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
            }
            controller.release()
            scope.cancel()
        }
    }

    // The TV switches to a refresh rate that suits the video while this player is open.
    app.opentv.ui.player.JaskFrameRateMatch(controller, key = mediaKey, rewind = true)

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

    // Every copy of this film on the provider, same language first: the Version menu, and what to fall back on.
    var copies by remember { mutableStateOf<List<app.opentv.data.model.Movie>>(emptyList()) }
    var playingUrl by remember { mutableStateOf(streamUrl) }
    // Between a refused copy and the next one starting: no error flashing up in between.
    var switching by remember { mutableStateOf(false) }
    // Said instead of "no longer exists" when the provider account is busy elsewhere.
    var busyNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mediaKey) {
        val id = mediaKey.takeIf { it.startsWith("movie:") }?.removePrefix("movie:")?.toLongOrNull() ?: return@LaunchedEffect
        copies = kotlinx.coroutines.withContext(Dispatchers.IO) {
            val dao = graph.database.canonicalMovies()
            val raw = graph.database.movies().byId(id) ?: return@withContext emptyList()
            val tmdb = dao.match(raw.sourceId, raw.streamId)?.tmdbId ?: return@withContext emptyList()
            val language = raw.name.substringBefore(" - ", "").trim()
            dao.versions(tmdb).sortedByDescending { it.name.substringBefore(" - ", "").trim() == language }
        }
    }
    fun playCopy(copy: app.opentv.data.model.Movie) {
        playingUrl = copy.streamUrl
        controller.play(PlaybackRequest(url = copy.streamUrl, title = title, userAgent = userAgent,
            startPositionMillis = maxOf(positionMs, if (sessionPosition >= 0) sessionPosition else 0L), isLive = false), debounce = false)
    }
    val goneTried = remember { mutableSetOf<String>() }
    LaunchedEffect(state) {
        if (state is PlaybackState.Playing) { switching = false; busyNote = null; return@LaunchedEffect }
        val failed = state as? PlaybackState.Error ?: return@LaunchedEffect
        if (failed.httpCode !in setOf(404, 407, 410) || copies.isEmpty()) { switching = false; return@LaunchedEffect }
        goneTried += playingUrl
        // A provider allowing one stream at a time refuses every copy while that stream is in use: say so,
        // rather than trying each copy in turn.
        if (goneTried.size == 1) {
            val busy = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching {
                    val source = graph.database.sources().byId(copies.first().sourceId) ?: return@runCatching null
                    graph.xtreamApi.authenticate(source).takeIf { info ->
                        val max = info.maxConnections ?: return@takeIf false
                        max > 0 && (info.activeConnections ?: 0) >= max
                    }
                }.getOrNull()
            }
            if (busy != null) {
                busyNote = "Your provider allows ${busy.maxConnections} stream at a time, and it's in use on another device or app. " +
                    "Stop it there, then try again."
                switching = false
                return@LaunchedEffect
            }
        }
        val next = copies.firstOrNull { it.streamUrl !in goneTried }
        if (next == null) { switching = false; return@LaunchedEffect }
        switching = true
        android.util.Log.i("jaskPlayer", "copy gone (HTTP ${failed.httpCode}); trying another copy of the film")
        playCopy(next)
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
    LaunchedEffect(controlsVisible, interaction, state, paused, scrubbing, vodPanel, displayMenuOpen, playOnOpen) {
        val activelyPlaying = state is PlaybackState.Playing ||
            (growingRec && state is PlaybackState.Buffering)
        if (controlsVisible && !displayMenuOpen && !playOnOpen && !paused && !scrubbing && vodPanel == VodPanel.NONE && activelyPlaying) {
            delay(5_000)
            controlsVisible = false
        }
    }

    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(40)
            runCatching { barFocus.requestFocus() }
        } else {
            vodPanel = VodPanel.NONE
            runCatching { rootFocus.requestFocus() }
        }
    }
    LaunchedEffect(vodPanel) { if (vodPanel == VodPanel.TRACKS) { delay(40); runCatching { panelFocus.requestFocus() } } }

    // Back closes an open panel; otherwise it leaves the film at once, controls showing or not. (Hiding the
    // controls first made a second press necessary.) Open menus (display size, Play on) and remote scrubbing
    // take Back before this through their own handlers.
    fun handleBack() {
        when {
            vodPanel == VodPanel.TRACKS -> closeTracks()
            vodPanel != VodPanel.NONE -> vodPanel = VodPanel.NONE
            else -> onBack()
        }
    }
    BackHandler { handleBack() }
    // A remote's Back is taken here, before Compose sees it: with a control focused, Compose turns an unclaimed
    // Back into "move focus out" (to the focusable root below), which swallowed the first press and left no ring.
    // Down is consumed and the action runs on Up, as the system's own Back does. Menus and remote scrubbing still
    // get Back through their own handlers.
    var backArmed by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                val down = event.type == KeyEventType.KeyDown
                when {
                    event.key == Key.Back || event.key == Key.Escape -> when {
                        displayMenuOpen || playOnOpen || scrubbing -> { backArmed = false; false }
                        down -> { backArmed = true; true }
                        event.type == KeyEventType.KeyUp && backArmed -> { backArmed = false; handleBack(); true }
                        else -> false
                    }
                    // The remote's media keys act at once, controls up or not (Android TV convention).
                    down && (event.key == Key.MediaPlayPause || event.key == Key.MediaPlay || event.key == Key.MediaPause) -> {
                        controller.setPlaying(when (event.key) { Key.MediaPlay -> true; Key.MediaPause -> false; else -> !controller.status.value.isPlaying })
                        reveal(); true
                    }
                    down && (event.key == Key.MediaFastForward || event.key == Key.MediaSkipForward) -> {
                        if (growingRec) seekRelative(15_000L) else controller.seekForward(); reveal(); true
                    }
                    down && (event.key == Key.MediaRewind || event.key == Key.MediaSkipBackward) -> {
                        if (growingRec) seekRelative(-15_000L) else controller.seekBackward(); reveal(); true
                    }
                    // Controls hidden: left/right skip back/forward (and show where), as TV players do.
                    down && !controlsVisible && event.key == Key.DirectionLeft -> {
                        if (growingRec) seekRelative(-15_000L) else controller.seekBackward(); reveal(); true
                    }
                    down && !controlsVisible && event.key == Key.DirectionRight -> {
                        if (growingRec) seekRelative(15_000L) else controller.seekForward(); reveal(); true
                    }
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

            is PlaybackState.Error -> if (switching) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                } else
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
                        Text(busyNote ?: current.message, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        app.opentv.ui.settings.JaskPill(stringResource(R.string.common_try_again), primary = true) {
                            goneTried.clear(); busyNote = null; controller.retry()
                        }
                    }
                }

            else -> Unit
        }

        if (!inPip) JaskTheme { JaskPlayerOverlayHost {
            val reading by app.opentv.ui.player.rememberStreamReading(controller)
            val buffering = state is PlaybackState.Buffering
            AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    JaskPlayerScrims()
                    val top = Modifier.windowInsetsPadding(PlayerChromeInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                    val bottom = Modifier.windowInsetsPadding(PlayerChromeInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    // Title top-left; clock, "Ends at" and the quality badge top-right (design: player chrome canvas).
                    JaskPlayerHeader(title, Modifier.align(Alignment.TopCenter).then(top)
                            .padding(if (touch) androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 14.dp)
                                else androidx.compose.foundation.layout.PaddingValues(start = 48.dp, end = 48.dp, top = 36.dp)),
                        endsAtMillis = if (!growingRec && durationMs > 0) System.currentTimeMillis() + (durationMs - positionMs) else null,
                        reserveEndsAt = !growingRec,
                        quality = reading, infoOpen = vodPanel == VodPanel.INFO,
                        onQuality = { vodPanel = if (vodPanel == VodPanel.INFO) VodPanel.NONE else VodPanel.INFO; interaction++ },
                        leading = if (touch) ({ app.opentv.ui.JaskGlassCircle(Icons.AutoMirrored.Filled.ArrowBack, "Back", size = 40.dp, onClick = onBack) }) else null,
                        overline = if (growingRec) ({
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.FiberManualRecord, null, tint = Color(0xFFE53935), modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.rec_watching_live_badge), style = MaterialTheme.typography.labelLarge, color = Color(0xFFFF8A80))
                            }
                        }) else null, compact = touch) {
                        // Which copy of the film: language and quality, as the provider names them.
                        if (copies.size > 1) app.opentv.ui.player.JaskPlayerPill(Icons.Filled.Layers, "Version", {
                            vodPanel = if (vodPanel == VodPanel.COPIES) VodPanel.NONE else VodPanel.COPIES; interaction++
                        }, active = vodPanel == VodPanel.COPIES, plain = touch, header = !touch)
                        // Play on another device, from this exact spot. Touch: here in the header; TV: in the bottom row.
                        if (touch) app.opentv.ui.JaskPlayOn(null, onSent = onBack, onOpenChange = { playOnOpen = it }) { open ->
                            JaskPlayerTool(Icons.Filled.Cast, "Play on another device") { open(); interaction++ }
                        }
                        if (touch && activity?.pipSupported() == true) JaskPlayerTool(Icons.Filled.PictureInPictureAlt, stringResource(R.string.player_pop_out)) {
                            activity.enterPipNow(); interaction++
                        }
                    }
                    if (vodPanel == VodPanel.COPIES) JaskPlayerPanel("Version", copies.map { copy ->
                        Triple(copy.name, copy.streamUrl == playingUrl, { goneTried.clear(); busyNote = null; vodPanel = VodPanel.NONE; playCopy(copy) })
                    }, "", panelFocus, Modifier.align(Alignment.TopEnd).then(top).padding(top = if (touch) 64.dp else 150.dp, end = if (touch) 20.dp else 36.dp))
                    if (vodPanel == VodPanel.INFO) app.opentv.ui.player.JaskStreamInfoCard(reading, buffering,
                        Modifier.align(Alignment.TopEnd).then(top).padding(top = if (touch) 64.dp else 150.dp, end = if (touch) 20.dp else 36.dp))
                    // Touch: the big centre transport, under the thumb. TV keeps the middle of the picture clear.
                    // Not over an error: its message and Try again sit in the middle.
                    if (touch && state !is PlaybackState.Error) JaskTransport(paused, { controller.setPlaying(paused); interaction++ }, barFocus, Modifier.align(Alignment.Center),
                        buffering = buffering,
                        backIcon = Icons.Filled.FastRewind, onBack = { if (growingRec) seekRelative(-15_000) else controller.seekBackward(); interaction++ },
                        forwardIcon = Icons.Filled.FastForward, onForward = { if (growingRec) seekRelative(15_000) else controller.seekForward(); interaction++ })
                    Column(Modifier.align(Alignment.BottomCenter).then(bottom)
                        .padding(start = if (touch) 28.dp else 48.dp, end = if (touch) 28.dp else 48.dp, bottom = if (touch) 8.dp else 36.dp)) {
                        // Touch: one row, the scrubber between nothing on the left and the tools on the right.
                        if (touch) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                            JaskScrubber(positionMs, durationMs, onSeek = { controller.seekTo(it); positionMs = it; interaction++ },
                                Modifier.weight(1f).padding(end = 12.dp), onInteraction = { interaction++ }, onScrubbing = { scrubbing = it })
                            Box(Modifier.focusRequester(audioFocus)) {
                                JaskPlayerTool(Icons.Filled.ClosedCaption, "Audio & subtitles", active = vodPanel == VodPanel.TRACKS) {
                                    if (vodPanel == VodPanel.TRACKS) closeTracks() else { vodPanel = VodPanel.TRACKS; interaction++ }
                                }
                            }
                            PlayerDisplayControls(settings, onMenuVisibilityChanged = { displayMenuOpen = it }, size = 40.dp) { interaction++ }
                        } else {
                        JaskScrubber(positionMs, durationMs, onSeek = { controller.seekTo(it); positionMs = it; interaction++ },
                            onInteraction = { interaction++ }, onScrubbing = { scrubbing = it })
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth().then(if (touch) Modifier else Modifier.jaskRowEnds()),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(if (touch) 4.dp else 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (!touch) {
                                app.opentv.ui.player.JaskPlayPausePill(paused, buffering, barFocus) { controller.setPlaying(paused); interaction++ }
                                app.opentv.ui.player.JaskPlayerCircle(Icons.Filled.FastRewind, "Back 15 seconds") {
                                    if (growingRec) seekRelative(-15_000) else controller.seekBackward(); interaction++
                                }
                                app.opentv.ui.player.JaskPlayerCircle(Icons.Filled.FastForward, "Forward 15 seconds") {
                                    if (growingRec) seekRelative(15_000) else controller.seekForward(); interaction++
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Box(Modifier.focusRequester(audioFocus)) {
                                app.opentv.ui.player.JaskPlayerPill(Icons.Filled.ClosedCaption, "Audio & subtitles", {
                                    if (vodPanel == VodPanel.TRACKS) closeTracks() else { vodPanel = VodPanel.TRACKS; interaction++ }
                                }, active = vodPanel == VodPanel.TRACKS, plain = touch)
                            }
                            if (!touch) app.opentv.ui.JaskPlayOn(null, onSent = onBack, onOpenChange = { playOnOpen = it }) { open ->
                                app.opentv.ui.player.JaskPlayerCircle(Icons.Filled.Cast, "Play on another device") { open(); interaction++ }
                            }
                            PlayerDisplayControls(settings, onMenuVisibilityChanged = { displayMenuOpen = it }) { interaction++ }
                        }
                        }
                    }
                    if (vodPanel == VodPanel.TRACKS) app.opentv.ui.player.JaskTracksMenu(controller, tracks, panelFocus,
                        Modifier.align(Alignment.BottomEnd).then(bottom).padding(end = if (touch) 28.dp else 48.dp, bottom = if (touch) 64.dp else 112.dp)) { closeTracks() }
                }
            }
        } }
    }
}

private enum class VodPanel { NONE, TRACKS, INFO, COPIES }
