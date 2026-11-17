package app.opentv.ui.settings

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.local.isLocalPlaylist
import app.opentv.data.local.playlistDisplayName
import app.opentv.data.local.safePlaylistDisplayName
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StremioAddon
import app.opentv.data.model.shownName
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.EpgViewModel
import app.opentv.ui.JaskGlassCircle
import app.opentv.ui.JaskGlassSwitch
import app.opentv.ui.JaskSheet
import app.opentv.ui.JaskSurfaceHost
import app.opentv.ui.JaskWordmark
import app.opentv.ui.ProfilesViewModel
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.channels.readClipboardText
import app.opentv.ui.jaskWheelScroll
import app.opentv.ui.toggleCategoryKey
import app.opentv.ui.vod.JaskTheme
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * jaskTV settings pages that replace the OpenTV ones: providers (and the add/edit form, which is
 * also first-run setup), guides, parental controls, profiles, add-ons and channels. They use the
 * same view models and settings as before; only the screens are new.
 */

/** A settings sub-page: Back and title on top, then [content] as groups in one scrolling column. */
@Composable
internal fun JaskSettingsPage(title: String, onBack: (() -> Unit)?, action: (@Composable RowScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit) = JaskTheme {
    val list = rememberLazyListState()
    JaskSurfaceHost(Modifier.fillMaxSize().background(Color(0xFF0E0D0D)), background = {
        LazyColumn(Modifier.fillMaxSize().jaskWheelScroll(list), state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().statusBarsPadding().padding(top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) {
                        JaskGlassCircle(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), size = 44.dp, onClick = onBack)
                        Spacer(Modifier.width(14.dp))
                    }
                    Text(title, color = JaskInk, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    action?.invoke(this)
                }
            }
            content()
        }
    }) { app.opentv.ui.JaskBarScrim(Modifier.align(Alignment.TopCenter), top = true) }
}

/** Pill button: white for the main action, glass-grey otherwise. */
@Composable
internal fun JaskPill(label: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.height(40.dp).clip(RoundedCornerShape(50))
        .background(if (primary) Color.White.copy(alpha = if (enabled) 1f else .4f) else Color.White.copy(alpha = .1f))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (primary) Color(0xFF141312) else JaskInk.copy(alpha = if (enabled) 1f else .45f),
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A line of explanation under a page title or inside a group. */
@Composable
internal fun JaskNote(text: String, color: Color = JaskDim, modifier: Modifier = Modifier) =
    Text(text, color = color, style = MaterialTheme.typography.bodySmall, modifier = modifier.padding(horizontal = 16.dp, vertical = 10.dp))

/** A page-width block (notes, buttons) outside a group, aligned with the groups. */
internal fun LazyListScope.pageItem(content: @Composable () -> Unit) = item {
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(bottom = 14.dp)) { content() }
}

private fun hostOf(url: String): String = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

private val ErrorInk = Color(0xFFFFB4A8)

// ---- Providers ------------------------------------------------------------------------------

@Composable
fun JaskProvidersPage(onAddSource: () -> Unit, onEditSource: (Source) -> Unit, onBack: () -> Unit,
    viewModel: SourcesViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsState()
    var removing by remember { mutableStateOf<Source?>(null) }
    var importing by remember { mutableStateOf<Source?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val source = importing
        importing = null
        if (source != null && uri != null) viewModel.importLocal(source, uri)
    }
    JaskSettingsPage(stringResource(R.string.settings_providers_title), onBack, action = {
        JaskPill(stringResource(R.string.providers_add), primary = true, onClick = onAddSource)
    }) {
        ui.syncMessage?.let { message -> pageItem { JaskNote(message, JaskInk) } }
        if (ui.sources.isEmpty()) pageItem { JaskNote(stringResource(R.string.providers_empty)) }
        else pageItem {
            Row(Modifier.padding(horizontal = 6.dp)) {
                JaskPill(if (ui.syncing) stringResource(R.string.onboarding_working) else "Refresh all", enabled = !ui.syncing) { viewModel.refreshAll() }
            }
        }
        items(ui.sources, key = { it.id }) { source ->
            Group(null) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name, color = JaskInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (source.isLocalPlaylist) stringResource(R.string.local_playlist_file) + " · " + safePlaylistDisplayName(source.localPlaylistDisplayName)
                            else "${source.kind.name} · ${hostOf(source.url)}", color = JaskDim, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (source.isLocalPlaylist) TextButton(enabled = !ui.syncing, onClick = { importing = source; picker.launch(arrayOf("*/*")) }) {
                        Text(stringResource(R.string.local_playlist_reimport))
                    }
                    TextButton(onClick = { onEditSource(source) }) { Text(stringResource(R.string.common_edit)) }
                    TextButton(onClick = { removing = source }) { Text(stringResource(R.string.common_remove), color = ErrorInk) }
                }
                // Stream format is an Xtream panel setting; M3U sources carry their own URLs.
                if (source.kind == SourceKind.XTREAM) {
                    JaskDivider()
                    Label(stringResource(R.string.provider_stream_format))
                    Chips(listOf(LiveStreamFormat.HLS to stringResource(R.string.provider_stream_format_hls),
                        LiveStreamFormat.MPEG_TS to stringResource(R.string.provider_stream_format_ts)), { it == source.liveFormat }) {
                        viewModel.setLiveFormat(source, it)
                    }
                }
            }
        }
    }
    removing?.let { source ->
        JaskTheme {
            JaskSheet({ removing = null }) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(source.name, color = JaskInk, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.providers_remove_confirm), color = JaskDim, style = MaterialTheme.typography.bodyMedium)
                    // Signed in, a playlist (other than one imported from a file) is removed from every device on the account.
                    if (!source.isLocalPlaylist && app.opentv.account.AccountManager.get(androidx.compose.ui.platform.LocalContext.current).status.collectAsState().value
                            is app.opentv.account.AccountManager.Status.Ready)
                        Text("It's also removed from your other signed-in devices.", Modifier.padding(top = 6.dp), color = JaskDim,
                            style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        JaskPill(stringResource(R.string.common_cancel)) { removing = null }
                        JaskPill(stringResource(R.string.providers_yes_remove), primary = true) { viewModel.delete(source); removing = null }
                    }
                }
            }
        }
    }
}

/**
 * Add or edit a provider (also first-run setup). Tests before saving, so a wrong password is
 * explained here rather than discovered as an empty guide later; credentials stay on the device.
 */
@Composable
fun JaskSourceForm(viewModel: SourcesViewModel, onFinished: () -> Unit, editingSourceId: Long? = null, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsState()
    val existing = remember(editingSourceId, ui.sources) { editingSourceId?.let { id -> ui.sources.firstOrNull { it.id == id } } }
    var kind by remember(existing?.id) { mutableStateOf(existing?.kind ?: SourceKind.XTREAM) }
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var url by remember(existing?.id) { mutableStateOf(existing?.url ?: "") }
    var username by remember(existing?.id) { mutableStateOf(existing?.username ?: "") }
    var password by remember(existing?.id) { mutableStateOf(existing?.password ?: "") }
    var mac by remember(existing?.id) { mutableStateOf(existing?.macAddress ?: "") }
    var epgUrl by remember(existing?.id) { mutableStateOf(existing?.epgUrl ?: "") }
    var userAgent by remember(existing?.id) { mutableStateOf(existing?.userAgent ?: Source.DEFAULT_USER_AGENT) }
    var localMode by remember(existing?.id) { mutableStateOf(existing?.isLocalPlaylist == true) }
    var advanced by remember { mutableStateOf(false) }
    var file by remember(existing?.id) { mutableStateOf<Uri?>(null) }
    var fileName by remember(existing?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        fileName = file?.let { uri -> withContext(Dispatchers.IO) {
            safePlaylistDisplayName(runCatching { context.contentResolver.playlistDisplayName(uri) }.getOrNull())
        } }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) file = uri }
    val isLocal = kind == SourceKind.M3U && localMode
    fun draft(): Source = (existing ?: Source(name = "", kind = kind, url = "")).copy(
        name = name.ifBlank { context.getString(if (kind == SourceKind.M3U) R.string.onboarding_default_playlist_name else R.string.onboarding_default_provider_name) },
        kind = kind, url = if (isLocal) "" else url, username = username.takeIf { it.isNotBlank() },
        password = password.takeIf { it.isNotBlank() }, macAddress = mac.takeIf { it.isNotBlank() },
        epgUrl = epgUrl.takeIf { it.isNotBlank() }, userAgent = userAgent.ifBlank { Source.DEFAULT_USER_AGENT })
    val canSubmit = if (isLocal) file != null || existing?.isLocalPlaylist == true else url.isNotBlank() && when (kind) {
        SourceKind.XTREAM -> username.isNotBlank() && password.isNotBlank()
        SourceKind.M3U -> true
        SourceKind.STALKER -> mac.isNotBlank()
    }
    val locked = existing?.isLocalPlaylist == true || ui.syncing

    JaskSettingsPage(stringResource(if (existing != null) R.string.settings_edit_provider_title else R.string.onboarding_add_provider_title), onBack) {
        if (onBack == null) pageItem { Box(Modifier.padding(horizontal = 6.dp)) { JaskWordmark(Modifier.height(22.dp)) } }
        pageItem { JaskNote(stringResource(R.string.onboarding_add_provider_desc)) }
        item {
            Group(null) {
                Label("Type")
                Chips(listOf(SourceKind.XTREAM to stringResource(R.string.provider_picker_xtream), SourceKind.M3U to stringResource(R.string.provider_picker_m3u),
                    SourceKind.STALKER to stringResource(R.string.provider_picker_stalker)), { it == kind }) { if (!locked) kind = it }
                if (kind == SourceKind.M3U) {
                    Chips(listOf(false to stringResource(R.string.local_playlist_url), true to stringResource(R.string.local_playlist_file)),
                        { it == localMode }) { if (!locked && (existing == null || !it)) localMode = it }
                }
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.epg_name_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (isLocal) {
                        Text(stringResource(R.string.local_playlist_help), color = JaskDim, style = MaterialTheme.typography.bodySmall)
                        (fileName ?: existing?.localPlaylistDisplayName?.let(::safePlaylistDisplayName))?.let { Text(it, color = JaskInk) }
                        Row { JaskPill(stringResource(if (existing == null) R.string.local_playlist_choose else R.string.local_playlist_reimport), enabled = !ui.syncing) {
                            picker.launch(arrayOf("*/*")) } }
                    } else {
                        OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(when (kind) {
                                SourceKind.XTREAM -> R.string.onboarding_server_address
                                SourceKind.M3U -> R.string.onboarding_playlist_url
                                SourceKind.STALKER -> R.string.onboarding_portal_url })) },
                            supportingText = { Text(stringResource(when (kind) {
                                SourceKind.XTREAM -> R.string.onboarding_server_help
                                SourceKind.M3U -> R.string.onboarding_playlist_help
                                SourceKind.STALKER -> R.string.onboarding_portal_help })) },
                            trailingIcon = { PasteButton { url += it } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    }
                    if (kind == SourceKind.XTREAM) {
                        OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.recset_field_username)) }, singleLine = true,
                            trailingIcon = { PasteButton { username += it } }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.recset_field_password)) }, singleLine = true,
                            trailingIcon = { PasteButton { password += it } }, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    }
                    if (kind == SourceKind.STALKER) OutlinedTextField(mac, { mac = it }, label = { Text(stringResource(R.string.onboarding_mac_address)) },
                        supportingText = { Text(stringResource(R.string.onboarding_mac_help)) }, singleLine = true,
                        trailingIcon = { PasteButton { mac += it } }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item {
            Group(null) {
                Toggle(stringResource(R.string.onboarding_advanced), null, advanced) { advanced = it }
                if (advanced) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(epgUrl, { epgUrl = it }, label = { Text(stringResource(R.string.onboarding_guide_url_optional)) },
                        supportingText = { Text(stringResource(R.string.onboarding_guide_blank_help)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(userAgent, { userAgent = it }, label = { Text("User-Agent") },
                        supportingText = { Text(stringResource(R.string.onboarding_user_agent_help)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        pageItem {
            ui.testResult?.let { JaskNote(it, JaskInk) }
            ui.testError?.let { JaskNote(it, ErrorInk) }
            ui.syncMessage?.let { JaskNote(it, JaskInk) }
            Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isLocal) JaskPill(stringResource(R.string.recset_test_connection), enabled = canSubmit && !ui.testing && !ui.syncing) { viewModel.test(draft()) }
                JaskPill(stringResource(when {
                    ui.syncing -> R.string.onboarding_working
                    isLocal && file != null -> if (existing == null) R.string.local_playlist_import else R.string.local_playlist_reimport
                    isLocal -> R.string.common_save
                    else -> R.string.onboarding_save_load
                }), primary = true, enabled = canSubmit && !ui.testing && !ui.syncing) {
                    val uri = file
                    if (isLocal && uri != null) viewModel.importLocal(draft(), uri) { ok -> if (ok) onFinished() }
                    else viewModel.saveAndSync(draft()) { ok -> if (ok) onFinished() }
                }
                if (ui.testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            JaskNote(stringResource(R.string.onboarding_privacy_note), modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/** Appends the clipboard to a field: long URLs and logins are miserable to type with a remote. */
@Composable
private fun PasteButton(onPaste: (String) -> Unit) {
    val context = LocalContext.current
    IconButton(onClick = {
        val pasted = readClipboardText(context)
        if (pasted != null) onPaste(pasted)
        else Toast.makeText(context, context.getString(R.string.kbd_clipboard_empty), Toast.LENGTH_SHORT).show()
    }) { Icon(Icons.Filled.ContentPaste, stringResource(R.string.kbd_paste)) }
}

// ---- Guides ---------------------------------------------------------------------------------

/** Guide feeds: the provider's own, the built-in free ones, and XMLTV URLs you add. Each shows its last result. */
@Composable
fun JaskGuidesPage(onBack: () -> Unit, viewModel: EpgViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsState()
    var adding by remember { mutableStateOf(false) }
    JaskSettingsPage(stringResource(R.string.settings_guide_title), onBack, action = {
        JaskPill(stringResource(if (ui.syncing) R.string.epg_updating else R.string.epg_update_now), enabled = !ui.syncing) { viewModel.refresh() }
    }) {
        pageItem {
            ui.statusLine?.let { JaskNote(it, JaskInk) }
            JaskNote(stringResource(R.string.epg_desc))
        }
        item {
            Group(null) {
                ui.feeds.forEachIndexed { i, feed ->
                    if (i > 0) JaskDivider()
                    Row(Modifier.fillMaxWidth().clickable { viewModel.setEnabled(feed, !feed.enabled) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(feed.name, color = JaskInk, style = MaterialTheme.typography.bodyLarge)
                            if (feed.lastResult.isNotEmpty()) Text(feed.lastResult, color = JaskDim, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        // Only feeds you added can be removed; provider and built-in ones are switched off instead.
                        if (!feed.builtIn && feed.providerSourceId == null) TextButton(onClick = { viewModel.remove(feed) }) {
                            Text(stringResource(R.string.common_remove), color = ErrorInk)
                        }
                        JaskGlassSwitch(feed.enabled)
                    }
                }
            }
        }
        pageItem { Row(Modifier.padding(horizontal = 6.dp)) { JaskPill(stringResource(R.string.epg_add_own)) { adding = true } } }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        JaskTheme {
            JaskSheet({ adding = false }) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.epg_add_own), color = JaskInk, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.epg_name_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(url, { url = it.trim() }, label = { Text(stringResource(R.string.epg_xmltv_url)) }, singleLine = true,
                        trailingIcon = { PasteButton { url += it.trim() } }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        JaskPill(stringResource(R.string.common_cancel)) { adding = false }
                        JaskPill(stringResource(R.string.epg_add_guide), primary = true, enabled = url.isNotBlank()) { viewModel.addCustom(name, url); adding = false }
                    }
                }
            }
        }
    }
}

// ---- Parental controls ----------------------------------------------------------------------

/**
 * A four-digit family PIN and categories hidden from Live TV. With a PIN set, this page itself is
 * behind the PIN, so a child can't come here and unlock.
 */
@Composable
fun JaskParentalPage(onBack: () -> Unit, channels: ChannelsViewModel = viewModel()) {
    val context = LocalContext.current
    val settings = remember { ServiceLocator.get(context).settings }
    val pinIsSet by settings.pinIsSet.collectAsState()
    val hidden by settings.hiddenCategories.collectAsState()
    val unlocked by settings.hiddenUnlocked.collectAsState()
    val categories by channels.categoryGroups.collectAsState()
    var authed by remember { mutableStateOf(false) }
    var pinSheet by remember { mutableStateOf(false) }
    BackHandler { onBack() }

    if (pinIsSet && !authed) {
        var pin by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }
        JaskSettingsPage(stringResource(R.string.settings_parental_title), onBack) {
            pageItem {
                JaskNote(stringResource(R.string.parental_enter_pin), JaskInk)
                OutlinedTextField(pin, { if (it.length <= 4) pin = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.parental_pin)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.padding(horizontal = 16.dp).width(200.dp))
                if (wrong) JaskNote(stringResource(R.string.parental_wrong_pin), ErrorInk)
                Row(Modifier.padding(16.dp)) {
                    JaskPill(stringResource(R.string.parental_unlock), primary = true, enabled = pin.length == 4) {
                        if (settings.verifyPin(pin)) authed = true else { wrong = true; pin = "" }
                    }
                }
            }
        }
        return
    }

    JaskSettingsPage(stringResource(R.string.settings_parental_title), onBack) {
        item {
            Group(stringResource(R.string.parental_pin)) {
                Link(Icons.Filled.Star, stringResource(if (pinIsSet) R.string.parental_change_pin else R.string.parental_pin_set),
                    if (pinIsSet) stringResource(R.string.parental_pin_is_set) else null) { pinSheet = true }
                if (pinIsSet) {
                    JaskDivider()
                    Row(Modifier.fillMaxWidth().clickable { settings.clearPin() }.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(stringResource(R.string.parental_remove_pin), color = ErrorInk, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        item {
            Group(stringResource(R.string.parental_hidden_categories)) {
                JaskNote(stringResource(R.string.parental_hidden_desc))
                Toggle(stringResource(R.string.parental_show_hidden_title), stringResource(R.string.parental_show_hidden_subtitle), unlocked, settings::setHiddenUnlocked)
                if (categories.isEmpty()) JaskNote(stringResource(R.string.parental_no_categories))
                categories.forEach { group ->
                    JaskDivider()
                    Toggle(group.label, null, group.matches(hidden)) { on -> settings.setHiddenCategories(toggleCategoryKey(hidden, group, categories, on)) }
                }
            }
        }
    }
    if (pinSheet) {
        var pin by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        val mismatch = stringResource(R.string.parental_pin_mismatch)
        JaskTheme {
            JaskSheet({ pinSheet = false }) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(if (pinIsSet) R.string.parental_pin_enter_new else R.string.parental_pin_set), color = JaskInk,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(stringResource(R.string.parental_pin_new) to pin, stringResource(R.string.parental_pin_confirm) to confirm).forEachIndexed { i, (label, value) ->
                            OutlinedTextField(value, { v -> if (v.length <= 4) { if (i == 0) pin = v.filter(Char::isDigit) else confirm = v.filter(Char::isDigit) } },
                                label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.weight(1f))
                        }
                    }
                    error?.let { Text(it, color = ErrorInk, style = MaterialTheme.typography.bodySmall) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        JaskPill(stringResource(R.string.common_cancel)) { pinSheet = false }
                        JaskPill(stringResource(R.string.parental_save_pin), primary = true, enabled = pin.length == 4 && confirm.length == 4) {
                            if (pin != confirm) error = mismatch else { settings.setPin(pin); authed = true; pinSheet = false }
                        }
                    }
                }
            }
        }
    }
}

// ---- Profiles -------------------------------------------------------------------------------

/** Local profiles: whose watch history is in play. Switch, add, rename, remove (the first can't be removed). */
@Composable
fun JaskProfilesPage(onBack: () -> Unit, viewModel: ProfilesViewModel = viewModel()) {
    val profiles by viewModel.profiles.collectAsState()
    val activeId by viewModel.activeProfileId.collectAsState()
    // null = closed; -1 = new profile; otherwise the profile being renamed.
    var editing by remember { mutableStateOf<Long?>(null) }
    JaskSettingsPage(stringResource(R.string.profiles_title), onBack, action = {
        JaskPill(stringResource(R.string.profiles_add), primary = true) { editing = -1L }
    }) {
        item {
            Group(null) {
                profiles.forEachIndexed { i, profile ->
                    if (i > 0) JaskDivider()
                    val active = profile.id == activeId
                    Row(Modifier.fillMaxWidth().clickable { viewModel.select(profile.id) }.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, color = JaskInk, style = MaterialTheme.typography.bodyLarge, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                            if (active) Text(stringResource(R.string.profiles_active), color = JaskDim, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { editing = profile.id }) { Text(stringResource(R.string.profiles_rename)) }
                        if (profile.id != 1L) TextButton(onClick = { viewModel.remove(profile.id) }) { Text(stringResource(R.string.common_remove), color = ErrorInk) }
                    }
                }
            }
        }
    }
    editing?.let { target ->
        var name by remember(target) { mutableStateOf(if (target == -1L) "" else profiles.firstOrNull { it.id == target }?.name.orEmpty()) }
        JaskTheme {
            JaskSheet({ editing = null }) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(if (target == -1L) R.string.profiles_new_heading else R.string.profiles_rename_heading), color = JaskInk,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(name, { if (it.length <= 24) name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        JaskPill(stringResource(R.string.common_cancel)) { editing = null }
                        JaskPill(stringResource(R.string.common_save), primary = true, enabled = name.isNotBlank()) {
                            if (target == -1L) viewModel.addProfile(name) else viewModel.rename(target, name)
                            editing = null
                        }
                    }
                }
            }
        }
    }
}

// ---- Add-ons --------------------------------------------------------------------------------

class StremioAddonsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    val addons: StateFlow<List<StremioAddon>> = graph.settings.stremioAddons

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data class Added(val name: String) : Status
        data object Invalid : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Validates a pasted manifest URL against the live add-on, and stores it if it checks out. */
    fun add(rawUrl: String) {
        val url = rawUrl.trim()
        if (url.isBlank()) return
        viewModelScope.launch {
            _status.value = Status.Checking
            val name = withContext(Dispatchers.IO) { graph.stremioClient.fetchManifest(url) }
            _status.value = if (name != null) {
                graph.settings.addStremioAddon(StremioAddon(manifestUrl = url, name = name))
                Status.Added(name)
            } else Status.Invalid
        }
    }

    fun remove(addon: StremioAddon) = graph.settings.removeStremioAddon(addon.manifestUrl)
    fun clearStatus() { _status.value = Status.Idle }
}

/** Stremio add-ons: extra sources on movie pages. You bring your own manifest URL; none are bundled. */
@Composable
fun JaskAddonsPage(onBack: () -> Unit) {
    val viewModel: StremioAddonsViewModel = viewModel()
    val addons by viewModel.addons.collectAsState()
    val status by viewModel.status.collectAsState()
    var input by remember { mutableStateOf("") }
    LaunchedEffect(status) { if (status is StremioAddonsViewModel.Status.Added) input = "" }
    JaskSettingsPage(stringResource(R.string.settings_addons_title), onBack) {
        pageItem { JaskNote(stringResource(R.string.addons_note)) }
        item {
            Group(null) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(input, { input = it; if (status !is StremioAddonsViewModel.Status.Idle) viewModel.clearStatus() },
                        label = { Text(stringResource(R.string.addons_url_label)) }, singleLine = true,
                        trailingIcon = { PasteButton { input += it.trim() } }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        JaskPill(stringResource(R.string.addons_add), primary = true,
                            enabled = input.isNotBlank() && status !is StremioAddonsViewModel.Status.Checking) { viewModel.add(input) }
                        when (val s = status) {
                            StremioAddonsViewModel.Status.Checking -> Text(stringResource(R.string.addons_checking), color = JaskDim)
                            is StremioAddonsViewModel.Status.Added -> Text(stringResource(R.string.addons_added, s.name), color = JaskInk)
                            StremioAddonsViewModel.Status.Invalid -> Text(stringResource(R.string.addons_invalid), color = ErrorInk)
                            StremioAddonsViewModel.Status.Idle -> {}
                        }
                    }
                }
            }
        }
        item {
            Group(null) {
                if (addons.isEmpty()) JaskNote(stringResource(R.string.addons_empty))
                addons.forEachIndexed { i, addon ->
                    if (i > 0) JaskDivider()
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(addon.name, color = JaskInk, style = MaterialTheme.typography.bodyLarge)
                            // Host only: a personalised manifest URL can carry a key.
                            Text(hostOf(addon.manifestUrl), color = JaskDim, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { viewModel.remove(addon) }) { Text(stringResource(R.string.common_remove), color = ErrorInk) }
                    }
                }
            }
        }
    }
}

// ---- Channels -------------------------------------------------------------------------------

/**
 * Channels: search, or browse one category at a time (a flat list of a 20,000-channel provider is
 * unusable), and favourite or hide each. Hidden channels stay listed here, dimmed, so they can be
 * brought back.
 */
@Composable
fun JaskChannelsPage(onBack: () -> Unit, viewModel: ChannelsViewModel = viewModel()) {
    val sources by viewModel.sources.collectAsState()
    val groups by viewModel.managerCategoryGroups.collectAsState()
    val selectedSource by viewModel.managerSelectedSource.collectAsState()
    val selectedCategory by viewModel.managerSelectedCategory.collectAsState()
    val browse by viewModel.managerRows.collectAsState()
    val results by viewModel.managerResults.collectAsState()
    var query by remember { mutableStateOf("") }
    LaunchedEffect(query) { viewModel.setManagerQuery(query) }
    LaunchedEffect(groups) { if (selectedCategory == null && groups.isNotEmpty()) viewModel.selectManagerCategory(groups.first().key) }
    val searching = query.trim().length >= 2
    val rows = if (searching) results else browse

    JaskSettingsPage(stringResource(R.string.channels_manager_title), onBack) {
        item {
            Group(null) {
                OutlinedTextField(query, { query = it.take(40) }, label = { Text(stringResource(R.string.nav_search)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp))
                if (!searching) {
                    if (sources.size > 1) {
                        Label(stringResource(R.string.channels_manager_source_header))
                        Chips(listOf<Long?>(null) .plus(sources.map { it.id }).map { id ->
                            id to (sources.firstOrNull { it.id == id }?.name ?: stringResource(R.string.channels_manager_all_sources))
                        }, { it == selectedSource }) { viewModel.selectManagerSource(it) }
                    }
                    Label(stringResource(R.string.channels_manager_categories_header))
                    LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(groups, key = { it.key }) { group ->
                            val on = group.key == selectedCategory
                            Text(group.label, color = if (on) Color(0xFF141312) else JaskInk, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) JaskInk else Color.White.copy(alpha = .08f))
                                    .clickable { viewModel.selectManagerCategory(group.key) }.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                }
            }
        }
        val empty = when {
            searching && rows.isEmpty() -> "No channels match \u201c${query.trim()}\u201d"
            !searching && groups.isEmpty() -> "No channels yet"
            !searching && rows.isEmpty() -> "Nothing in this category"
            else -> null
        }
        if (empty != null) pageItem { JaskNote(empty) }
        items(rows, key = { it.key }) { row ->
            val hidden = row.variants.all { it.hidden }
            Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1B1A19)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(row.primary.logoUrl, null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.primary.shownName, color = JaskInk.copy(alpha = if (hidden) .5f else 1f), style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(if (hidden) R.string.channels_hidden else R.string.channels_showing), color = JaskDim, style = MaterialTheme.typography.bodySmall)
                }
                JaskGlassCircle(if (row.primary.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    stringResource(if (row.primary.favourite) R.string.common_remove_favourite else R.string.common_favourite), size = 40.dp,
                    active = row.primary.favourite) { viewModel.toggleFavourite(row) }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.clip(RoundedCornerShape(50)).clickable { viewModel.setRowHidden(row, !hidden) }.padding(4.dp)) { JaskGlassSwitch(!hidden) }
            }
        }
    }
}
