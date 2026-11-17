package app.opentv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Search's on-screen keyboard on a TV, inside the app (no system keyboard covering the screen): letters, digits,
 * space and delete, typed with the d-pad; results filter as you type. Back, or the hide key, puts it away and
 * leaves the results to browse; OK on the search bar brings it back.
 */
@Composable
internal fun JaskTvKeyboard(onType: (String) -> Unit, onBackspace: () -> Unit, onClear: () -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xF0181716)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("abcdefghijklm", "nopqrstuvwxyz", "1234567890").forEachIndexed { row, keys ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                keys.forEachIndexed { i, c ->
                    Key(c.toString(), 44.dp, if (row == 0 && i == 0) Modifier.focusRequester(first) else Modifier) { onType(c.toString()) }
                }
                if (row == 2) {
                    Key(null, 98.dp, icon = Icons.Filled.SpaceBar, label = "Space") { onType(" ") }
                    Key(null, 44.dp, icon = Icons.AutoMirrored.Filled.Backspace, label = "Delete") { onBackspace() }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key("Clear", 120.dp) { onClear() }
            Key(null, 120.dp, icon = Icons.Filled.KeyboardHide, label = "Hide keyboard") { onHide() }
        }
    }
}

@Composable
private fun Key(text: String?, width: Dp, modifier: Modifier = Modifier, icon: ImageVector? = null, label: String? = null, onClick: () -> Unit) =
    Box(modifier.width(width).height(44.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .08f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        if (icon != null) Icon(icon, label, tint = Color(0xFFF4F1ED))
        else Text(text.orEmpty(), color = Color(0xFFF4F1ED), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    }
