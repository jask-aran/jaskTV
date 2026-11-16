/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Owns the single ExoPlayer instance and everything about switching what it is playing.
 *
 * This is the Android implementation of [PlayerEngine]. Screens talk to the interface; swapping in
 * VLC on a Mac or AVPlayer on iOS means writing another implementation of that interface, not
 * rewriting the player screens. The debounce/retry policy below is still ExoPlayer-shaped (it uses
 * Exo's load-error policy and restarts), so it will move into a platform-neutral coordinator when
 * the second engine lands; until then this class is both the engine and the coordinator.
 *
 * ## Why one player, reused
 *
 * The obvious implementation of "change channel" is release the player and build a new one.
 * On a low-end TV box that takes long enough that a user pressing channel-up four times in a
 * row can queue four constructions and four teardowns, and the codec ends up in a state where
 * nothing plays until the app is killed. That is the "changing channels too quickly causes
 * streams to fail" class of bug, and it is entirely self-inflicted.
 *
 * OpenTV keeps exactly one player for the lifetime of the screen and only ever swaps its
 * media item. Requests are debounced, and an in-flight switch is cancelled the moment a newer
 * one arrives, so holding channel-up costs one actual tune — the one the user stopped on.
 */
@OptIn(UnstableApi::class)
class PlayerController(
    context: Context,
    private val scope: CoroutineScope,
    httpClient: OkHttpClient,
    subtitlesEnabled: Boolean = true,
    /**
     * Tunes the player for the guide's muted preview pane: a shallow start buffer so a highlighted
     * channel shows a frame quickly, and a longer switch debounce so scrolling the channel list
     * does not tune to every channel passed over. The full-screen players leave this false and keep
     * the deep buffer that rides out a twitchy IPTV source.
     */
    private val preview: Boolean = false,
    /**
     * Turns the live player into a shallow DVR: a retained back-buffer so the viewer can pause and
     * rewind live TV within the last few minutes. Off by default because holding minutes of video
     * in memory is a real cost on a cheap box; the recording player and preview never set it.
     */
    private val dvr: Boolean = false,
    /**
     * When set, `smb://` media (a recording on a NAS) is read through this source so it plays and
     * seeks in-app. Null for the live/VOD players, which never see an smb URI.
     */
    smbDataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null,
    /**
     * When set, `optvrec://<id>` media (a recording still being written) is read through this
     * tail-following source so it can be watched while it records. Null everywhere except the
     * recording player.
     */
    growingDataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null,
    /**
     * Playing a recording that is still being written changes the buffer targets: hold playback a
     * cushion behind the file's growing edge instead of right on it.
     */
    private val liveRecording: Boolean = false,
) : PlayerEngine {

    private val switchDebounceMillis = if (preview) PREVIEW_SWITCH_DEBOUNCE_MILLIS else SWITCH_DEBOUNCE_MILLIS

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Media3's own track model, kept private for selection; the UI sees [PlayerTrack]. */
    private val _mediaTracks = MutableStateFlow(Tracks.EMPTY)

    /** The current stream's tracks (audio/text/video), for the in-player pickers. */
    private val _tracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    override val tracks: StateFlow<List<PlayerTrack>> = _tracks.asStateFlow()

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    override val surface: PlayerSurfaceHost by lazy { ExoPlayerSurfaceHost(this) }

    private var switchJob: Job? = null
    private var current: PlaybackRequest? = null
    private var consecutiveFailures = 0

    /**
     * Held so the User-Agent can be swapped per source before each tune.
     *
     * Providers commonly 403 any client whose UA they do not recognise, and the workaround —
     * "try a different User-Agent" — is useless unless the setting actually reaches the
     * player's HTTP layer. Setting it on the factory applies it to data sources created for
     * subsequent loads, which is exactly the granularity we need.
     */
    private val httpFactory = OkHttpDataSource.Factory(httpClient).apply {
        setDefaultRequestProperties(mapOf("User-Agent" to DEFAULT_USER_AGENT))
    }

    private val dataSourceFactory: androidx.media3.datasource.DataSource.Factory =
        DefaultDataSource.Factory(context, httpFactory).let { default ->
            val custom = buildMap<String, androidx.media3.datasource.DataSource.Factory> {
                if (smbDataSourceFactory != null) put("smb", smbDataSourceFactory)
                if (growingDataSourceFactory != null) put("optvrec", growingDataSourceFactory)
            }
            if (custom.isEmpty()) default else RoutingDataSourceFactory(default, custom)
        }

    /**
     * Retry policy tuned for IPTV rather than for CDNs.
     *
     * A provider under load returns 403, 429 or 500 for a few seconds and then serves the
     * stream perfectly well. ExoPlayer's default is to give up almost immediately on those,
     * which surfaces to the user as a channel that "doesn't work" but plays fine on the
     * second try. We retry them with backoff instead. 401 and 404 are not retried — those
     * genuinely will not fix themselves.
     */
    private val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val statusCode =
                (info.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode

            return when (statusCode) {
                401, 404, 410 -> C_TIME_UNSET
                403, 405, 429, 500, 502, 503, 504 -> backoffFor(info.errorCount)
                null -> backoffFor(info.errorCount) // network blips
                else -> super.getRetryDelayMsFor(info)
            }
        }

        override fun getMinimumLoadableRetryCount(dataType: Int): Int = MAX_LOAD_RETRIES

        private fun backoffFor(errorCount: Int): Long =
            if (errorCount > MAX_LOAD_RETRIES) C_TIME_UNSET
            else minOf(INITIAL_BACKOFF_MILLIS * (1L shl (errorCount - 1)), MAX_BACKOFF_MILLIS)
    }

    /**
     * Held so captions can be turned on and off at runtime. Prefer the device language when
     * captions are on; [setSubtitlesEnabled] flips the text renderer off entirely.
     */
    private val trackSelector = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters()
            .setPreferredTextLanguage(java.util.Locale.getDefault().language)
            .setSelectUndeterminedTextLanguage(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
            .build()
    }

    /** Android-only escape hatch for the surface host. Screens must not use this. */
    internal val player: ExoPlayer = ExoPlayer.Builder(context)
        .setSeekBackIncrementMs(SEEK_INCREMENT_MILLIS)
        .setSeekForwardIncrementMs(SEEK_INCREMENT_MILLIS)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(loadErrorPolicy),
        )
        .setTrackSelector(trackSelector)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    if (preview) PREVIEW_MIN_BUFFER_MILLIS else MIN_BUFFER_MILLIS,
                    when {
                        preview -> PREVIEW_MAX_BUFFER_MILLIS
                        dvr -> DVR_MAX_BUFFER_MILLIS
                        liveRecording -> LIVE_REC_MAX_BUFFER_MILLIS
                        else -> MAX_BUFFER_MILLIS
                    },
                    when {
                        preview -> PREVIEW_BUFFER_FOR_PLAYBACK_MILLIS
                        liveRecording -> LIVE_REC_BUFFER_FOR_PLAYBACK_MILLIS
                        else -> BUFFER_FOR_PLAYBACK_MILLIS
                    },
                    when {
                        preview -> PREVIEW_BUFFER_AFTER_REBUFFER_MILLIS
                        liveRecording -> LIVE_REC_BUFFER_AFTER_REBUFFER_MILLIS
                        else -> BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MILLIS
                    },
                )
                // Retain the last few minutes so pause/rewind of live TV has something to seek into.
                .apply { if (dvr) setBackBuffer(DVR_BACK_BUFFER_MILLIS, true) }
                .build(),
        )
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    _mediaTracks.value = tracks
                    _tracks.value = tracks.toPlayerTracks()
                    publishStatus()
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    publishStatus()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val title = current?.title.orEmpty()
                    _state.value = when (playbackState) {
                        Player.STATE_BUFFERING -> PlaybackState.Buffering(title)
                        Player.STATE_READY -> {
                            consecutiveFailures = 0
                            PlaybackState.Playing(title)
                        }
                        Player.STATE_IDLE -> _state.value
                        Player.STATE_ENDED -> PlaybackState.Idle
                        else -> _state.value
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    consecutiveFailures++
                    val request = current
                    _state.value = PlaybackState.Error(
                        title = request?.title.orEmpty(),
                        message = PlaybackErrors.describe(error),
                        canRetry = consecutiveFailures < MAX_AUTO_RESTARTS,
                    )
                    // One silent restart covers the common case of a provider dropping the
                    // connection when another device on the account starts streaming.
                    if (consecutiveFailures < MAX_AUTO_RESTARTS && request != null) {
                        scope.launch {
                            delay(AUTO_RESTART_DELAY_MILLIS)
                            if (current == request) play(request, debounce = false)
                        }
                    }
                }
            })
        }

    override fun play(request: PlaybackRequest, debounce: Boolean) {
        // Cancelling here is what makes fast channel-changing safe: the previous switch never
        // reaches the player, so we never stack prepares.
        switchJob?.cancel()
        current = request

        switchJob = scope.launch {
            if (debounce) delay(switchDebounceMillis)

            consecutiveFailures = 0
            httpFactory.setDefaultRequestProperties(mapOf("User-Agent" to request.userAgent))
            _state.value = PlaybackState.Buffering(request.title)

            val mediaItem = MediaItem.Builder()
                .setUri(request.url)
                .apply {
                    // Only meaningful for live streams; setting it on VOD skews seeking.
                    if (request.isLive) {
                        setLiveConfiguration(
                            MediaItem.LiveConfiguration.Builder()
                                .setTargetOffsetMs(LIVE_TARGET_OFFSET_MILLIS)
                                .build(),
                        )
                    }
                }
                .build()

            with(player) {
                // stop() rather than release(): keeps the codec and the surface alive.
                stop()
                clearMediaItems()
                setMediaItem(mediaItem)
                if (!request.isLive && request.startPositionMillis > 0) {
                    seekTo(request.startPositionMillis)
                }
                playWhenReady = true
                prepare()
            }
        }
    }

    override fun retry() {
        current?.let { play(it, debounce = false) }
    }

    override fun setPlaying(playing: Boolean) {
        player.playWhenReady = playing
    }

    /**
     * The quick captions toggle. On is *not* merely "allow the text renderer" — that leaves it
     * to the selector to guess a language, which for a lot of IPTV streams guesses nothing and
     * the user sees no captions even though they turned them on. So On explicitly selects the
     * first available subtitle track; Off disables text entirely.
     */
    override fun setSubtitlesEnabled(enabled: Boolean) {
        if (!enabled) {
            disableText()
            return
        }
        val firstText = _tracks.value.firstOrNull {
            it.type == PlayerTrackType.TEXT && it.isSupported
        }
        if (firstText != null) {
            selectTrack(firstText)
        } else {
            // Nothing to select yet — allow the renderer so a track that arrives later can be
            // picked up, and re-assert once tracks change.
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        }
    }

    /** Force a specific audio or text track on (used by the in-player pickers). */
    override fun selectTrack(track: PlayerTrack) {
        val media3Type = track.type.toMedia3Type()
        val group = _mediaTracks.value.groups
            .filter { it.type == media3Type }
            .getOrNull(track.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.index))
            .setTrackTypeDisabled(media3Type, false)
            .build()
    }

    /** Turn subtitles off entirely. */
    override fun disableText() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    override val isSeekable: Boolean get() = player.isCurrentMediaItemSeekable
    override val positionMillis: Long get() = player.currentPosition
    override val durationMillis: Long get() = player.duration
    override val bufferedPositionMillis: Long get() = player.bufferedPosition
    override val volume: Float get() = player.volume

    override fun seekTo(positionMillis: Long) {
        player.seekTo(positionMillis.coerceAtLeast(0L))
    }

    override fun seekBy(deltaMillis: Long) {
        if (!isSeekable) return
        seekTo((positionMillis + deltaMillis).coerceAtLeast(0L))
    }

    override fun seekBackward() {
        if (player.isCurrentMediaItemSeekable) player.seekBack()
    }

    override fun seekForward() {
        if (player.isCurrentMediaItemSeekable) player.seekForward()
    }

    override fun setVolume(volume: Float) {
        player.volume = volume.coerceIn(0f, 1f)
    }

    override fun stop() {
        switchJob?.cancel()
        current = null
        player.stop()
        player.clearMediaItems()
        _mediaTracks.value = Tracks.EMPTY
        _tracks.value = emptyList()
        _state.value = PlaybackState.Idle
        publishStatus()
    }

    override fun release() {
        switchJob?.cancel()
        player.release()
    }

    private fun publishStatus() {
        val size = player.videoSize
        _status.value = PlaybackStatus(
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady,
            videoSize = VideoSizeInfo(
                width = size.width,
                height = size.height,
                pixelWidthHeightRatio = size.pixelWidthHeightRatio,
            ),
        )
    }

    private companion object {
        const val DEFAULT_USER_AGENT = "OpenTV/0.1 (Android)"

        /** ExoPlayer's "do not retry" sentinel. */
        const val C_TIME_UNSET = androidx.media3.common.C.TIME_UNSET

        const val SWITCH_DEBOUNCE_MILLIS = 350L
        const val SEEK_INCREMENT_MILLIS = 15_000L
        const val INITIAL_BACKOFF_MILLIS = 500L
        const val MAX_BACKOFF_MILLIS = 8_000L
        const val MAX_LOAD_RETRIES = 5
        const val MAX_AUTO_RESTARTS = 3
        const val AUTO_RESTART_DELAY_MILLIS = 1_500L

        /**
         * Larger than ExoPlayer's defaults. IPTV sources are much twitchier than a CDN, and a
         * deeper buffer is the difference between a momentary hiccup and a visible stall.
         */
        const val MIN_BUFFER_MILLIS = 15_000
        const val MAX_BUFFER_MILLIS = 60_000
        const val BUFFER_FOR_PLAYBACK_MILLIS = 2_500
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MILLIS = 5_000

        // DVR mode (pause & rewind live TV): keep a couple of minutes behind the live edge to seek
        // into, and let the forward buffer grow to match so a pause of up to a couple of minutes
        // resumes cleanly. Deliberately bounded — this is memory the box has to find.
        const val DVR_BACK_BUFFER_MILLIS = 120_000
        const val DVR_MAX_BUFFER_MILLIS = 120_000

        // Watch-while-recording: hold playback a cushion *behind* the file's growing edge rather than
        // right on it. IPTV sources arrive in bursts, so a shallow buffer drains to nothing between
        // bursts and the picture stalls every second or two — worst over SMB to a NAS, where every
        // read is a network round-trip. Waiting for ~8s of reservoir before playback (and rebuilding
        // it after any stall) rides straight over those gaps; because playback and the recorder both
        // advance at 1x, that cushion holds without ever catching the edge again.
        const val LIVE_REC_MAX_BUFFER_MILLIS = 60_000
        const val LIVE_REC_BUFFER_FOR_PLAYBACK_MILLIS = 8_000
        const val LIVE_REC_BUFFER_AFTER_REBUFFER_MILLIS = 8_000

        // Guide-preview tuning: start fast and don't hoard buffer (the preview is muted and
        // low-stakes), and debounce channel-surfing harder so scrolling the list doesn't tune to
        // every channel in passing. Kept within DefaultLoadControl's constraints: min >= both
        // playback thresholds, max >= min.
        const val PREVIEW_MIN_BUFFER_MILLIS = 5_000
        const val PREVIEW_MAX_BUFFER_MILLIS = 15_000
        const val PREVIEW_BUFFER_FOR_PLAYBACK_MILLIS = 1_000
        const val PREVIEW_BUFFER_AFTER_REBUFFER_MILLIS = 1_500
        const val PREVIEW_SWITCH_DEBOUNCE_MILLIS = 700L

        const val LIVE_TARGET_OFFSET_MILLIS = 10_000L
    }
}

/**
 * Converts Media3's track model into the neutral one exactly once per track change.
 *
 * [groupIndex] counts groups of the same type: the subtitle picker filters by type and then
 * addresses `groupIndex`, so it must not be the index into the mixed list Media3 returns.
 */
private fun Tracks.toPlayerTracks(): List<PlayerTrack> {
    val seenPerType = mutableMapOf<PlayerTrackType, Int>()
    return groups.flatMap { group ->
        val type = group.type.toPlayerTrackType() ?: return@flatMap emptyList()
        val groupIndex = seenPerType[type] ?: 0
        seenPerType[type] = groupIndex + 1
        (0 until group.length).map { index ->
            val format = group.getTrackFormat(index)
            PlayerTrack(
                type = type,
                groupIndex = groupIndex,
                index = index,
                label = format.label,
                language = format.language,
                isSelected = group.isTrackSelected(index),
                isSupported = group.isTrackSupported(index),
            )
        }
    }
}

private fun Int.toPlayerTrackType(): PlayerTrackType? = when (this) {
    C.TRACK_TYPE_VIDEO -> PlayerTrackType.VIDEO
    C.TRACK_TYPE_AUDIO -> PlayerTrackType.AUDIO
    C.TRACK_TYPE_TEXT -> PlayerTrackType.TEXT
    else -> null
}

private fun PlayerTrackType.toMedia3Type(): Int = when (this) {
    PlayerTrackType.VIDEO -> C.TRACK_TYPE_VIDEO
    PlayerTrackType.AUDIO -> C.TRACK_TYPE_AUDIO
    PlayerTrackType.TEXT -> C.TRACK_TYPE_TEXT
}
