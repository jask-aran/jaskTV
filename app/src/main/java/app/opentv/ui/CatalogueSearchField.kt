/* jaskTV — GPL-3.0-or-later. */
package app.opentv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.opentv.R

/** A compact text field that filters this destination without navigating away. */
@Composable
internal fun CatalogueSearchField(
    query: String, onQuery: (String) -> Unit, hint: String,
    onClose: (() -> Unit)? = null, autoFocus: Boolean = false,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (autoFocus) { focus.requestFocus(); keyboard?.show() }
    }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = query, onValueChange = onQuery, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
            .height(36.dp).focusRequester(focus)
            .background(colors.surfaceContainerLow, shape),
        decorationBox = { input ->
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant, maxLines = 1)
                    input()
                }
                if (query.isNotEmpty() || onClose != null) IconButton(onClick = {
                    if (query.isNotEmpty()) onQuery("") else { keyboard?.hide(); onClose?.invoke() }
                }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, stringResource(if (query.isNotEmpty())
                        R.string.search_recent_clear else R.string.common_cancel), Modifier.size(18.dp))
                }
            }
        },
    )
}
