package app.opentv.update

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Asks once per new version, over any screen: "Update to x.y.z?" Install / Later (d-pad friendly: two buttons). */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val updater = remember { AppUpdater.get(context) }
    val state by updater.state.collectAsState()
    var hidden by remember { mutableStateOf(false) }
    val info = (state as? AppUpdater.State.Available)?.info ?: return
    if (hidden || info.versionCode == updater.dismissed) return
    AlertDialog(
        onDismissRequest = { hidden = true },
        title = { Text("Update to jaskTV ${info.versionName}?") },
        text = { Text(info.notes.ifBlank { "A new version is ready. It downloads now and asks you to confirm the install." }) },
        confirmButton = { TextButton(onClick = { hidden = true; updater.install(info) }) { Text("Install") } },
        dismissButton = { TextButton(onClick = { updater.dismissed = info.versionCode; hidden = true }) { Text("Later") } },
    )
}
