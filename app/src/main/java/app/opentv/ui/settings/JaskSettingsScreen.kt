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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.filled.Check
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
internal val JaskDim = Color(0xFFA6A29E)
internal val JaskInk = Color(0xFFF4F1ED)

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
    onOpenParental: () -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
    onOpenChannels: () -> Unit = {},
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
    val uiScale by settings.uiScale.collectAsState()
    val wide = app.opentv.ui.LocalLayoutClass.current.isWide
    val live by settings.liveEnabled.collectAsState()
    val movies by settings.moviesEnabled.collectAsState()
    val shows by settings.seriesEnabled.collectAsState()
    val subtitles by settings.subtitlesEnabled.collectAsState()
    val resume by settings.resumeLastChannel.collectAsState()
    val livePause by settings.livePauseEnabled.collectAsState()
    val tmdb by settings.tmdbApiKey.collectAsState()
    val omdb by settings.omdbApiKey.collectAsState()
    val addons by settings.stremioAddons.collectAsState()
    val language by settings.languageTag.collectAsState()
    // Which sheet is open: "tmdb", "omdb", "language", or null.
    var sheet by remember { mutableStateOf<String?>(null) }
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
                    Text(stringResource(R.string.nav_settings), color = JaskInk, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
            }
            item {
                Group(null) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(active?.name ?: "?", 60.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(active?.name ?: "", color = JaskInk, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.jask_profile_local), color = JaskDim, style = MaterialTheme.typography.bodySmall)
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
                                Text(p.name, color = if (p.id == activeId) JaskInk else JaskDim, style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1, modifier = Modifier.widthIn(max = 64.dp).padding(top = 4.dp))
                            }
                        }
                        Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpenProfiles).padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(44.dp).clip(CircleShape).border(1.dp, Color.White.copy(alpha = .3f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Add, null, tint = JaskInk)
                            }
                            Text(stringResource(R.string.jask_profiles_manage), color = JaskDim, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            item {
                Group(stringResource(R.string.jask_browse)) {
                    Toggle(stringResource(R.string.jask_show_unavailable), stringResource(R.string.jask_show_unavailable_hint), everything) { vod.setShowUnavailable(it) }
                    if (sources.size > 1) {
                        JaskDivider()
                        Label(stringResource(R.string.jask_settings_sources_shown))
                        Chips(listOf(null to stringResource(R.string.catalogue_all_sources)) + sources.map { it.id to it.name }, { it == source }) { vod.selectVodSource(it) }
                    }
                    JaskDivider()
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
                    JaskDivider()
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
                    JaskDivider()
                    Link(Icons.Outlined.Language, stringResource(R.string.jask_settings_language),
                        OpenTvLanguages.firstOrNull { it.first == language }?.second ?: stringResource(R.string.settings_language_system)) { sheet = "language" }
                    if (wide) {
                        JaskDivider()
                        Label(stringResource(R.string.jask_settings_ui_scale))
                        Chips(listOf("auto" to stringResource(R.string.jask_glass_auto), "1.0" to "100%", "1.15" to "115%",
                            "1.3" to "130%", "1.5" to "150%"), { it == uiScale }) { settings.setUiScale(it) }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
            item {
                Group(stringResource(R.string.settings_section_content)) {
                    Toggle(stringResource(R.string.nav_live_tv), stringResource(R.string.settings_content_live_subtitle), live, settings::setLiveEnabled)
                    JaskDivider()
                    Toggle(stringResource(R.string.nav_movies), stringResource(R.string.settings_content_movies_subtitle), movies, settings::setMoviesEnabled)
                    JaskDivider()
                    Toggle(stringResource(R.string.nav_shows), stringResource(R.string.settings_content_series_subtitle), shows, settings::setSeriesEnabled)
                }
            }
            item {
                Group(stringResource(R.string.settings_section_playback)) {
                    Toggle(stringResource(R.string.settings_subtitles_title), stringResource(R.string.settings_subtitles_subtitle), subtitles, settings::setSubtitlesEnabled)
                    JaskDivider()
                    Toggle(stringResource(R.string.settings_resume_title), stringResource(R.string.settings_resume_subtitle), resume, settings::setResumeLastChannel)
                    JaskDivider()
                    Toggle(stringResource(R.string.jask_settings_live_pause), stringResource(R.string.jask_settings_live_pause_hint), livePause, settings::setLivePauseEnabled)
                    JaskDivider()
                    SleepTimerRows()
                }
            }
            if (live) item {
                Group(stringResource(R.string.nav_live_tv)) { LiveLayoutRows(settings) }
            }
            item {
                Group(stringResource(R.string.settings_recording_title)) { RecordingRows(settings) }
            }
            item {
                Group(stringResource(R.string.jask_settings_sources_guide)) {
                    Link(Icons.Outlined.Dns, stringResource(R.string.settings_providers_title),
                        pluralLabel(sources.size, sources.count { it.enabled }), onOpenProviders)
                    JaskDivider()
                    Link(Icons.Outlined.CalendarViewDay, stringResource(R.string.settings_guide_title), stringResource(R.string.settings_guide_subtitle), onOpenGuide)
                    JaskDivider()
                    Link(Icons.Outlined.LiveTv, stringResource(R.string.common_channels), stringResource(R.string.settings_channels_subtitle), onOpenChannels)
                }
            }
            item {
                Group(stringResource(R.string.jask_settings_services)) {
                    Link(Icons.Outlined.Movie, stringResource(R.string.jask_tmdb_title),
                        stringResource(if (tmdb.isBlank()) R.string.jask_tmdb_off else R.string.jask_tmdb_on)) { sheet = "tmdb" }
                    JaskDivider()
                    Link(Icons.Outlined.StarOutline, stringResource(R.string.jask_omdb_title),
                        stringResource(if (omdb.isBlank()) R.string.jask_omdb_off else R.string.jask_omdb_on)) { sheet = "omdb" }
                    JaskDivider()
                    Link(Icons.Outlined.Extension, stringResource(R.string.settings_addons_title),
                        if (addons.isEmpty()) stringResource(R.string.settings_addons_subtitle) else "${addons.size} installed", onOpenAddons)
                    JaskDivider()
                    Link(Icons.Outlined.PhoneAndroid, stringResource(R.string.settings_webmanager_title), stringResource(R.string.settings_webmanager_subtitle), onOpenWebManager)
                }
            }
            item {
                Group(stringResource(R.string.jask_settings_more)) {
                    Link(Icons.Outlined.Lock, stringResource(R.string.settings_parental_title), stringResource(R.string.settings_parental_subtitle), onOpenParental)
                    JaskDivider()
                    Link(Icons.Outlined.Info, stringResource(R.string.settings_about_title), stringResource(R.string.settings_about_subtitle), onOpenAbout)
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }) {
        app.opentv.ui.JaskBarScrim(Modifier.align(Alignment.TopCenter), top = true)
        when (sheet) {
            "tmdb" -> KeySheet(stringResource(R.string.jask_tmdb_title), stringResource(R.string.settings_tmdb_note), tmdb,
                { settings.setTmdbApiKey(it) }) { sheet = null }
            "omdb" -> KeySheet(stringResource(R.string.jask_omdb_title), stringResource(R.string.jask_omdb_note), omdb,
                { settings.setOmdbApiKey(it) }) { sheet = null }
            "language" -> JaskSheet({ sheet = null }) {
                LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    item { LanguageRow(stringResource(R.string.settings_language_system), language.isBlank()) { sheet = null; changeLanguage(context, settings, "") } }
                    items(OpenTvLanguages.size) { i ->
                        val (tag, name) = OpenTvLanguages[i]
                        LanguageRow(name, language == tag) { sheet = null; changeLanguage(context, settings, tag) }
                    }
                }
            }
        }
        if (renaming && active != null) {
            var name by remember { mutableStateOf(active.name) }
            JaskSheet({ renaming = false }) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.profiles_rename_heading), color = JaskInk, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
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
internal fun Group(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(bottom = 18.dp)) {
        title?.let {
            Text(it.uppercase(), color = JaskDim, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp, bottom = 7.dp))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card), content = content)
    }
}

@Composable
internal fun JaskDivider() = Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(Rule))

@Composable
internal fun Label(text: String) = Text(text, color = JaskDim, style = MaterialTheme.typography.labelLarge,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp))

@Composable
internal fun Toggle(title: String, subtitle: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = JaskInk, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, color = JaskDim, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.width(12.dp))
        JaskGlassSwitch(on)
    }
}

@Composable
internal fun Link(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = JaskInk, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = JaskInk, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, color = JaskDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = JaskDim)
    }
}

@Composable
internal fun <T> Chips(items: List<Pair<T, String>>, selected: (T) -> Boolean, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (value, label) ->
            val on = selected(value)
            Text(label, color = if (on) Color(0xFF141312) else JaskInk, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) JaskInk else Color.White.copy(alpha = .08f))
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

@Composable
private fun LanguageRow(name: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = JaskInk, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (on) Icon(Icons.Filled.Check, null, tint = JaskInk)
    }
}

/** Edits one service's API key in place. Keys stay on this device; the field is masked. */
@Composable
internal fun KeySheet(title: String, note: String, saved: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var field by remember(saved) { mutableStateOf(saved) }
    JaskSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(title, color = JaskInk, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(note, color = JaskDim, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(field, { field = it.trim() }, singleLine = true, label = { Text(stringResource(R.string.jask_key_label)) },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (saved.isNotBlank()) TextButton(onClick = { onSave(""); onDismiss() }) { Text(stringResource(R.string.jask_key_remove)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                TextButton(onClick = { onSave(field); onDismiss() }, enabled = field.isNotBlank()) { Text(stringResource(R.string.common_save)) }
            }
        }
    }
}
