/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.desktop.mpv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.opentv.player.PlaybackRequest
import app.opentv.player.PlaybackState
import app.opentv.player.PlaybackStatus
import app.opentv.player.PlayerEngine
import app.opentv.player.PlayerSurfaceHost
import app.opentv.player.PlayerTrack
import app.opentv.player.PlayerTrackType
import app.opentv.player.VideoScale
import app.opentv.player.VideoSizeInfo
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desktop [PlayerEngine] backed by libmpv.
 *
 * ## Rendering
 *
 * Frames are fetched through mpv's software render API (`MPV_RENDER_API_TYPE_SW`) into a BGRA
 * buffer, turned into a Skia image and drawn by Compose. That keeps the video inside the Compose
 * scene, so the player chrome can sit on top of it — the thing a native child window (`--wid`)
 * cannot do.
 *
 * The cost is one CPU copy per frame. That is deliberate for the first desktop build: it is
 * backend-agnostic (works on Skia's OpenGL, Metal and software backends) and works on all three
 * desktop OSes without per-platform GL context code. The upgrade path is mpv's OpenGL render API
 * into a texture Skia can sample, which removes the copy; see the handoff.
 *
 * ## Threads
 *
 * - The event thread blocks in `mpv_wait_event` and publishes state, position and tracks.
 * - The render thread owns the render context and is the only caller of
 *   `mpv_render_context_render`.
 * - Compose only reads `frames` and `videoSize`.
 */
class MpvPlayerEngine : PlayerEngine {

    private val lib: MpvLibrary = MpvLibrary.instance
    private val handle: Pointer = checkNotNull(lib.mpv_create()) { "mpv_create() returned null" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _tracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    override val tracks: StateFlow<List<PlayerTrack>> = _tracks.asStateFlow()

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    /** Latest rendered frame, for the desktop surface host. */
    val frames = MutableStateFlow<ImageBitmap?>(null)

    /** Coded video size, so the surface can letterbox before the first frame arrives. */
    val videoSize = MutableStateFlow(VideoSizeInfo(0, 0))

    override val surface: PlayerSurfaceHost = MpvSurfaceHost(this)

    @Volatile private var current: PlaybackRequest? = null
    @Volatile private var playJob: Job? = null

    @Volatile private var loaded = false
    @Volatile private var paused = false
    @Volatile private var cachePaused = false
    @Volatile private var seekable = false
    @Volatile private var currentPositionMillis = 0L
    @Volatile private var currentDurationMillis = 0L
    @Volatile private var bufferedMillis = 0L
    @Volatile private var volumeLevel = 1f
    @Volatile private var pendingSeekMillis = 0L
    @Volatile private var lastError: String? = null
    @Volatile private var renderError: String? = null

    @Volatile private var running = true
    @Volatile private var renderContext: Pointer? = null

    @Volatile private var viewportWidth = 0
    @Volatile private var viewportHeight = 0
    private val redrawRequested = AtomicBoolean(true)

    /**
     * mpv keeps the function pointer after the call returns, so the JNA callback object must be
     * reachable for as long as the render context exists. Passing it inline would let the GC
     * collect it and turn the next frame notification into a crash.
     */
    private val renderUpdateCallback = object : MpvRenderUpdateCallback {
        override fun invoke(callbackData: Pointer?) {
            redrawRequested.set(true)
        }
    }

    private val eventThread: Thread
    private val renderThread: Thread

    init {
        configure()
        val rc = lib.mpv_initialize(handle)
        check(rc >= 0) { "mpv_initialize failed: ${lib.mpv_error_string(rc)}" }

        eventThread = Thread(::eventLoop, "mpv-events").apply {
            isDaemon = true
            start()
        }
        renderThread = Thread(::renderLoop, "mpv-render").apply {
            isDaemon = true
            start()
        }
    }

    private fun configure() {
        // No terminal OSD and no keys: this is an embedded player, Compose owns input.
        option("terminal", "no")
        option("input-default-bindings", "no")
        option("input-vo-keyboard", "no")
        option("osc", "no")
        option("osd-level", "0")
        // The render API's video output. Without this mpv opens its own window.
        option("vo", "libmpv")
        option("hwdec", "auto-safe")
        option("keep-open", "no")
        option("idle", "yes")
        option("force-window", "no")
        // IPTV sources are twitchy; a real cache is the difference between a hiccup and a stall.
        option("cache", "yes")
        option("cache-secs", "30")
        option("demuxer-max-bytes", "128MiB")
        option("demuxer-max-back-bytes", "32MiB")
        option("network-timeout", "15")
        option("audio-display", "no")
        option("sub-auto", "fuzzy")
        option("audio-file-auto", "fuzzy")

        lib.mpv_request_log_messages(handle, "error")
    }

    private fun option(name: String, value: String) {
        lib.mpv_set_option_string(handle, name, value)
    }

    // ---------------------------------------------------------------- playback

    override fun play(request: PlaybackRequest, debounce: Boolean) {
        playJob?.cancel()
        pendingSeekMillis = if (request.isLive) 0L else request.startPositionMillis
        playJob = scope.launch {
            if (debounce) delay(SWITCH_DEBOUNCE_MILLIS)

            current = request
            lastError = null
            loaded = false
            cachePaused = false
            _state.value = PlaybackState.Buffering(request.title)

            // Providers reject clients they do not recognise, so the UA has to reach mpv's HTTP
            // layer before the load starts.
            lib.mpv_set_property_string(
                handle,
                "http-header-fields",
                "User-Agent: ${request.userAgent}",
            )

            command("loadfile", request.url, "replace")
        }
    }

    override fun retry() {
        current?.let { play(it, debounce = false) }
    }

    override fun setPlaying(playing: Boolean) {
        lib.mpv_set_property_string(handle, "pause", if (playing) "no" else "yes")
    }

    override fun stop() {
        playJob?.cancel()
        current = null
        loaded = false
        frames.value = null
        _tracks.value = emptyList()
        _status.value = PlaybackStatus()
        _state.value = PlaybackState.Idle
        command("stop")
    }

    override fun release() {
        if (!running) return
        running = false
        playJob?.cancel()
        scope.cancel()
        renderThread.join(2_000)
        lib.mpv_terminate_destroy(handle)
    }

    // ---------------------------------------------------------------- transport

    override val isSeekable: Boolean get() = seekable
    override val positionMillis: Long get() = currentPositionMillis
    override val durationMillis: Long get() = currentDurationMillis
    override val bufferedPositionMillis: Long get() = bufferedMillis
    override val volume: Float get() = volumeLevel

    override fun seekTo(positionMillis: Long) {
        command("seek", (positionMillis.coerceAtLeast(0L) / 1000.0).toString(), "absolute")
    }

    override fun seekBy(deltaMillis: Long) {
        if (!seekable) return
        seekTo(currentPositionMillis + deltaMillis)
    }

    override fun seekBackward() {
        if (seekable) command("seek", "-5", "relative")
    }

    override fun seekForward() {
        if (seekable) command("seek", "5", "relative")
    }

    override fun setVolume(volume: Float) {
        lib.mpv_set_property_string(handle, "volume", (volume.coerceIn(0f, 1f) * 100f).toString())
    }

    // ---------------------------------------------------------------- tracks

    override fun setSubtitlesEnabled(enabled: Boolean) {
        if (!enabled) {
            disableText()
            return
        }
        val first = _tracks.value.firstOrNull {
            it.type == PlayerTrackType.TEXT && it.isSupported
        }
        if (first != null) selectTrack(first) else setTrack("sid", "auto")
    }

    override fun selectTrack(track: PlayerTrack) {
        when (track.type) {
            PlayerTrackType.AUDIO -> setTrack("aid", track.index.toString())
            PlayerTrackType.TEXT -> setTrack("sid", track.index.toString())
            PlayerTrackType.VIDEO -> setTrack("vid", track.index.toString())
        }
    }

    override fun disableText() = setTrack("sid", "no")

    private fun setTrack(property: String, value: String) {
        lib.mpv_set_property_string(handle, property, value)
    }

    // ---------------------------------------------------------------- mpv plumbing

    private fun command(vararg args: String) {
        val strings = args.map { Memory(it.length + 1L).apply { setString(0, it) } }
        val array = Memory((args.size + 1L) * Native.POINTER_SIZE)
        strings.forEachIndexed { index, pointer ->
            array.setPointer(index.toLong() * Native.POINTER_SIZE, pointer)
        }
        array.setPointer(args.size.toLong() * Native.POINTER_SIZE, null)
        lib.mpv_command(handle, array)
    }

    private fun propertyDouble(name: String): Double? {
        val memory = Memory(8)
        return if (lib.mpv_get_property(handle, name, Mpv.FORMAT_DOUBLE, memory) >= 0) {
            memory.getDouble(0)
        } else {
            null
        }
    }

    private fun propertyLong(name: String): Long? {
        val memory = Memory(8)
        return if (lib.mpv_get_property(handle, name, Mpv.FORMAT_INT64, memory) >= 0) {
            memory.getLong(0)
        } else {
            null
        }
    }

    private fun propertyFlag(name: String): Boolean? {
        val memory = Memory(4)
        return if (lib.mpv_get_property(handle, name, Mpv.FORMAT_FLAG, memory) >= 0) {
            memory.getInt(0) != 0
        } else {
            null
        }
    }

    private fun propertyString(name: String): String? {
        val pointer = lib.mpv_get_property_string(handle, name) ?: return null
        return try {
            pointer.getString(0)
        } finally {
            lib.mpv_free(pointer)
        }
    }

    private fun eventLoop() {
        while (running) {
            val eventPointer = lib.mpv_wait_event(handle, 0.1) ?: continue
            val event = MpvEvent(eventPointer)
            event.read()

            when (event.eventId) {
                Mpv.EVENT_START_FILE -> {
                    loaded = false
                    _state.value = PlaybackState.Buffering(current?.title.orEmpty())
                }

                Mpv.EVENT_FILE_LOADED -> {
                    loaded = true
                    val seekTo = pendingSeekMillis
                    pendingSeekMillis = 0
                    if (seekTo > 0) seekTo(seekTo)
                    refreshTracks()
                }

                Mpv.EVENT_TRACKS_CHANGED -> refreshTracks()

                Mpv.EVENT_END_FILE -> {
                    val reason = event.data?.getInt(0) ?: Mpv.END_FILE_EOF
                    val error = event.data?.getInt(4) ?: 0
                    loaded = false
                    frames.value = null
                    _status.value = PlaybackStatus()
                    when (reason) {
                        Mpv.END_FILE_ERROR -> _state.value = PlaybackState.Error(
                            title = current?.title.orEmpty(),
                            message = lastError ?: lib.mpv_error_string(error),
                            canRetry = true,
                        )

                        Mpv.END_FILE_EOF, Mpv.END_FILE_STOP ->
                            if (current?.isLive != true) _state.value = PlaybackState.Idle

                        else -> Unit
                    }
                }

                Mpv.EVENT_LOG_MESSAGE -> {
                    // struct mpv_event_log_message { char *prefix; char *level; char *text; ... }
                    val textPointer = event.data?.getPointer(16)
                    val text = textPointer?.getString(0)
                    if (!text.isNullOrBlank()) lastError = text.trim()
                }

                Mpv.EVENT_SHUTDOWN -> running = false
            }

            pollPlayback()
        }
    }

    private fun pollPlayback() {
        currentPositionMillis = ((propertyDouble("time-pos") ?: 0.0) * 1000).toLong()
        currentDurationMillis = ((propertyDouble("duration") ?: 0.0) * 1000).toLong()
        bufferedMillis = ((propertyDouble("demuxer-cache-time") ?: 0.0) * 1000).toLong()
            .coerceAtLeast(currentPositionMillis)
        volumeLevel = ((propertyDouble("volume") ?: 100.0) / 100.0).toFloat()
        seekable = propertyFlag("seekable") ?: false
        paused = propertyFlag("pause") ?: false
        cachePaused = propertyFlag("paused-for-cache") ?: false

        val width = propertyLong("width")?.toInt() ?: 0
        val height = propertyLong("height")?.toInt() ?: 0
        val size = VideoSizeInfo(width, height)
        if (videoSize.value != size) videoSize.value = size

        val playing = loaded && !paused && !cachePaused
        _status.value = PlaybackStatus(
            isPlaying = playing,
            playWhenReady = loaded && !paused,
            videoSize = size,
        )

        if (loaded) {
            val title = current?.title.orEmpty()
            val next = if (cachePaused) PlaybackState.Buffering(title) else PlaybackState.Playing(title)
            if (_state.value != next && _state.value !is PlaybackState.Error) {
                _state.value = next
            }
        }
    }

    private fun refreshTracks() {
        val json = propertyString("track-list") ?: return
        _tracks.value = runCatching { parseTracks(json) }.getOrDefault(emptyList())
    }

    private fun parseTracks(json: String): List<PlayerTrack> {
        var videos = 0
        var audios = 0
        var texts = 0
        return Json.parseToJsonElement(json).jsonArray.mapNotNull { element ->
            val obj = element.jsonObject
            val type = when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "video" -> PlayerTrackType.VIDEO
                "audio" -> PlayerTrackType.AUDIO
                "sub" -> PlayerTrackType.TEXT
                else -> return@mapNotNull null
            }
            val groupIndex = when (type) {
                PlayerTrackType.VIDEO -> videos++
                PlayerTrackType.AUDIO -> audios++
                PlayerTrackType.TEXT -> texts++
            }
            PlayerTrack(
                type = type,
                groupIndex = groupIndex,
                index = obj["id"]?.jsonPrimitive?.intOrNull ?: 0,
                label = obj["title"]?.jsonPrimitive?.contentOrNull
                    ?: obj["codec"]?.jsonPrimitive?.contentOrNull,
                language = obj["lang"]?.jsonPrimitive?.contentOrNull,
                isSelected = obj["selected"]?.jsonPrimitive?.booleanOrNull ?: false,
                isSupported = true,
            )
        }
    }

    // ---------------------------------------------------------------- rendering

    /** Called by the surface when the composable's pixel size changes. */
    internal fun setViewport(width: Int, height: Int) {
        // Render at most 1920px on the long edge: the CPU frame copy is the expensive part, and
        // Compose upscales the result on the GPU when the window is larger.
        val longest = maxOf(width, height)
        val factor = if (longest > MAX_RENDER_EDGE) MAX_RENDER_EDGE.toDouble() / longest else 1.0
        val w = ((width * factor).toInt() and 0x7FFFFFFE).coerceAtLeast(2)
        val h = ((height * factor).toInt() and 0x7FFFFFFE).coerceAtLeast(2)
        if (w != viewportWidth || h != viewportHeight) {
            viewportWidth = w
            viewportHeight = h
            redrawRequested.set(true)
        }
    }

    /**
     * Fit/crop/stretch is applied by mpv rather than by Compose, because mpv already owns the
     * buffer it renders into and can scale without a second pass.
     */
    internal fun setVideoScale(scale: VideoScale, width: Int, height: Int) {
        val w = width.takeIf { it > 0 } ?: viewportWidth
        val h = height.takeIf { it > 0 } ?: viewportHeight
        when (scale) {
            VideoScale.FIT -> {
                setProperty("panscan", "0")
                setProperty("video-aspect-override", "no")
            }

            VideoScale.CROP -> {
                setProperty("panscan", "1")
                setProperty("video-aspect-override", "no")
            }

            VideoScale.STRETCH -> if (w > 0 && h > 0) {
                setProperty("panscan", "0")
                setProperty("video-aspect-override", "$w:$h")
            }
        }
        redrawRequested.set(true)
    }

    private fun setProperty(name: String, value: String) {
        lib.mpv_set_property_string(handle, name, value)
    }

    private fun renderLoop() {
        val result = PointerByReference()
        val apiType = Memory(8).apply { setString(0, "sw") }
        val params = arrayOf(
            MpvRenderParam(Mpv.RENDER_PARAM_API_TYPE, apiType),
            MpvRenderParam(0, null),
        )
        val array = MpvRenderParam().toArray(params.size) as Array<MpvRenderParam>
        array.indices.forEach { index ->
            array[index].type = params[index].type
            array[index].data = params[index].data
            array[index].write()
        }

        val rc = lib.mpv_render_context_create(result, handle, array[0].pointer)
        if (rc < 0) {
            lastError = "Could not create the mpv render context: ${lib.mpv_error_string(rc)}"
            _state.value = PlaybackState.Error("", lastError!!, canRetry = false)
            return
        }

        val context = result.value
        renderContext = context

        lib.mpv_render_context_set_update_callback(
            context,
            renderUpdateCallback,
            null,
        )

        var buffer: Memory? = null
        var bufferWidth = 0
        var bufferHeight = 0
        var lastRenderNanos = 0L
        var loggedFirstFrame = false

        while (running) {
            val width = viewportWidth
            val height = viewportHeight
            val now = System.nanoTime()
            // The update callback is the intended trigger, but if it never reaches JNA the
            // surface stays black while mpv reports Playing. A ~60fps timer while a file is
            // loaded keeps frames flowing either way; mpv returns the current frame when there
            // is no new one.
            val due = redrawRequested.getAndSet(false) ||
                (loaded && now - lastRenderNanos >= FRAME_INTERVAL_NANOS)
            if (due && width > 0 && height > 0) {
                lastRenderNanos = now
                if (buffer == null || bufferWidth != width || bufferHeight != height) {
                    buffer = Memory(width.toLong() * height * 4L)
                    bufferWidth = width
                    bufferHeight = height
                }
                val frame = renderFrame(context, buffer, width, height)
                if (frame != null) {
                    frames.value = frame
                    if (!loggedFirstFrame) {
                        loggedFirstFrame = true
                        println("mpv: first frame ${frame.width}x${frame.height}")
                    }
                }
            }
            Thread.sleep(8)
        }

        lib.mpv_render_context_free(context)
        renderContext = null
    }

    private fun renderFrame(
        context: Pointer,
        buffer: Memory,
        width: Int,
        height: Int,
    ): ImageBitmap? {
        val size = Memory(8).apply {
            setInt(0, width)
            setInt(4, height)
        }
        val format = Memory(8).apply { setString(0, "bgra") }
        val stride = Memory(8).apply { setLong(0, width * 4L) }

        val values = arrayOf(
            MpvRenderParam(Mpv.RENDER_PARAM_SW_SIZE, size),
            MpvRenderParam(Mpv.RENDER_PARAM_SW_FORMAT, format),
            MpvRenderParam(Mpv.RENDER_PARAM_SW_STRIDE, stride),
            MpvRenderParam(Mpv.RENDER_PARAM_SW_POINTER, buffer),
            MpvRenderParam(0, null),
        )
        val array = MpvRenderParam().toArray(values.size) as Array<MpvRenderParam>
        array.indices.forEach { index ->
            array[index].type = values[index].type
            array[index].data = values[index].data
            array[index].write()
        }

        val rc = lib.mpv_render_context_render(context, array[0].pointer)
        if (rc < 0) {
            val message = lib.mpv_error_string(rc)
            if (renderError != message) {
                renderError = message
                System.err.println("mpv: render failed: $message")
            }
            return null
        }

        // One copy out of native memory, then Skia takes ownership of the bytes.
        val bytes = ByteArray(width * height * 4)
        buffer.read(0, bytes, 0, bytes.size)
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        return Image.makeRaster(info, bytes, width * 4).toComposeImageBitmap()
    }

    private companion object {
        const val SWITCH_DEBOUNCE_MILLIS = 250L
        const val MAX_RENDER_EDGE = 1920
        const val FRAME_INTERVAL_NANOS = 16_000_000L
    }
}

/** Compose surface for [MpvPlayerEngine]; draws frames and letterboxes them. */
internal class MpvSurfaceHost(
    private val engine: MpvPlayerEngine,
) : PlayerSurfaceHost {

    @Composable
    override fun Content(modifier: Modifier, scale: VideoScale, transparent: Boolean) {
        MpvVideo(modifier = modifier, scale = scale, engine = engine)
    }
}
