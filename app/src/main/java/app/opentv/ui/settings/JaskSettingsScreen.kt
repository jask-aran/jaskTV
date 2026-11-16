/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import app.opentv.core.get
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarViewDay
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.catalogue.CatalogueSort
import app.opentv.ui.JaskGlassSwitch
import app.opentv.ui.JaskSheet
import app.opentv.ui.JaskSurfaceHost
import app.opentv.ui.ProfilesViewModel
import app.opentv.ui.VodViewModel
import app.opentv.ui.vod.JaskTheme
import java.util.Locale

private val Card = Color(0xFF1B1A19)
private val Rule = Color.White.copy(alpha = .07f)
private val Dim = Color(0xFFA6A29E)
private val Ink = Color(0xFFF4F1ED)

/**
 * The jaskTV settings page (touch): one scrolling page of grouped cards. Account first (who's
 * watching, profiles, sync), then what Movies and Shows include, content types and playback
 * inline; deeper areas (sources, guide, recordings, parental, display) open their own pages.
 */
@Composable
fun JaskSettingsScreen(
    profiles: ProfilesViewModel,
    vod: VodViewModel,
    onOpenProfiles: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenAddons: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenWebManager: () -> Unit,
    onOpenDisplay: () -> Unit,
    onOpenParental: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenRecordings: () -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
) = JaskTheme {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val people by profiles.profiles.collectAsState()
    val activeId by profiles.activeProfileId.collectAsState()
    val sources by vod.sources.collectAsState()
    val source by vod.selectedVodSource.collectAsState()
    val everything by vod.showUnavailable.collectAsState()
    val options by vod.movieOptions.collectAsState()
    val languages by vod.catalogueLanguages.collectAsState()
    val glass by settings.glassMode.collectAsState()
    val live by settings.liveEnabled.collectAsState()
    val movies by settings.moviesEnabled.collectAsState()
    val shows by settings.seriesEnabled.collectAsState()
    val subtitles by settings.subtitlesEnabled.collectAsState()
    val resume by settings.resumeLastChannel.collectAsState()
    val livePause by settings.livePauseEnabled.collectAsState()
    val tmdb by settings.tmdbApiKey.collectAsState()
    val active = people.firstOrNull { it.id == activeId }
    var renaming by remember { mutableStateOf(false) }

    JaskSurfaceHost(Modifier.fillMaxSize().background(Color(0xFF0E0D0D)), background = {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().statusBarsPadding().padding(top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    app.opentv.ui.JaskGlassCircle(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), size = 44.dp, onClick = onBack)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.nav_settings), color = Ink, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
            }
            item {
                Group(null) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(active?.name ?: "?", 60.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(active?.name ?: "", color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.jask_profile_local), color = Dim, style = MaterialTheme.typography.bodySmall)
                        }
                        app.opentv.ui.JaskGlassCircle(Icons.Outlined.Edit, stringResource(R.string.profiles_rename), size = 40.dp) { renaming = true }
                    }
                    // Who's watching: switch in place; add/rename/remove on the profiles page.
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        people.forEach { p ->
                            Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { profiles.select(p.id) }.padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Avatar(p.name, 44.dp, selected = p.id == activeId)
                                Text(p.name, color = if (p.id == activeId) Ink else Dim, style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1, modifier = Modifier.widthIn(max = 64.dp).padding(top = 4.dp))
                            }
                        }
                        Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpenProfiles).padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(44.dp).clip(CircleShape).border(1.dp, Color.White.copy(alpha = .3f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Add, null, tint = Ink)
                            }
                            Text(stringResource(R.string.jask_profiles_manage), color = Dim, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    Divider()
                    Link(Icons.Outlined.Sync, stringResource(R.string.settings_sync_title), stringResource(R.string.settings_sync_subtitle), onOpenSync)
                }
            }
            item {
                Group(stringResource(R.string.jask_browse)) {
                    Toggle(stringResource(R.string.jask_show_unavailable), stringResource(R.string.jask_show_unavailable_hint), everything) { vod.setShowUnavailable(it) }
                    if (sources.size > 1) {
                        Divider()
                        Label(stringResource(R.string.jask_settings_sources_shown))
                        Chips(listOf(null to stringResource(R.string.catalogue_all_sources)) + sources.map { it.id to it.name }, { it == source }) { vod.selectVodSource(it) }
                    }
                    Divider()
                    Label(stringResource(R.string.catalogue_original_language))
                    Chips(listOf<String?>(null).plus(languages).map { it to (it?.let { c -> Locale.forLanguageTag(c).getDisplayLanguage(Locale.getDefault()) } ?: stringResource(R.string.catalogue_all_languages)) },
                        { if (it == null) options.languages.isEmpty() else it in options.languages }) { code ->
                        vod.applyMovieOptions(options.copy(languages = when {
                            code == null -> emptySet()
                            code in options.languages -> options.languages - code
                            else -> options.languages + code
                        }))
                    }
                    Toggle(stringResource(R.string.catalogue_unknown), null, options.includeUnknown) { vod.applyMovieOptions(options.copy(includeUnknown = it)) }
                    Divider()
                    Label(stringResource(R.string.jask_settings_all_movies_order))
                    Chips(CatalogueSort.entries.map { it to stringResource(when (it) {
                        CatalogueSort.TITLE -> R.string.catalogue_sort_title
                        CatalogueSort.YEAR -> R.string.catalogue_sort_year
                        CatalogueSort.RATING -> R.string.catalogue_sort_rating
                        CatalogueSort.ADDED -> R.string.catalogue_sort_added
                    }) }, { it == options.sort }) { vod.applyMovieOptions(options.copy(sort = it)) }
                    Spacer(Modifier.height(6.dp))
                }
            }
            item {
                Group(stringResource(R.string.jask_settings_appearance)) {
                    Label(stringResource(R.string.jask_settings_glass))
                    Chips(listOf("auto" to stringResource(R.string.jask_glass_auto), "live" to stringResource(R.string.jask_glass_live),
                        "frosted" to stringResource(R.string.jask_glass_frosted), "solid" to stringResource(R.string.jask_glass_solid)),
                        { it == glass }) { settings.setGlassMode(it) }
                    Spacer(Modifier.height(6.dp))
                }
            }
            item {
                Group(stringResource(R.string.settings_section_content)) {
                    Toggle(stringResource(R.string.nav_live_tv), stringResource(R.string.settings_content_live_subtitle), live, settings::setLiveEnabled)
                    Divider()
                    Toggle(stringResource(R.string.nav_movies), stringResource(R.string.settings_content_movies_subtitle), movies, settings::setMoviesEnabled)
                    Divider()
                    Toggle(stringResource(R.string.nav_shows), stringResource(R.string.settings_content_series_subtitle), shows, settings::setSeriesEnabled)
                }
            }
            item {
                Group(stringResource(R.string.settings_section_playback)) {
                    Toggle(stringResource(R.string.settings_subtitles_title), stringResource(R.string.settings_subtitles_subtitle), subtitles, settings::setSubtitlesEnabled)
                    Divider()
                    Toggle(stringResource(R.string.settings_resume_title), stringResource(R.string.settings_resume_subtitle), resume, settings::setResumeLastChannel)
                    Divider()
                    Toggle(stringResource(R.string.jask_settings_live_pause), stringResource(R.string.jask_settings_live_pause_hint), livePause, settings::setLivePauseEnabled)
                }
            }
            item {
                Group(stringResource(R.string.jask_settings_sources_guide)) {
                    Link(Icons.Outlined.Dns, stringResource(R.string.settings_providers_title),
                        pluralLabel(sources.size, sources.count { it.enabled }), onOpenProviders)
                    Divider()
                    Link(Icons.Outlined.CalendarViewDay, stringResource(R.string.settings_guide_title), stringResource(R.string.settings_guide_subtitle), onOpenGuide)
                    Divider()
                    Link(Icons.Outlined.Extension, stringResource(R.string.settings_addons_title), stringResource(R.string.settings_addons_subtitle), onOpenAddons)
                    Divider()
                    Link(Icons.Outlined.PhoneAndroid, stringResource(R.string.settings_webmanager_title), stringResource(R.string.settings_webmanager_subtitle), onOpenWebManager)
                }
            }
            item {
                Group(stringResource(R.string.jask_settings_more)) {
                    Link(Icons.Outlined.Palette, stringResource(R.string.settings_display_title),
                        if (tmdb.isBlank()) stringResource(R.string.jask_settings_tmdb_missing) else stringResource(R.string.settings_display_subtitle), onOpenDisplay)
                    Divider()
                    Link(Icons.Outlined.Storage, stringResource(R.string.settings_recording_title), stringResource(R.string.settings_recording_subtitle), onOpenRecordings)
                    Divider()
                    Link(Icons.Outlined.Lock, stringResource(R.string.settings_parental_title), stringResource(R.string.settings_parental_subtitle), onOpenParental)
                    Divider()
                    Link(Icons.Outlined.Info, stringResource(R.string.settings_about_title), stringResource(R.string.settings_about_subtitle), onOpenAbout)
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }) {
        app.opentv.ui.JaskBarScrim(Modifier.align(Alignment.TopCenter), top = true)
        if (renaming && active != null) {
            var name by remember { mutableStateOf(active.name) }
            JaskSheet({ renaming = false }) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.profiles_rename_heading), color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.common_cancel)) }
                        TextButton(onClick = { profiles.rename(active.id, name); renaming = false }) { Text(stringResource(R.string.common_save)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun pluralLabel(total: Int, enabled: Int) = when (total) {
    0 -> stringResource(R.string.jask_settings_no_sources)
    else -> stringResource(R.string.jask_settings_sources_count, total, enabled)
}

@Composable
private fun Group(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(bottom = 18.dp)) {
        title?.let {
            Text(it.uppercase(), color = Dim, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp, bottom = 7.dp))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card), content = content)
    }
}

@Composable
private fun Divider() = Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(Rule))

@Composable
private fun Label(text: String) = Text(text, color = Dim, style = MaterialTheme.typography.labelLarge,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp))

@Composable
private fun Toggle(title: String, subtitle: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, color = Dim, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.width(12.dp))
        JaskGlassSwitch(on)
    }
}

@Composable
private fun Link(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Ink, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, color = Dim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Dim)
    }
}

@Composable
private fun <T> Chips(items: List<Pair<T, String>>, selected: (T) -> Boolean, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (value, label) ->
            val on = selected(value)
            Text(label, color = if (on) Color(0xFF141312) else Ink, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Ink else Color.White.copy(alpha = .08f))
                    .clickable { onPick(value) }.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun Avatar(name: String, size: androidx.compose.ui.unit.Dp, selected: Boolean = true) {
    // A stable colour per name, so each profile is recognisable at a glance.
    val hue = (name.lowercase().hashCode().toLong() and 0xffff).toFloat() % 360f
    val colour = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, .55f, .78f)))
    Box(Modifier.size(size).clip(CircleShape).background(if (selected) colour else colour.copy(alpha = .45f))
        .then(if (selected) Modifier.border(2.dp, Color.White.copy(alpha = .85f), CircleShape) else Modifier),
        contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold,
            style = if (size > 50.dp) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium)
    }
}
