/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import android.content.pm.ActivityInfo
import app.opentv.core.findActivity
import app.opentv.ui.LocalLayoutClass
import app.opentv.ui.rememberLayoutClass
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.opentv.core.AppSettings
import app.opentv.core.PlayerOrientation
import app.opentv.core.PlayerOrientationSession
import app.opentv.core.PlayerOrientationTarget
import app.opentv.core.PipState
import app.opentv.core.shouldEnterPlayerPip
import app.opentv.core.ServiceLocator
import app.opentv.data.parser.displayTitle
import app.opentv.ui.MainScreen
import app.opentv.ui.browsingDestination
import app.opentv.ui.ProfilesViewModel
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.VodViewModel
import app.opentv.ui.player.PlayerScreen
import app.opentv.ui.theme.OpenTvTheme
import app.opentv.ui.vod.MovieDetailScreen
import app.opentv.ui.vod.PersonScreen
import app.opentv.ui.vod.SeriesDetailScreen
import app.opentv.ui.vod.VodPlayerScreen

class MainActivity : ComponentActivity() {

    var playerOrientationSession by mutableStateOf(PlayerOrientationSession())
        private set
    private var browseAutoRotate = true
    private fun inPipWindow() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
    private fun inMultiWindow() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode

    fun setPlaybackDestination(playing: Boolean, autoRotate: Boolean) {
        playerOrientationSession = playerOrientationSession.destination(playing)
        browseAutoRotate = autoRotate
        applyOrientationPolicy()
    }

    @Suppress("DEPRECATION")
    fun selectPlayerOrientation(orientation: PlayerOrientation) {
        if (!playerOrientationSession.active || inPipWindow() || inMultiWindow()) return
        val rotation = windowManager.defaultDisplay.rotation
        val naturalLandscape = if (rotation % 2 == 0)
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        else resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        playerOrientationSession = playerOrientationSession.select(orientation, rotation, naturalLandscape)
        applyOrientationPolicy()
    }

    private fun applyOrientationPolicy() {
        // Never derive policy from the resized PiP/multi-window configuration, nor write it back.
        if (isRunningOnTelevision(this) || inPipWindow() || inMultiWindow()) return
        val session = playerOrientationSession
        val requested = if (session.active) when (session.target(false, false, false)) {
            PlayerOrientationTarget.SENSOR_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            PlayerOrientationTarget.SENSOR_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            PlayerOrientationTarget.FULL_USER -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            PlayerOrientationTarget.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            PlayerOrientationTarget.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            PlayerOrientationTarget.REVERSE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            PlayerOrientationTarget.REVERSE_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            null -> return
        } else when {
            // Android observes its rotation lock live; do not snapshot it or use FULL_SENSOR.
            // Phones browse in portrait only (the player rotates on its own); tablets rotate freely.
            resources.configuration.smallestScreenWidthDp >= 600 -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            else -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        }
        if (requestedOrientation != requested) requestedOrientation = requested
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationPolicy()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (!isInMultiWindowMode) applyOrientationPolicy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("player.session", playerOrientationSession.active)
        outState.putString("player.orientation", playerOrientationSession.orientation.name)
        outState.putInt("player.rotation", playerOrientationSession.lockedRotation)
        outState.putBoolean("player.naturalLandscape", playerOrientationSession.naturalLandscape)
        super.onSaveInstanceState(outState)
    }

    /** D-pad sounds need to know which key moved focus (see JaskSounds); the UI handles the key as usual. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        app.opentv.ui.JaskSounds.key(event)
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        app.opentv.account.CoreDirectory.foreground()
        super.onStart()
        app.opentv.remote.RemoteReceiver.attach(this)
        app.opentv.update.AppUpdater.get(this).checkIfDue()
        app.opentv.account.AccountManager.get(this).foreground()
        PipState.setActivityVisible(true)
        PipState.setInPip(inPipWindow())
    }

    override fun onStop() {
        app.opentv.remote.RemoteReceiver.detach(this)
        // PiP is STARTED while visible. Stopping means even its floating window is gone.
        PipState.setActivityVisible(false)
        PipState.setInPip(false)
        super.onStop()
    }

    // Apply the chosen UI language before any view or resource is resolved. A language change in
    // settings calls recreate(), which re-runs this with the new tag.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(app.opentv.core.LocaleUtils.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.APPLICATION_ID.endsWith(".debug") && intent?.getBooleanExtra("jaskAudit", false) == true) app.opentv.ui.channels.StreamProbe.auditPending = true
        app.opentv.livetv.StreamHealth.init(this)
        app.opentv.ui.channels.readScreenLines(this)
        // Debug app only: `--ez jaskHealthClear true` forgets what live streams have done (emulator test plays).
        if (BuildConfig.APPLICATION_ID.endsWith(".debug") && intent?.getBooleanExtra("jaskHealthClear", false) == true) app.opentv.livetv.StreamHealth.clear()
        super.onCreate(savedInstanceState)
        app.opentv.ui.JaskSounds.attach(this)
        if (savedInstanceState == null) handlePairLink(intent)
        savedInstanceState?.let {
            playerOrientationSession = PlayerOrientationSession(
                active = it.getBoolean("player.session"),
                orientation = runCatching { PlayerOrientation.valueOf(it.getString("player.orientation").orEmpty()) }
                    .getOrDefault(PlayerOrientation.LANDSCAPE),
                lockedRotation = it.getInt("player.rotation"),
                naturalLandscape = it.getBoolean("player.naturalLandscape"),
            )
        }
        // The jaskTV pages are dark whatever the system theme: light clock and icons on both bars (the default follows
        // the system, which drew dark icons over dark pages on a phone in light mode).
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // The window reaches into the camera-cutout side in landscape too (before Android 15 it stopped short, and a light
        // strip showed down that edge). The content is kept clear of the cutout below, so the strip is the dark window.
        if (android.os.Build.VERSION.SDK_INT >= 30) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        else if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        if (savedInstanceState == null) handlePlayIntent(intent)
        val isTelevision = isRunningOnTelevision(this)

        setContent {
            val settings = remember { ServiceLocator.get(this).settings }
            val themeMode by settings.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                AppSettings.ThemeMode.DARK -> true
                AppSettings.ThemeMode.LIGHT -> false
                // A living-room screen defaults to dark; a phone/tablet follows the system.
                AppSettings.ThemeMode.SYSTEM -> isTelevision || isSystemInDarkTheme()
            }
            OpenTvTheme(darkTheme = darkTheme, phone = !isTelevision) {
                Surface(
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(
                        androidx.compose.foundation.layout.WindowInsets.displayCutout.only(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal)),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val layoutClass = rememberLayoutClass(isTelevision)
                    // The jaskTV focus ring and wash everywhere (sheets, menus, person pages), not only inside JaskTheme.
                    CompositionLocalProvider(LocalLayoutClass provides layoutClass,
                        app.opentv.ui.LocalJaskMaterial provides app.opentv.ui.rememberJaskMaterial(isTelevision),
                        androidx.compose.foundation.LocalIndication provides app.opentv.ui.JaskIndication) {
                        app.opentv.ui.JaskUiScale(layoutClass) { OpenTvApp(isTelevision = isTelevision) }
                    }
                }
            }
        }
    }

    // singleTask: a reminder tapped while the app is already running arrives here, not a fresh
    // onCreate. Either way the requested channel is handed to the nav graph via [PlayRequests].
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePlayIntent(intent)
        handlePairLink(intent)
    }

    /** A TV's sign-in QR code, opened by the phone's camera: ask whether to sign that TV in. */
    private fun handlePairLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.path != "/pair") return
        val params = (data.fragment ?: "").split('&').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }.associate { it[0] to it[1] }
        val code = params["c"] ?: return
        app.opentv.account.AccountManager.get(this).offerPairing(code, params["k"])
    }

    private fun handlePlayIntent(intent: Intent?) {
        // Shared reminder/future widget handoff: accept an ID, never an external stream URL.
        // The navigation consumer validates that the channel still exists before opening playback.
        val id = intent?.getLongExtra(EXTRA_PLAY_CHANNEL, 0L) ?: 0L
        if (id > 0L) app.opentv.core.PlayRequests.request(id)
        intent?.removeExtra(EXTRA_PLAY_CHANNEL)
        val recId = intent?.getLongExtra(EXTRA_WATCH_RECORDING, 0L) ?: 0L
        if (recId != 0L) {
            app.opentv.core.RecordingSignals.requestWatch(recId, System.currentTimeMillis())
        }
        intent?.removeExtra(EXTRA_WATCH_RECORDING)
    }

    // ---- Picture-in-picture ------------------------------------------------------------------

    /** Home pressed while a programme is playing → shrink to a floating window instead of stopping. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        maybeEnterPip()
    }

    private fun supportsPip(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun maybeEnterPip() {
        enterPipNow()
    }

    /** Both explicit and Home requests require actual Media3 playback, not merely READY. */
    fun enterPipNow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!shouldEnterPlayerPip(PipState.eligible, PipState.isPlaying, supportsPip()) || inPipWindow()) return
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational((PipState.aspectRatio * 10_000).toInt().coerceIn(4185, 23900), 10_000))
                    .build(),
            )
        }
    }

    fun pipSupported(): Boolean = supportsPip()

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        app.opentv.core.PipState.setInPip(isInPictureInPictureMode)
        if (!isInPictureInPictureMode) applyOrientationPolicy()
    }

    companion object {
        /** A reminder notification carries the channel to tune to in this extra. */
        const val EXTRA_PLAY_CHANNEL = "opentv.play_channel"

        /** A recording auto-switch notification carries the recording to watch in this extra. */
        const val EXTRA_WATCH_RECORDING = "opentv.watch_recording"
    }
}

object Routes {
    const val HOME = "home"
    const val ADD_SOURCE = "add-source"
    const val PLAYER = "player/{channelId}"
    /** [scope]: all | movies | series — which results the phone search starts filtered to. */
    const val EPG_SETTINGS = "epg-settings"
    const val SETTINGS_HUB = "settings"
    const val PROVIDERS = "providers"
    const val ADDONS = "addons"
    const val CHANNELS = "channels"
    const val PROFILES = "profiles"
    const val PARENTAL = "parental"
    const val SERIES_DETAIL = "series/{seriesId}"
    const val MOVIE_DETAIL = "movie/{movieId}"
    const val EDIT_SOURCE = "edit-source/{sourceId}"

    // A person's name goes in a query arg, URL-encoded, so spaces and punctuation survive the round
    // trip — the same inline-encode/decode approach as the VOD player below.
    const val PERSON = "person?name={name}&tmdb={tmdb}"

    // VOD plays carry the stream inline; a movie/episode is a one-off URL, not a stored id
    // the player can look up the way a channel is.
    const val VOD_PLAYER = "vod?key={key}&url={url}&title={title}&ua={ua}"

    fun player(channelId: Long) = "player/$channelId"
    fun seriesDetail(seriesId: Long) = "series/$seriesId"
    fun movieDetail(movieId: Long) = "movie/$movieId"
    fun editSource(sourceId: Long) = "edit-source/$sourceId"
    fun person(name: String, tmdb: Int = 0) = "person?name=${java.net.URLEncoder.encode(name, "UTF-8")}&tmdb=$tmdb"
    fun vodPlayer(key: String, url: String, title: String, ua: String): String {
        // Strict percent-encoding (space = %20, '+' = %2B). Navigation decodes query arguments once;
        // the old URLEncoder + second URLDecoder pass turned every '+' into a space — breaking
        // titles like "A+ …" and any stream URL carrying a '+' in a token.
        fun e(v: String) = android.net.Uri.encode(v)
        return "vod?key=${e(key)}&url=${e(url)}&title=${e(title)}&ua=${e(ua)}"
    }
}

@Composable
private fun OpenTvApp(isTelevision: Boolean) {
    val navController = rememberNavController()
    val sourcesViewModel: SourcesViewModel = viewModel()
    val vodViewModel: VodViewModel = viewModel()
    val profilesViewModel: ProfilesViewModel = viewModel()
    val sourcesUi by sourcesViewModel.ui.collectAsState()
    val profiles by profilesViewModel.profiles.collectAsState()
    val activeProfileId by profilesViewModel.activeProfileId.collectAsState()
    val activeProfileName = profiles.firstOrNull { it.id == activeProfileId }?.name ?: "Me"

    // Activity is the only orientation writer. Playback policy is session-scoped, never layout-scoped.
    val activity = LocalContext.current.findActivity() as? MainActivity
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val autoRotate by ServiceLocator.get(LocalContext.current).settings.phoneAutoRotate.collectAsState()
    LaunchedEffect(currentRoute, autoRotate) {
        if (currentRoute == null) return@LaunchedEffect
        val playing = currentRoute == Routes.PLAYER || currentRoute == Routes.VOD_PLAYER
        activity?.setPlaybackDestination(playing, autoRotate)
    }

    // Until the saved sources have loaded from the database, we cannot tell a first run from a
    // returning user — and guessing "first run" drops a returning user on the setup screen and
    // asks for their provider again. NavHost locks in its start destination on first
    // composition, so wait for that first load before building it.
    if (!sourcesUi.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // First run goes straight to setup — an empty channel list with no explanation is the
    // worst possible first impression.
    // With an account server in this build, a fresh device opens to the welcome screen (inside HOME): sign in, or
    // a TV's QR code for the phone to approve, or add a playlist. Without one, straight to adding a provider.
    val accountAvailable = remember { app.opentv.account.AccountManager.get(navController.context).available }
    val start = if (sourcesUi.sources.isEmpty() && !accountAvailable) Routes.ADD_SOURCE else Routes.HOME

    // External ID requests and resume-last share fresh privacy/recording validation.
    val bootContext = androidx.compose.ui.platform.LocalContext.current
    app.opentv.widget.ExternalChannelRequests(start) { id ->
        navController.navigate(Routes.player(id)) {
            // Recreate the player so its remembered zap queue is the newly validated one.
            popUpTo(Routes.PLAYER) { inclusive = true }
            launchSingleTop = true
        }
    }

    // Another device cast something here (or this device asked to "watch here"): open it in the player.
    val remotePlay by app.opentv.remote.RemoteSignals.pending.collectAsState()
    LaunchedEffect(remotePlay) {
        val p = remotePlay ?: return@LaunchedEffect
        app.opentv.remote.RemoteSignals.consume()
        when (p) {
            is app.opentv.remote.RemoteSignals.Play.Live -> app.opentv.core.PlayRequests.request(p.channelId)
            is app.opentv.remote.RemoteSignals.Play.Vod -> runCatching {
                val route = navController.currentBackStackEntry?.destination?.route
                if (route == Routes.VOD_PLAYER || route == Routes.PLAYER) navController.popBackStack()
                navController.navigate(Routes.vodPlayer(p.mediaKey, p.url, p.title, p.userAgent)) { launchSingleTop = true }
            }
        }
    }

    // Auto-switch: a scheduled recording started, so move the live view onto the recording file
    // (single-connection safe). Ignore a stale request a long-backgrounded app only now sees.
    val watchRequest by app.opentv.core.RecordingSignals.watch.collectAsState()
    LaunchedEffect(watchRequest) {
        val req = watchRequest ?: return@LaunchedEffect
        app.opentv.core.RecordingSignals.consumeWatch()
        val fresh = System.currentTimeMillis() - req.requestedAtMillis < 10 * 60_000L
        if (req.recordingId != 0L && fresh) {
            val rec = runCatching {
                ServiceLocator.get(bootContext).recordingRepository.byId(req.recordingId)
            }.getOrNull() ?: return@LaunchedEffect
            runCatching {
                navController.navigate(
                    Routes.vodPlayer(
                        key = "rec:${rec.id}",
                        url = "optvrec://${rec.id}",
                        title = rec.title,
                        ua = rec.userAgent,
                    ),
                ) {
                    launchSingleTop = true
                    // Drop the live channel we switched away from off the back stack, so Back from
                    // the recording returns to the home screen — never to a live stream. On a single-
                    // connection line, returning to live TV while still recording would open a second
                    // stream and risk the provider banning the account.
                    popUpTo(Routes.PLAYER) { inclusive = true }
                }
            }
        }
    }

    // A TV asking to be signed in (its QR code scanned by this phone): asked over whichever screen is open.
    app.opentv.ui.settings.PairOfferDialog()
    // A new release on GitHub: asked once per version, over whichever screen is open.
    app.opentv.update.UpdatePrompt()

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = start) {
            browsingDestination(Routes.ADD_SOURCE) {
                // First run has nowhere to go back to; adding from Providers does.
                val canGoBack = navController.previousBackStackEntry != null
                app.opentv.ui.settings.JaskSourceForm(
                    viewModel = sourcesViewModel,
                    onFinished = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.ADD_SOURCE) { inclusive = true }
                        }
                    },
                    onBack = if (canGoBack) ({ navController.popBackStack() }) else null,
                )
            }

            browsingDestination(Routes.EDIT_SOURCE) { entry ->
                val sourceId = entry.arguments?.getString("sourceId")?.toLongOrNull() ?: return@browsingDestination
                app.opentv.ui.settings.JaskSourceForm(
                    viewModel = sourcesViewModel,
                    editingSourceId = sourceId,
                    onFinished = { navController.popBackStack() },
                    onBack = { navController.popBackStack() },
                )
            }

            browsingDestination(Routes.HOME, edgeToEdgeOnTouch = true) {
                MainScreen(
                    isTelevision = isTelevision,
                    hasSources = sourcesUi.sources.isNotEmpty(),
                    isSyncing = sourcesUi.syncing,
                    onPlayChannel = { channel -> navController.navigate(Routes.player(channel.id)) },
                    onOpenMovie = { movie ->
                        navController.navigate(Routes.movieDetail(movie.id))
                    },
                    onOpenSeries = { series ->
                        navController.navigate(Routes.seriesDetail(series.id))
                    },
                    onResume = { key, url, title ->
                        navController.navigate(
                            Routes.vodPlayer(key, url, title, "OpenTV/0.1 (Android)"),
                        )
                    },
                    onAddSource = { navController.navigate(Routes.ADD_SOURCE) },
                    onRefresh = sourcesViewModel::refreshAll,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS_HUB) },
                    onOpenProfiles = { navController.navigate(Routes.PROFILES) },
                    onPlayRecording = { rec ->
                        // A NAS recording plays straight off its smb:// locator and a USB one off
                        // its content:// document URI (Media3 reads both); an internal one through
                        // a file:// uri. An internal recording that's still capturing plays through
                        // the tail-following optvrec:// source, so it can be watched as it records
                        // with no second connection to the provider. All go through the VOD player.
                        val stillRecording = rec.status == app.opentv.data.model.RecordingStatus.RECORDING
                        // Internal and NAS in-progress recordings are both tail-followable via the
                        // growing source; USB (content://) isn't.
                        val growable = !app.opentv.recording.RecordingStorage.isContent(rec.filePath) &&
                            !app.opentv.recording.RecordingStorage.isUsbPlaceholder(rec.filePath)
                        val url = when {
                            stillRecording && growable -> "optvrec://${rec.id}"
                            app.opentv.recording.SmbClient.isSmb(rec.filePath) -> rec.filePath
                            app.opentv.recording.RecordingStorage.isContent(rec.filePath) -> rec.filePath
                            else -> android.net.Uri.fromFile(java.io.File(rec.filePath)).toString()
                        }
                        navController.navigate(
                            Routes.vodPlayer(
                                key = "rec:${rec.id}",
                                url = url,
                                title = rec.title,
                                ua = rec.userAgent,
                            ),
                        )
                    },
                    onPlayCatchup = { key, url, title, ua ->
                        // Catch-up is a seekable archive stream — plays through the VOD player.
                        navController.navigate(Routes.vodPlayer(key, url, title, ua))
                    },
                    activeProfileName = activeProfileName,
                    vodViewModel = vodViewModel,
                    onOpenGuide = { navController.navigate(Routes.EPG_SETTINGS) },
                )
            }

            browsingDestination(Routes.PROFILES) {
                app.opentv.ui.settings.JaskProfilesPage(onBack = { navController.popBackStack() }, viewModel = profilesViewModel)
            }

            browsingDestination(Routes.SETTINGS_HUB, edgeToEdgeOnTouch = true) {
                app.opentv.ui.settings.JaskSettingsScreen(
                    profiles = profilesViewModel, vod = vodViewModel,
                    onOpenProfiles = { navController.navigate(Routes.PROFILES) },
                    onOpenProviders = { navController.navigate(Routes.PROVIDERS) },
                    onOpenAddons = { navController.navigate(Routes.ADDONS) },
                    onOpenGuide = { navController.navigate(Routes.EPG_SETTINGS) },
                    onOpenParental = { navController.navigate(Routes.PARENTAL) },
                    onOpenChannels = { navController.navigate(Routes.CHANNELS) },
                    onAddSource = { navController.navigate(Routes.ADD_SOURCE) },
                    onEditSource = { src -> navController.navigate(Routes.editSource(src.id)) },
                    onBack = { navController.popBackStack() },
                )
            }

            browsingDestination(Routes.CHANNELS) {
                app.opentv.ui.settings.JaskChannelsPage(onBack = { navController.popBackStack() })
            }

            browsingDestination(Routes.PARENTAL) {
                app.opentv.ui.settings.JaskParentalPage(onBack = { navController.popBackStack() })
            }

            browsingDestination(Routes.PROVIDERS) {
                app.opentv.ui.settings.JaskProvidersPage(
                    viewModel = sourcesViewModel,
                    onAddSource = { navController.navigate(Routes.ADD_SOURCE) },
                    onEditSource = { src -> navController.navigate(Routes.editSource(src.id)) },
                    onBack = { navController.popBackStack() },
                )
            }

            browsingDestination(Routes.ADDONS) {
                app.opentv.ui.settings.JaskAddonsPage(onBack = { navController.popBackStack() })
            }

            browsingDestination(Routes.EPG_SETTINGS) {
                app.opentv.ui.settings.JaskGuidesPage(onBack = { navController.popBackStack() })
            }

            composable(Routes.PLAYER) { entry ->
                val channelId = entry.arguments?.getString("channelId")?.toLongOrNull()
                PlayerScreen(
                    channelId = channelId,
                    onBack = { navController.popBackStack() },
                )
            }

            browsingDestination(Routes.SERIES_DETAIL, edgeToEdgeOnTouch = true) { entry ->
                val seriesId = entry.arguments?.getString("seriesId")?.toLongOrNull() ?: return@browsingDestination
                app.opentv.ui.JaskMediaAmbient {
                SeriesDetailScreen(
                    seriesId = seriesId,
                    viewModel = vodViewModel,
                    onPlayEpisode = { key, url, title ->
                        navController.navigate(
                            Routes.vodPlayer(key, url, title, "OpenTV/0.1 (Android)"),
                        )
                    },
                    onOpenSeries = { series -> navController.navigate(Routes.seriesDetail(series.id)) },
                    onOpenPerson = { name -> navController.navigate(Routes.person(name)) },
                    onBack = { navController.popBackStack() },
                    onOpenMovieId = { id -> navController.navigate(Routes.movieDetail(id)) },
                    onOpenSeriesId = { id -> navController.navigate(Routes.seriesDetail(id)) },
                    onOpenPersonId = { id, name -> navController.navigate(Routes.person(name, id)) },
                )
                }
            }

            browsingDestination(Routes.MOVIE_DETAIL, edgeToEdgeOnTouch = true) { entry ->
                val movieId = entry.arguments?.getString("movieId")?.toLongOrNull() ?: return@browsingDestination
                app.opentv.ui.JaskMediaAmbient {
                MovieDetailScreen(
                    movieId = movieId,
                    viewModel = vodViewModel,
                    onPlay = { movie ->
                        navController.navigate(
                            Routes.vodPlayer(
                                key = "movie:${movie.id}",
                                url = movie.streamUrl,
                                title = movie.displayTitle,
                                ua = "OpenTV/0.1 (Android)",
                            ),
                        )
                    },
                    onPlayUrl = { key, url, title ->
                        navController.navigate(
                            Routes.vodPlayer(key = key, url = url, title = title, ua = "OpenTV/0.1 (Android)"),
                        )
                    },
                    onOpenMovie = { movie -> navController.navigate(Routes.movieDetail(movie.id)) },
                    onOpenPerson = { name -> navController.navigate(Routes.person(name)) },
                    onBack = { navController.popBackStack() },
                    onOpenMovieId = { id -> navController.navigate(Routes.movieDetail(id)) },
                    onOpenSeriesId = { id -> navController.navigate(Routes.seriesDetail(id)) },
                    onOpenPersonId = { id, name -> navController.navigate(Routes.person(name, id)) },
                )
                }
            }

            browsingDestination(Routes.PERSON, edgeToEdgeOnTouch = true) { entry ->
                val name = entry.arguments?.getString("name")
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
                app.opentv.ui.JaskMediaAmbient {
                PersonScreen(
                    name = name,
                    viewModel = vodViewModel,
                    onOpenMovie = { movie -> navController.navigate(Routes.movieDetail(movie.id)) },
                    onOpenSeries = { series -> navController.navigate(Routes.seriesDetail(series.id)) },
                    onBack = { navController.popBackStack() },
                    tmdbId = entry.arguments?.getString("tmdb")?.toIntOrNull() ?: 0,
                    onOpenMovieId = { id -> navController.navigate(Routes.movieDetail(id)) },
                    onOpenSeriesId = { id -> navController.navigate(Routes.seriesDetail(id)) },
                )
                }
            }

            composable(Routes.VOD_PLAYER) { entry ->
                // Already decoded by Navigation — see Routes.vodPlayer.
                fun arg(name: String) = entry.arguments?.getString(name).orEmpty()
                VodPlayerScreen(
                    mediaKey = arg("key"),
                    streamUrl = arg("url"),
                    title = arg("title"),
                    userAgent = arg("ua").ifEmpty { "OpenTV/0.1 (Android)" },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        // Sits above the whole nav graph so a found update can prompt from any screen.
        // jaskTV releases are independent: never offer upstream APK updates.

        // "About to switch to a recording" banner — shows 30s before an auto-switch fires.
        RecordingSwitchBanner()
    }
}

/**
 * A slim banner shown 30 seconds before a scheduled recording takes over the screen (single-
 * connection auto-switch). It gives the viewer a heads-up and a way out: "Keep watching" suppresses
 * the switch for that booking. It clears itself once the switch would have happened.
 */
@Composable
private fun RecordingSwitchBanner() {
    val imminent by app.opentv.core.RecordingSignals.imminent.collectAsState()
    val current = imminent ?: return

    // Auto-clear a little after the switch instant, in case the switch itself never lands.
    LaunchedEffect(current.recordingId) {
        val wait = (current.startAtMillis + 5_000L) - System.currentTimeMillis()
        kotlinx.coroutines.delay(wait.coerceIn(5_000L, 60_000L))
        if (app.opentv.core.RecordingSignals.imminent.value?.recordingId == current.recordingId) {
            app.opentv.core.RecordingSignals.clearImminent()
        }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.FiberManualRecord,
                contentDescription = null,
                tint = Color(0xFFE53935),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.rec_switch_banner, current.title, current.channelName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(14.dp))
            var focused by remember { mutableStateOf(false) }
            Text(
                stringResource(R.string.rec_switch_keep_watching),
                style = MaterialTheme.typography.labelLarge,
                color = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (focused) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
                    .clickable { app.opentv.core.RecordingSignals.suppress(current.recordingId) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Detects a ten-foot device.
 *
 * Checked at runtime rather than by shipping a separate leanback build: one APK for phone,
 * tablet, Android TV and Fire TV means one thing to release and one thing to test.
 */
fun isRunningOnTelevision(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val packageManager = context.packageManager
    return packageManager.hasSystemFeature("android.software.leanback") ||
        packageManager.hasSystemFeature("android.hardware.type.television")
}
