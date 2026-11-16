package app.opentv.ui.channels

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.opentv.data.model.Channel
import app.opentv.livetv.MyTvRepository
import kotlinx.coroutines.launch

@Composable
internal fun AddToMyTvButton(channel: Channel, compact: Boolean = false) {
    val context = LocalContext.current
    val repository = remember(context) { MyTvRepository(context) }
    val scope = rememberCoroutineScope()
    var adding by remember(channel.id) { mutableStateOf(false) }
    var added by remember(channel.id) { mutableStateOf(false) }
    var error by remember(channel.id) { mutableStateOf(false) }
    TextButton(enabled = !adding && !added, contentPadding = if (compact) androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp) else ButtonDefaults.TextButtonContentPadding,
        modifier = if (compact) androidx.compose.ui.Modifier.heightIn(min = 32.dp) else androidx.compose.ui.Modifier, onClick = {
        adding = true; error = false
        scope.launch {
            try { repository.add(channel); added = true }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = true }
            finally { adding = false }
        }
    }) { Text(when { added -> "Added"; adding -> "Adding…"; error -> "Retry add"; else -> if (compact) "+ Add" else "Add to My TV" }) }
}
