/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import app.opentv.core.get
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
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.CalendarViewDay
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.AccountCircle
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
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    onPlayRecording: (Recording) -> Unit,
    onPlayCatchup: (mediaKey: String, url: String, title: String, ua: String) -> Unit,
    activeProfileName: String,
    vodViewModel: VodViewModel = viewModel(),
    onOpenGuide: () -> Unit = onOpenSettings,
) {
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
        val sources by vodViewModel.sources.collectAsState()
        val vodSource by vodViewModel.selectedVodSource.collectAsState()
        val showUnavailable by vodViewModel.showUnavailable.collectAsState()
        CompositionLocalProvider(LocalJaskFilterRequest provides filterRequest) {
            JaskChromeFrame(destination, { if (it != destination) clearQueries(); focusManager.clearFocus()
                    destination = it; showInventory = false; filterRequest = 0 },
                query = query, onQuery = setQuery, onFilter = { filterRequest++ },
                accountMenu = accountMenu(activeProfileName, vodViewModel, sources, vodSource, showUnavailable, isSyncing,
                    onOpenProfiles = onOpenProfiles, onOpenSettings = onOpenSettings, onOpenGuide = onOpenGuide, onAddSource = onAddSource,
                    onRefresh = { if (destination == JaskDestination.MOVIES || destination == JaskDestination.SHOWS) vodViewModel.refreshVod() else onRefresh() },
                    onInventory = { showInventory = true; destination = JaskDestination.SHOWS }),
                // Browsing scope lives in the account popover; only Library has a header button (Downloads).
                filterIcon = when (destination) {
                    JaskDestination.LIBRARY -> androidx.compose.material.icons.Icons.Filled.DownloadForOffline
                    JaskDestination.LIVE -> androidx.compose.material.icons.Icons.Filled.Tune
                    else -> null
                },
                filterLabel = when (destination) {
                    JaskDestination.LIBRARY -> stringResource(R.string.jask_library_downloads)
                    JaskDestination.LIVE -> "Regions, sports and channels"
                    else -> null
                }) {
                // No playlists on this device (checked against the database, not a list still loading): welcome, with sign-in.
                val context = androidx.compose.ui.platform.LocalContext.current
                val stored by remember { app.opentv.core.ServiceLocator.get(context).database.sources().observeAll() }
                    .collectAsState(initial = null)
                if (stored?.isEmpty() == true) app.opentv.ui.settings.JaskWelcome(onAddSource)
                else when (destination) {
                    JaskDestination.MOVIES -> MoviesScreen(onOpenMovie, onResume, {},
                        hasSources, isSyncing, vodViewModel)
                    JaskDestination.SHOWS -> SeriesScreen(onOpenSeries, onResume, {}, hasSources, isSyncing, vodViewModel)
                    // Edge to edge: Live TV's hero runs under the header; its pages add the content padding themselves.
                    JaskDestination.LIVE -> Box(Modifier.fillMaxSize()) {
                        app.opentv.ui.channels.MyTvScreen(query = liveQuery, onPlay = onPlayChannel)
                    }
                    JaskDestination.LIBRARY -> Box(Modifier.fillMaxSize()) {
                        app.opentv.ui.library.JaskLibrary(libraryQuery, vodViewModel, onPlayChannel, onOpenMovie, onOpenSeries, onResume)
                    }
                }
            }
        }
    }
}


/**
 * The account popover: who's watching, what Movies and Shows include (sources, and whether titles
 * not on any source are listed), then settings and source management.
 */
@Composable
private fun accountMenu(
    profileName: String, vm: VodViewModel, sources: List<app.opentv.data.model.Source>, selected: Long?, showUnavailable: Boolean,
    isSyncing: Boolean, onOpenProfiles: () -> Unit, onOpenSettings: () -> Unit, onOpenGuide: () -> Unit, onAddSource: () -> Unit,
    onRefresh: () -> Unit, onInventory: () -> Unit,
): List<JaskMenuItem> = buildList {
    val icons = androidx.compose.material.icons.Icons.Outlined
    add(JaskMenuItem(profileName, icon = icons.AccountCircle, subtitle = stringResource(R.string.jask_switch_profile), onClick = onOpenProfiles))
    add(JaskMenuItem(stringResource(R.string.jask_browse), header = true, divider = true))
    add(JaskMenuItem(stringResource(R.string.jask_show_unavailable), subtitle = stringResource(R.string.jask_show_unavailable_hint),
        toggle = showUnavailable, keepOpen = true) { vm.setShowUnavailable(!showUnavailable) })
    if (sources.size > 1) {
        add(JaskMenuItem(stringResource(R.string.catalogue_all_sources), checked = selected == null, keepOpen = true) { vm.selectVodSource(null) })
        sources.forEach { source -> add(JaskMenuItem(source.name, checked = selected == source.id, keepOpen = true) { vm.selectVodSource(source.id) }) }
    }
    add(JaskMenuItem(stringResource(R.string.nav_settings), icon = icons.Settings, divider = true, onClick = onOpenSettings))
    add(JaskMenuItem(stringResource(R.string.jask_sources), icon = icons.Dns, onClick = onAddSource))
    add(JaskMenuItem(stringResource(R.string.jask_guide_sources), icon = icons.CalendarViewDay, onClick = onOpenGuide))
    add(JaskMenuItem(stringResource(R.string.phone_refresh), icon = icons.Refresh, enabled = !isSyncing, onClick = onRefresh))
    add(JaskMenuItem(stringResource(R.string.jask_inventory), icon = icons.Inventory2, onClick = onInventory))
}
