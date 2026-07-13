package com.jadxmp.ui.workbench

import androidx.compose.runtime.Immutable
import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.NodeKind
import com.jadxmp.ui.client.TreeKind
import com.jadxmp.ui.client.TreeNode

/** Expansion / selection / flatten / filter state for one tree. Pure and immutable. */
@Immutable
data class TreeUiState(
    val kind: TreeKind = TreeKind.CLASSES,
    val expanded: Set<NodeId> = emptySet(),
    val selected: NodeId? = null,
    /** Collapse single-child package chains into dotted rows (`a.b.c`); see [buildVisibleRows]. */
    val flattenPackages: Boolean = false,
    val filter: String = "",
) {
    fun toggleExpanded(id: NodeId): TreeUiState =
        copy(expanded = if (id in expanded) expanded - id else expanded + id)

    fun expand(id: NodeId): TreeUiState = if (id in expanded) this else copy(expanded = expanded + id)

    fun select(id: NodeId): TreeUiState = copy(selected = id)

    fun switchTree(kind: TreeKind): TreeUiState = copy(kind = kind)

    fun setFilter(text: String): TreeUiState = copy(filter = text)

    fun setFlatten(value: Boolean): TreeUiState = copy(flattenPackages = value)
}

/** A flattened, ready-to-render tree row: the node plus its indent depth and expander state. */
@Immutable
data class TreeRowItem(
    val node: TreeNode,
    val depth: Int,
    val expanded: Boolean,
)

/**
 * Flatten the visible tree into a linear list for a lazy list, honouring expansion, package-flatten,
 * and a substring filter (which auto-reveals matching subtrees and keeps ancestors of matches).
 *
 * **Flatten packages** (jadx's behaviour): a chain of packages each having a *single* sub-package
 * child and nothing else is collapsed into one dotted row — `a › b › c` renders as `a.b.c` (the row
 * keeps the deepest package's id, so expanding/loading still targets the real leaf package). It is
 * *not* "auto-expand every package". Collapse and filtering are mutually exclusive: while a filter is
 * active, flatten is ignored so the match-reveal logic stays simple.
 *
 * Pure: [childrenOf] is injected so this is fully testable without a client or coroutines. It only
 * descends into (and collapses through) children already provided by [childrenOf] (the cache),
 * matching the lazy-load model — a chain collapses as deep as its packages are loaded, and the filter
 * only sees loaded nodes (the view-model eagerly loads the subtree when a filter is set; see
 * `WorkbenchState.setFilter`).
 */
fun buildVisibleRows(
    roots: List<TreeNode>,
    childrenOf: (NodeId) -> List<TreeNode>,
    expanded: Set<NodeId>,
    flattenPackages: Boolean = false,
    filter: String = "",
): List<TreeRowItem> {
    val needle = filter.trim()
    val filtering = needle.isNotEmpty()

    fun matches(node: TreeNode): Boolean =
        !filtering || node.label.contains(needle, ignoreCase = true)

    fun subtreeMatches(node: TreeNode): Boolean {
        if (matches(node)) return true
        if (!node.hasChildren) return false
        return childrenOf(node.id).any { subtreeMatches(it) }
    }

    // Follow a single-package-child chain, folding names into a dotted label. Stops as soon as a
    // package has zero or many children, or its lone child is not a package (e.g. a single class is
    // never merged into its package). Returns the deepest package with the merged label, or [node].
    fun collapsePackageChain(node: TreeNode): TreeNode {
        var current = node
        var label = node.label
        while (current.hasChildren) {
            val onlyChild = childrenOf(current.id).singleOrNull()
            if (onlyChild == null || onlyChild.kind != NodeKind.PACKAGE) break
            label += ".${onlyChild.label}"
            current = onlyChild
        }
        return if (current === node) node else current.copy(label = label)
    }

    val result = ArrayList<TreeRowItem>()

    fun walk(nodes: List<TreeNode>, depth: Int) {
        for (node in nodes) {
            if (filtering && !subtreeMatches(node)) continue
            val display = if (!filtering && flattenPackages && node.kind == NodeKind.PACKAGE) {
                collapsePackageChain(node)
            } else {
                node
            }
            val isOpen = display.hasChildren && (display.id in expanded || filtering)
            result.add(TreeRowItem(display, depth, isOpen))
            if (isOpen) walk(childrenOf(display.id), depth + 1)
        }
    }

    walk(roots, 0)
    return result
}
