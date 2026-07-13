package com.jadxmp.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jadxmp.ui.client.TreeKind
import com.jadxmp.ui.client.TreeNode
import com.jadxmp.ui.component.PaneHeader
import com.jadxmp.ui.component.SearchField
import com.jadxmp.ui.component.SegmentedToggle
import com.jadxmp.ui.component.ToolbarTextButton
import com.jadxmp.ui.component.TreeRow
import com.jadxmp.ui.theme.JadxTheme

/**
 * Left navigation pane: Classes/Resources selector, a package-flatten toggle, a filter field, and the
 * virtualized tree. The visible rows are computed by the pure [buildVisibleRows]; expansion loads
 * children lazily through the client.
 */
@Composable
fun TreePane(
    state: WorkbenchUiState,
    onSwitchTree: (TreeKind) -> Unit,
    onFilter: (String) -> Unit,
    onToggleFlatten: () -> Unit,
    onActivate: (TreeNode) -> Unit,
    onToggle: (TreeNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tree = state.tree
    val roots = state.roots[tree.kind].orEmpty()
    val rows = buildVisibleRows(
        roots = roots,
        childrenOf = { state.children(it) },
        expanded = tree.expanded,
        flattenPackages = tree.flattenPackages,
        filter = tree.filter,
    )

    val kinds = listOf(TreeKind.CLASSES, TreeKind.RESOURCES)
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PaneHeader(title = "Project") {
            ToolbarTextButton(
                label = "Flatten",
                onClick = onToggleFlatten,
                selected = tree.flattenPackages,
            )
        }
        // Source / Resources selector
        Row(
            Modifier.fillMaxWidth().padding(horizontal = JadxTheme.spacing.lg, vertical = JadxTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(JadxTheme.spacing.xs),
        ) {
            SegmentedToggle(
                options = listOf("Classes", "Resources"),
                selectedIndex = kinds.indexOf(tree.kind).coerceAtLeast(0),
                onSelect = { onSwitchTree(kinds[it]) },
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = JadxTheme.spacing.lg, vertical = JadxTheme.spacing.xs)) {
            SearchField(value = tree.filter, onValueChange = onFilter, placeholder = "Filter tree…")
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = JadxTheme.spacing.xs)) {
            items(rows, key = { it.node.id.value }) { row ->
                TreeRow(
                    item = row,
                    selected = row.node.id == tree.selected,
                    onActivate = { onActivate(row.node) },
                    onToggle = { onToggle(row.node) },
                )
            }
        }
    }
}
