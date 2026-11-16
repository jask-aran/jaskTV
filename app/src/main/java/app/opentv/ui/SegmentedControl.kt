/* jaskTV — GPL-3.0-or-later. */
package app.opentv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Mutually exclusive filters, with one quiet selection surface. */
@Composable
internal fun <T> SegmentedControl(
    options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.height(34.dp).clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(3.dp)) {
        options.forEach { (value, label) ->
            val active = value == selected
            var focused by remember(value) { mutableStateOf(false) }
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(7.dp))
                .background(if (active) MaterialTheme.colorScheme.surfaceContainerHighest
                    else androidx.compose.ui.graphics.Color.Transparent)
                .border(if (focused) 2.dp else 0.dp,
                    if (focused) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                    RoundedCornerShape(7.dp))
                .onFocusChanged { focused = it.isFocused }
                .selectable(active, role = Role.Tab, onClick = { onSelect(value) })
                .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
