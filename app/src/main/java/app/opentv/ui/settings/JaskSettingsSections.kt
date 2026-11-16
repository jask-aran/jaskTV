package app.opentv.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.SleepTimer
import app.opentv.core.findActivity
import android.content.Context
import app.opentv.core.isIgnoringBatteryOptimizations
import app.opentv.core.requestIgnoreBatteryOptimizations
import app.opentv.recording.RecordingStorage
import app.opentv.recording.SmbClient
import app.opentv.recording.SmbConfig
import app.opentv.ui.JaskGlassCircle
import app.opentv.ui.JaskSheet
import app.opentv.ui.LayoutClass
import app.opentv.ui.LocalLayoutClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * jaskTV Settings sections that replace the old OpenTV pages: sleep timer, Live TV layout and
 * recordings (background permission, padding, where recordings are saved). They write the same
 * AppSettings keys the old pages did, so nothing about recording or playback changes.
 */

/** Sleep timer: Off or a preset; shows the minutes left while it runs. */
@Composable
internal fun SleepTimerRows() {
    val deadline by SleepTimer.deadline.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadline) { while (deadline != null) { now = System.currentTimeMillis(); delay(30_000) } }
    val left = deadline?.let { (((it - now).coerceAtLeast(0L)) + 59_999L) / 60_000L }
    Label(if (left == null) stringResource(R.string.settings_sleep_timer)
        else stringResource(R.string.settings_sleep_timer) + " · " + stringResource(R.string.settings_sleep_on_desc, left.toInt()))
    Chips(listOf(0 to stringResource(R.string.settings_sleep_off)) + SleepTimer.presets.map { it to "$it min" },
        { if (it == 0) deadline == null else false }) { if (it == 0) SleepTimer.clear() else SleepTimer.armMinutes(it) }
}

/** Live TV layout: list or guide on phones; guide grid or list, plus previews, on wide screens. */
@Composable
internal fun LiveLayoutRows(settings: AppSettings) {
    if (LocalLayoutClass.current == LayoutClass.PHONE) {
        val layout by settings.phoneLiveLayout.collectAsState()
        Label(stringResource(R.string.phone_guide_layout))
        Chips(listOf(AppSettings.PhoneLiveLayout.LIST to stringResource(R.string.phone_guide_list),
            AppSettings.PhoneLiveLayout.GUIDE to stringResource(R.string.phone_guide_guide)), { it == layout }) { settings.setPhoneLiveLayout(it) }
    } else {
        val layout by settings.channelLayout.collectAsState()
        val video by settings.guidePreviewVideo.collectAsState()
        val sound by settings.guidePreviewSound.collectAsState()
        Label(stringResource(R.string.settings_channel_layout_title))
        Chips(listOf(AppSettings.ChannelLayout.GRID to stringResource(R.string.settings_channel_layout_grid),
            AppSettings.ChannelLayout.LIST to stringResource(R.string.settings_channel_layout_list)), { it == layout }) { settings.setChannelLayout(it) }
        JaskDivider()
        Toggle(stringResource(R.string.settings_live_preview_title), stringResource(R.string.settings_live_preview_subtitle), video, settings::setGuidePreviewVideo)
        JaskDivider()
        Toggle(stringResource(R.string.settings_preview_sound_title), stringResource(R.string.settings_preview_sound_subtitle), sound, settings::setGuidePreviewSound)
    }
}

/** Recordings: background permission, start-early/end-late, auto-switch, and where files go. */
@Composable
internal fun RecordingRows(settings: AppSettings) {
    val context = LocalContext.current
    val target by settings.recordingTarget.collectAsState()
    val usbTree by settings.usbTreeUri.collectAsState()
    val usbLabel by settings.usbFolderLabel.collectAsState()
    val padStart by settings.recordPadStartMinutes.collectAsState()
    val padEnd by settings.recordPadEndMinutes.collectAsState()
    val autoSwitch by settings.recordAutoSwitch.collectAsState()
    var nasSheet by remember { mutableStateOf(false) }

    // Re-read on resume: the user grants it in a system dialog and comes back.
    var allowed by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) allowed = context.isIgnoringBatteryOptimizations() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Link(if (allowed) Icons.Outlined.BatteryChargingFull else Icons.Outlined.BatteryAlert, stringResource(R.string.recset_background_title),
        if (allowed) stringResource(R.string.recset_background_on_title)
        else stringResource(R.string.recset_background_not_allowed) + " · " + stringResource(R.string.recset_background_fix)) {
        if (!allowed) context.requestIgnoreBatteryOptimizations()
    }
    JaskDivider()
    Stepper(stringResource(R.string.recset_pad_start), padStart, 0, 30) { settings.setRecordPadding(it, padEnd) }
    JaskDivider()
    Stepper(stringResource(R.string.recset_pad_end), padEnd, 0, 60) { settings.setRecordPadding(padStart, it) }
    JaskDivider()
    Toggle(stringResource(R.string.recset_autoswitch), stringResource(R.string.recset_autoswitch_desc), autoSwitch, settings::setRecordAutoSwitch)
    JaskDivider()

    // A USB folder is granted with the system picker (no storage permission); the grant is kept.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            val label = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: context.getString(R.string.recset_usb_label)
            settings.setUsbTree(uri.toString(), label)
            settings.setRecordingTarget(AppSettings.RecordingTarget.USB)
        }
    }
    Label(stringResource(R.string.recset_save_to))
    Chips(listOf(AppSettings.RecordingTarget.INTERNAL to "This device", AppSettings.RecordingTarget.USB to "USB drive",
        AppSettings.RecordingTarget.SMB to "Network share"), { it == target }) {
        when (it) {
            // Only switch to USB once a folder is granted, so recordings never have nowhere to go.
            AppSettings.RecordingTarget.USB -> if (usbTree == null) runCatching { picker.launch(null) } else settings.setRecordingTarget(it)
            AppSettings.RecordingTarget.SMB -> nasSheet = true
            else -> settings.setRecordingTarget(it)
        }
    }
    when (target) {
        AppSettings.RecordingTarget.USB -> Link(Icons.Outlined.Folder, usbLabel ?: stringResource(R.string.recset_usb_none),
            stringResource(R.string.recset_usb_change)) { runCatching { picker.launch(null) } }
        AppSettings.RecordingTarget.SMB -> Link(Icons.Outlined.Lan, stringResource(R.string.recset_nas_connection),
            settings.smbHost.value.let { h -> if (h.isBlank()) null else "\\\\$h\\${settings.smbShare.value}" }) { nasSheet = true }
        else -> InternalStorage()
    }
    if (nasSheet) NasSheet(settings) { nasSheet = false }
}

@Composable
private fun InternalStorage() {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        text = withContext(Dispatchers.IO) {
            val dir = RecordingStorage.internalDir(context)
            val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
            "${files.size} recordings · ${bytes(files.sumOf { it.length() })} used · ${bytes(dir.usableSpace)} free"
        }
    }
    Link(Icons.Outlined.Storage, stringResource(R.string.recset_internal_label), text ?: stringResource(R.string.recset_storage_reading)) {}
}

private fun bytes(n: Long): String {
    val mb = n / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.0f MB", mb)
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = JaskInk, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        JaskGlassCircle(Icons.Filled.Remove, "−", size = 36.dp) { if (value > min) onChange(value - 1) }
        Text("$value ${stringResource(R.string.recset_minutes_short)}", color = JaskInk, style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 64.dp))
        JaskGlassCircle(Icons.Filled.Add, "+", size = 36.dp) { if (value < max) onChange(value + 1) }
    }
}

/** NAS (SMB) connection: server, share, folder and login, with a connection test. Saved on this device only. */
@Composable
private fun NasSheet(settings: AppSettings, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(settings.smbHost.value) }
    var share by remember { mutableStateOf(settings.smbShare.value) }
    var folder by remember { mutableStateOf(settings.smbFolder.value) }
    var user by remember { mutableStateOf(settings.smbUser.value) }
    var password by remember { mutableStateOf(settings.smbPassword.value) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    JaskSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.recset_nas_connection), color = JaskInk, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.recset_smb_subtitle), color = JaskDim, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(host, { host = it.trim() }, label = { Text(stringResource(R.string.recset_field_server)) }, placeholder = { Text("192.168.1.10") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(share, { share = it.trim() }, label = { Text(stringResource(R.string.recset_field_share)) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(folder, { folder = it.trim() }, label = { Text(stringResource(R.string.recset_field_folder)) }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.recset_field_username)) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.recset_field_password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
            }
            status?.let { Text(it, color = JaskDim, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !testing && host.isNotBlank() && share.isNotBlank(), onClick = {
                    testing = true
                    status = context.getString(R.string.recset_status_testing)
                    val config = SmbConfig(host, share, folder, user, password)
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { SmbClient.test(config) } }
                        testing = false
                        status = result.fold({ context.getString(R.string.recset_status_connected) },
                            { context.getString(R.string.recset_status_failed, it.message ?: it.javaClass.simpleName) })
                    }
                }) { Text(stringResource(R.string.recset_test_connection)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                TextButton(enabled = host.isNotBlank() && share.isNotBlank(), onClick = {
                    settings.setSmbConfig(host, share, folder, user, password)
                    settings.setRecordingTarget(AppSettings.RecordingTarget.SMB)
                    onDismiss()
                }) { Text(stringResource(R.string.common_save)) }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * The UI languages OpenTV ships translations for, each labelled with its own endonym. The tag is
 * both the stored language tag and the resource qualifier (values-<tag>), so adding a language is
 * just a new `values-xx/strings.xml` plus a line here.
 */
internal val OpenTvLanguages: List<Pair<String, String>> = listOf(
    "en" to "English",
    "es" to "Español",
    "fr" to "Français",
    "de" to "Deutsch",
    "it" to "Italiano",
    "pt" to "Português",
    "nl" to "Nederlands",
    "pl" to "Polski",
    "ru" to "Русский",
    "tr" to "Türkçe",
    "ar" to "العربية",
    "zh" to "中文",
    "ja" to "日本語",
    "ko" to "한국어",
    "hi" to "हिन्दी",
    "sv" to "Svenska",
    "da" to "Dansk",
    "fi" to "Suomi",
    "nb" to "Norsk",
    "cs" to "Čeština",
    "el" to "Ελληνικά",
    "ro" to "Română",
    "hu" to "Magyar",
    "uk" to "Українська",
    "id" to "Bahasa Indonesia",
    "th" to "ไทย",
    "vi" to "Tiếng Việt",
    "bg" to "Български",
    "sk" to "Slovenčina",
    "hr" to "Hrvatski",
    "fa" to "فارسی",
)

/** Persist the chosen language and recreate the activity so the whole UI reloads translated. */
internal fun changeLanguage(context: Context, settings: AppSettings, tag: String) {
    if (settings.languageTag.value == tag) return
    settings.setLanguageTag(tag)
    context.findActivity()?.recreate()
}

