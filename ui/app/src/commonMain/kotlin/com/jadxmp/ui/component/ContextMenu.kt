package com.jadxmp.ui.component

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * One entry in a [ContextMenu]. Disabled items render dimmed and inert; [onClick] runs after the menu
 * has dismissed (so an action that opens another surface isn't fighting the closing popup).
 */
@Immutable
data class ContextMenuItem(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * A thin, generic wrapper over Material 3's [DropdownMenu], anchored at an [offset] measured from its
 * placement in the layout (pass a pointer position to open it under the cursor). It dismisses on an
 * outside click / back press through [onDismiss], and each item dismisses before firing.
 *
 * Deliberately kept free of any workbench types so it can back the code-area menu now and the tree /
 * tab menus later (P0#5). Compose-Multiplatform only — wasm-safe.
 */
@Composable
fun ContextMenu(
    expanded: Boolean,
    offset: DpOffset,
    onDismiss: () -> Unit,
    items: List<ContextMenuItem>,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        modifier = modifier,
    ) {
        for (item in items) {
            DropdownMenuItem(
                text = { Text(item.label, style = MaterialTheme.typography.bodyMedium) },
                enabled = item.enabled,
                onClick = {
                    // Close first, then act: an action that opens another popup/panel must not race the
                    // dismiss animation of this one.
                    onDismiss()
                    item.onClick()
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            )
        }
    }
}
