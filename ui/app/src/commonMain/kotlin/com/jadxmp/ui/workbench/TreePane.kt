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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.NodeKind
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
    onEnsureChildrenLoaded: (NodeId) -> Unit = {},
    onExpandSubtree: (TreeNode) -> Unit = {},
    onCollapseSubtree: (TreeNode) -> Unit = {},
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

    // Eager package-chain compaction: with Flatten on (and no filter), a single-child package chain
    // must collapse into one dotted row without a click. buildVisibleRows only folds through children
    // already cached, but children load lazily on expand — so here we walk each package root's
    // single-child chain and pull the next uncached hop into the cache, one hop per recomposition.
    // The effect re-runs when the cache grows (a key), resuming the walk until the chain ends at a
    // branch point or a class. Only single-child package chains are loaded — never the whole tree.
    LaunchedEffect(tree.flattenPackages, tree.filter, roots, state.childrenCache) {
        if (!tree.flattenPackages || tree.filter.isNotBlank()) return@LaunchedEffect
        for (root in roots) {
            if (root.kind != NodeKind.PACKAGE) continue
            var node = root
            while (node.hasChildren) {
                val cached = state.childrenCache[node.id]
                if (cached == null) {
                    // Not loaded yet: pull this hop in and stop; the walk resumes next recomposition.
                    onEnsureChildrenLoaded(node.id)
                    break
                }
                val onlyChild = cached.singleOrNull()
                if (onlyChild != null && onlyChild.kind == NodeKind.PACKAGE) {
                    node = onlyChild // single package child — keep folding down the chain.
                } else {
                    break // branch point (0 or many children) or a class reached — chain ends.
                }
            }
        }
    }

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
        // Reveal in tree: when the workbench arms a reveal ([WorkbenchUiState.revealNonce] bumped by
        // revealInTree *after* it has expanded the target's ancestors), scroll the now-visible target
        // on-screen. Keyed only on the nonce, so ordinary selection/expansion never yanks the list; rows
        // already reflects the expanded ancestors (same recomposition), so the flattened index resolves here.
        val listState = rememberLazyListState()
        LaunchedEffect(state.revealNonce) {
            val target = state.revealTarget ?: return@LaunchedEffect
            val index = rows.indexOfFirst { it.node.id == target }
            // Bias a couple of rows up so the target lands with a little context, not pinned to the very top.
            if (index >= 0) listState.scrollToItem((index - REVEAL_CONTEXT_ROWS).coerceAtLeast(0))
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = JadxTheme.spacing.xs), state = listState) {
            items(rows, key = { it.node.id.value }) { row ->
                TreeRow(
                    item = row,
                    selected = row.node.id == tree.selected,
                    onActivate = { onActivate(row.node) },
                    onToggle = { onToggle(row.node) },
                    onExpandSubtree = { onExpandSubtree(row.node) },
                    onCollapseSubtree = { onCollapseSubtree(row.node) },
                )
            }
        }
    }
}

/** Rows of context kept above a revealed tree node when scrolling it into view (see [TreePane]). */
private const val REVEAL_CONTEXT_ROWS: Int = 2
