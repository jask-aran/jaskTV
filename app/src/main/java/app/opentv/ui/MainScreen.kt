/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Tune

import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import app.opentv.R
import app.opentv.core.findActivity
import app.opentv.core.StatusBus
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.opentv.core.AppSettings
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Recording
import app.opentv.data.model.Series
import app.opentv.ui.channels.HomeScreen
import app.opentv.ui.recordings.RecordingsScreen
import app.opentv.ui.downloads.DownloadsScreen
import androidx.compose.material.icons.filled.Download
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.ui.vod.MoviesScreen
import app.opentv.ui.vod.SeriesScreen
import app.opentv.ui.library.LibraryScreen
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Star

/**
 * The shell: a slim navigation rail down the left over a content area. The rail sits collapsed as
 * an icon strip and expands to show labels the moment focus lands in it — the TiviMate-style side
 * menu people asked for, instead of a top bar that ate a row of the guide. It overlays the content
 * rather than pushing it, so expanding the menu never reflows the guide underneath.
 */
enum class Tab(val labelRes: Int, val icon: ImageVector) {
    LIVE(R.string.nav_live_tv, Icons.Filled.LiveTv),
    VOD(R.string.nav_media_simple, Icons.Filled.Movie),
    LIBRARY(R.string.nav_saved_simple, Icons.Filled.Star),
}

private val RAIL_COLLAPSED = 76.dp
private val RAIL_EXPANDED = 236.dp

@Composable
fun MainScreen(
    isTelevision: Boolean,
    hasSources: Boolean,
    isSyncing: Boolean,
    onPlayChannel: (Channel) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onResume: (mediaKey: String, url: String, title: String) -> Unit,
    onAddSource: () -> Unit,
    onRefresh: () -> Unit,
    /** Opens search pre-filtered to a scope: "all", "movies" or "series". */
    onOpenSearch: (scope: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    onPlayRecording: (Recording) -> Unit,
    onPlayCatchup: (mediaKey: String, url: String, title: String, ua: String) -> Unit,
    activeProfileName: String,
    vodViewModel: VodViewModel = viewModel(),
    onOpenGuide: () -> Unit = onOpenSettings,
) {
    if (LocalLayoutClass.current.isTouch) {
        app.opentv.ui.vod.JaskTheme {
            var destination by rememberSaveable { mutableStateOf(JaskDestination.MOVIES) }
            var filterRequest by remember { mutableStateOf(0) }
            var showInventory by rememberSaveable { mutableStateOf(false) }
            // The header search field filters the current destination in place; there is no search route.
            val browseQuery by vodViewModel.browseQuery.collectAsState()
            var liveQuery by rememberSaveable { mutableStateOf("") }
            var libraryQuery by rememberSaveable { mutableStateOf("") }
            val query = when (destination) {
                JaskDestination.MOVIES, JaskDestination.SHOWS -> browseQuery
                JaskDestination.LIVE -> liveQuery
                JaskDestination.LIBRARY -> libraryQuery
            }
            val setQuery: (String) -> Unit = { text -> when (destination) {
                JaskDestination.MOVIES, JaskDestination.SHOWS -> vodViewModel.setBrowseQuery(text)
                JaskDestination.LIVE -> liveQuery = text
                JaskDestination.LIBRARY -> libraryQuery = text
            } }
            fun clearQueries() { vodViewModel.setBrowseQuery(""); liveQuery = ""; libraryQuery = "" }
            BackHandler(query.isNotEmpty()) { setQuery("") }
            BackHandler(query.isEmpty() && destination != JaskDestination.MOVIES) { destination = JaskDestination.MOVIES }
            val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            CompositionLocalProvider(LocalJaskFilterRequest provides filterRequest) {
                JaskChromeFrame(destination, { if (it != destination) clearQueries(); focusManager.clearFocus()
                        destination = it; showInventory = false; filterRequest = 0 },
                    query = query, onQuery = setQuery, onFilter = { filterRequest++ },
                    accountMenu = listOf(
                        JaskMenuItem(activeProfileName) { onOpenProfiles() },
                        JaskMenuItem(stringResource(R.string.nav_settings)) { onOpenSettings() },
                        JaskMenuItem(stringResource(R.string.jask_guide_sources)) { onOpenGuide() },
                        JaskMenuItem(stringResource(R.string.jask_sources)) { onAddSource() },
                        JaskMenuItem(stringResource(R.string.phone_refresh), enabled = !isSyncing) {
                            if (destination == JaskDestination.MOVIES || destination == JaskDestination.SHOWS) vodViewModel.refreshVod() else onRefresh()
                        },
                        JaskMenuItem(stringResource(R.string.jask_inventory)) { showInventory = true; destination = JaskDestination.SHOWS },
                    ),
                    // Library has no filters (they're inline); its header button opens Downloads.
                    filterIcon = if (destination == JaskDestination.LIBRARY) androidx.compose.material.icons.Icons.Filled.DownloadForOffline
                        else androidx.compose.material.icons.Icons.Filled.Tune,
                    filterLabel = if (destination == JaskDestination.LIBRARY) stringResource(R.string.jask_library_downloads) else null) {
                    when (destination) {
                        JaskDestination.MOVIES -> MoviesScreen(onOpenMovie, onResume, {},
                            hasSources, isSyncing, vodViewModel)
                        JaskDestination.SHOWS -> SeriesScreen(onOpenSeries, onResume, {}, hasSources, isSyncing, vodViewModel)
                        JaskDestination.LIVE -> Box(Modifier.fillMaxSize().padding(LocalJaskContentPadding.current)) {
                            app.opentv.ui.channels.MyTvScreen(query = liveQuery, onPlay = onPlayChannel)
                        }
                        JaskDestination.LIBRARY -> Box(Modifier.fillMaxSize()) {
                            app.opentv.ui.library.JaskLibrary(libraryQuery, vodViewModel, onPlayChannel, onOpenMovie, onOpenSeries, onResume)
                        }
                    }
                }
            }
        }
        return
    }

    // Content-type toggles: a switched-off type has its tab hidden here (and its sync skipped in
    // CatalogRepository). Recordings is never a content type, so it always stays — which also means
    // there is always at least one tab and never a blank shell, even with all three types off.
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val liveEnabled by settings.liveEnabled.collectAsState()
    val moviesEnabled by settings.moviesEnabled.collectAsState()
    val seriesEnabled by settings.seriesEnabled.collectAsState()
    val visibleTabs = remember(liveEnabled, moviesEnabled, seriesEnabled) {
        buildList {
            if (liveEnabled) add(Tab.LIVE)
            if (moviesEnabled || seriesEnabled) add(Tab.VOD)
            add(Tab.LIBRARY)
        }
    }
    // The default/home tab is the first visible one — Live TV normally, otherwise the first type
    // still switched on (or Recordings if none are).
    val homeTab = visibleTabs.first()

    // Saveable so it survives leaving for the player or a detail page: Back from an episode used
    // to land on Live TV because this state was rebuilt from scratch when the shell came back.
    var tab by rememberSaveable { mutableStateOf(if (Tab.VOD in visibleTabs) Tab.VOD else homeTab) }
    var catalogueSearchVisible by rememberSaveable { mutableStateOf(false) }
    var vodSection by rememberSaveable { mutableStateOf("Movies") }
    LaunchedEffect(moviesEnabled, seriesEnabled) {
        if (!moviesEnabled && seriesEnabled) vodSection = "Shows"
        else if (!seriesEnabled) vodSection = "Movies"
    }

    val vodLoading by vodViewModel.vodLoading.collectAsState()
    val refreshing = isSyncing || vodLoading
    val refreshAction: () -> Unit = if (tab == Tab.VOD) vodViewModel::refreshVod else onRefresh

    // If the selected tab gets hidden (its type toggled off while it's open), drop back to the
    // home tab so the content area never tries to show a tab that's no longer there.
    LaunchedEffect(visibleTabs) {
        if (tab !in visibleTabs) tab = homeTab
    }

    // Back from a non-home tab returns to the home tab rather than dropping out of the app.
    BackHandler(enabled = tab != homeTab) { tab = homeTab }

    // On the home tab, Back would otherwise drop straight out to the TV launcher — one stray press
    // and you've closed the app. Ask first. (A dialog or panel open in a child screen swallows Back
    // before this, so this only fires at the true root.)
    var showExit by remember { mutableStateOf(false) }
    BackHandler(enabled = tab == homeTab) { showExit = true }
    if (showExit) {
        AlertDialog(
            onDismissRequest = { showExit = false },
            title = { Text(stringResource(R.string.exit_title)) },
            text = { Text(stringResource(R.string.exit_body)) },
            confirmButton = {
                TextButton(onClick = { showExit = false; context.findActivity()?.finish() }) {
                    Text(stringResource(R.string.exit_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExit = false }) { Text(stringResource(R.string.exit_cancel)) }
            },
        )
    }

    val content: @Composable () -> Unit = {
        when (tab) {
            Tab.LIVE -> HomeScreen(
                isTelevision = isTelevision,
                hasSources = hasSources,
                isSyncing = isSyncing,
                onPlayChannel = onPlayChannel,
                onAddSource = onAddSource,
                onRefresh = onRefresh,
                onPlayCatchup = onPlayCatchup,
                onSearchLive = { onOpenSearch("channels") },
            )
            Tab.VOD -> Column(Modifier.fillMaxSize()) {
                val kindControl: @Composable () -> Unit = {
                    SegmentedControl(
                        options = buildList {
                            if (moviesEnabled) add("Movies" to stringResource(R.string.nav_movies))
                            if (seriesEnabled) add("Shows" to stringResource(R.string.nav_shows))
                        }, selected = vodSection, onSelect = { vodSection = it },
                        modifier = Modifier.width(176.dp),
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        vodSection == "Movies" && moviesEnabled -> MoviesScreen(
                            onOpenMovie = onOpenMovie, onResume = onResume,
                            onOpenSearch = { onOpenSearch("movies") },
                            hasSources = hasSources, isSyncing = isSyncing, viewModel = vodViewModel, kindControl = kindControl,
                            searchVisible = catalogueSearchVisible,
                            onCloseSearch = { catalogueSearchVisible = false; vodViewModel.setBrowseQuery("") })
                        seriesEnabled -> SeriesScreen(
                            onOpenSeries = onOpenSeries, onResume = onResume,
                            onOpenSearch = { onOpenSearch("series") },
                            hasSources = hasSources, isSyncing = isSyncing, viewModel = vodViewModel, kindControl = kindControl,
                            searchVisible = catalogueSearchVisible,
                            onCloseSearch = { catalogueSearchVisible = false; vodViewModel.setBrowseQuery("") })
                    }
                }
            }
            Tab.LIBRARY -> LibraryScreen(
                onPlayChannel = onPlayChannel, onOpenMovie = onOpenMovie,
                onOpenSeries = onOpenSeries, onResume = onResume)

        }
    }

    // Phones get a touch shell: a top app bar for the global actions and a bottom tab bar, so the
    // full portrait width goes to the content instead of a TV-sized side rail.
    if (LocalLayoutClass.current == LayoutClass.PHONE) {
        val landscape = isPhoneLandscape()
        val scopedSearch = {
            onOpenSearch(
                when (tab) {
                    Tab.LIVE -> "channels"
                    else -> "all"
                },
            )
        }
        // The browsing destination supplies and consumes safe insets around the entire shell,
        // including either rail, content and bottom navigation (not just the background).
        Row(Modifier.fillMaxSize()) {
            if (landscape) {
                PhoneLandscapeRail(visibleTabs, tab, { tab = it }, refreshing, refreshAction,
                    scopedSearch, onOpenSettings, onOpenProfiles, activeProfileName, showSearch = false)
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (!landscape) PhoneTopBar(
                    title = stringResource(tab.labelRes),
                    onSearch = if (tab == Tab.VOD && !catalogueSearchVisible)
                        ({ catalogueSearchVisible = true }) else null,
                    isSyncing = refreshing,
                    onRefresh = refreshAction,
                    onOpenSettings = onOpenSettings,
                    onOpenProfiles = onOpenProfiles,
                    activeProfileName = activeProfileName,
                )
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                // The toolbar carries phone sync activity.
                if (!landscape) CompactBottomNavigation(visibleTabs, tab) { tab = it }

            }
        }
        return
    }

    // The rail sits beside the content and pushes it, rather than floating over it. The Live TV
    // screen has its own category rail down its left edge, and an overlaying menu would land on top
    // of it and leave a sliver poking out — so they live side by side and never collide.
    Column(Modifier.fillMaxSize()) {
      Row(Modifier.weight(1f).fillMaxWidth()) {
        NavRail(
            tabs = visibleTabs,
            current = tab,
            onSelect = { tab = it },
            onOpenSearch = { onOpenSearch("all") },
            onOpenSettings = onOpenSettings,
            onOpenProfiles = onOpenProfiles,
            activeProfileName = activeProfileName,
        )

        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
      }
      StatusBar()
    }
}

/** Two touch-sized strips keep navigation and global actions visible without a top/bottom bar. */
@Composable
internal fun PhoneLandscapeRail(
    tabs: List<Tab>, current: Tab, onSelect: (Tab) -> Unit,
    isSyncing: Boolean, onRefresh: () -> Unit, onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit, onOpenProfiles: () -> Unit, activeProfileName: String,
    showSearch: Boolean = true,
) {
    Row(
        Modifier.width(112.dp).fillMaxHeight().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Scroll independently on very short viewports; tabs cannot push actions off screen.
        Column(Modifier.width(48.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            tabs.forEach { t ->
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (current == t) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = current == t, role = Role.Tab, onClick = { onSelect(t) }),
                    contentAlignment = Alignment.Center) {
                    Icon(t.icon, stringResource(t.labelRes))
                }
            }
        }
        Column(Modifier.width(48.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            if (showSearch) IconButton(onClick = onOpenSearch, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.nav_search))
            }
            IconButton(onClick = onRefresh, enabled = !isSyncing, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.phone_refresh))
                if (isSyncing) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                }
            }
            IconButton(onClick = onOpenProfiles, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Person, contentDescription = activeProfileName)
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.nav_settings))
            }
        }
    }
}

/** Compact phone navigation; the surrounding BrowsingViewport owns system insets. */
@Composable
internal fun CompactBottomNavigation(tabs: List<Tab>, current: Tab, onSelect: (Tab) -> Unit) {
    Box(Modifier.fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.surface)) {
      Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant))
      Row(Modifier.fillMaxSize()) {
        tabs.forEach { tab ->
            val selected = current == tab
            val color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            Column(Modifier.weight(1f).fillMaxHeight()
                .selectable(selected, role = Role.Tab, onClick = { onSelect(tab) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                val icon = if (selected) tab.icon else when (tab) {
                    Tab.LIVE -> Icons.Outlined.LiveTv
                    Tab.VOD -> Icons.Outlined.Movie
                    Tab.LIBRARY -> Icons.Outlined.StarOutline
                }
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(3.dp))
                Text(stringResource(tab.labelRes), color = color,
                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}
}

@Composable
private fun PhoneTopBar(
    title: String,
    onSearch: (() -> Unit)? = null,
    isSyncing: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    activeProfileName: String,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(58.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (onSearch != null) IconButton(onClick = onSearch, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Filled.Search, stringResource(R.string.nav_search), Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onOpenProfiles, modifier = Modifier.size(44.dp)) {
            Box(Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Person, activeProfileName, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(44.dp)) {
                if (isSyncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp)
                else Icon(Icons.Filled.MoreVert, stringResource(R.string.nav_settings),
                    Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.phone_refresh)) },
                    enabled = !isSyncing, onClick = { menu = false; onRefresh() },
                    leadingIcon = { Icon(Icons.Filled.Refresh, null) })
                DropdownMenuItem(text = { Text(stringResource(R.string.nav_settings)) },
                    onClick = { menu = false; onOpenSettings() },
                    leadingIcon = { Icon(Icons.Filled.Settings, null) })
            }
        }
    }
}

/**
 * A slim line along the bottom that says what the app is doing in the background — loading
 * channels, building the guide, loading movies — so a slow moment on a big provider reads as work
 * in progress, not a frozen screen. Invisible when there's nothing to report.
 */
@Composable
private fun StatusBar() {
    val compact = isPhoneLandscape()
    val message by StatusBus.message.collectAsState()
    val progress by StatusBus.progress.collectAsState()
    val text = message ?: return
    val p = progress
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (p == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "${(p * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (compact) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (p != null) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { p },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun NavRail(
    tabs: List<Tab>,
    current: Tab,
    onSelect: (Tab) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    activeProfileName: String,
    modifier: Modifier = Modifier,
) {
    // Expand whenever focus is anywhere inside the rail; collapse back to icons when it leaves.
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(
        targetValue = if (expanded) RAIL_EXPANDED else RAIL_COLLAPSED,
        label = "railWidth",
    )

    Column(
        modifier
            .width(width)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .focusGroup()
            .onFocusChanged { expanded = it.hasFocus }
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Brand: the logo mark alone when collapsed, the mark + "OpenTV" wordmark when open. The
        // only the jaskTV wordmark is shown when the rail is expanded.
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Tv,
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(34.dp),
            )
            if (expanded) {
                Spacer(Modifier.width(12.dp))
                Wordmark()
            }
        }
        Spacer(Modifier.height(8.dp))

        tabs.forEach { t ->
            RailItem(t.icon, stringResource(t.labelRes), expanded, current == t) { onSelect(t) }
        }

        Spacer(Modifier.height(1.dp).fillMaxWidth())
        Spacer(Modifier.weight(1f))

        RailItem(Icons.Filled.Search, stringResource(R.string.nav_search), expanded, false, onOpenSearch)
        RailItem(Icons.Filled.Person, activeProfileName, expanded, false, onOpenProfiles)
        RailItem(Icons.Filled.Settings, stringResource(R.string.nav_settings), expanded, false, onOpenSettings)
    }
}

@Composable
private fun RailItem(
    icon: ImageVector,
    label: String,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    val tint = if (focused) MaterialTheme.colorScheme.onPrimary
    else if (selected) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        if (expanded) {
            Spacer(Modifier.width(14.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = tint,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The "OpenTV / MOBILE" wordmark: the upstream name, with what this app is set small underneath
 * — credit to the project it grew from, and plain about being the phone edition.
 */
@Composable
private fun Wordmark(modifier: Modifier = Modifier) {
    Column(modifier) {
        JaskWordmark(Modifier.height(22.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            "PHONE + TV",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 3.sp,
            maxLines = 1,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}
