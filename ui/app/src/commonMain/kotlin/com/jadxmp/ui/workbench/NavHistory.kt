package com.jadxmp.ui.workbench

import androidx.compose.runtime.Immutable
import com.jadxmp.ui.client.NodeId

/**
 * Linear back/forward navigation history (like a browser). Visiting a node after going back
 * truncates the forward entries. Pure and immutable → unit-tested in commonTest.
 */
@Immutable
data class NavHistory(
    val entries: List<NodeId> = emptyList(),
    val cursor: Int = -1,
) {
    val current: NodeId? get() = entries.getOrNull(cursor)
    val canGoBack: Boolean get() = cursor > 0
    val canGoForward: Boolean get() = entries.isNotEmpty() && cursor < entries.lastIndex

    /** Navigate to [node]. No-op if it is already current; otherwise drops forward history. */
    fun visit(node: NodeId): NavHistory {
        if (current == node) return this
        val kept = entries.take(cursor + 1)
        return NavHistory(kept + node, kept.size)
    }

    /**
     * Move the cursor onto an existing entry **without** truncating anything. Used when the user
     * merely re-focuses an already-open tab: that is not a new navigation, so the forward stack must
     * survive (unlike [visit]). If [node] is not in the history the state is returned unchanged, so
     * switching to a tab that fell out of history never rewrites it.
     */
    fun moveTo(node: NodeId): NavHistory {
        if (current == node) return this
        val idx = entries.indexOf(node)
        return if (idx < 0) this else copy(cursor = idx)
    }

    fun back(): NavHistory = if (canGoBack) copy(cursor = cursor - 1) else this

    fun forward(): NavHistory = if (canGoForward) copy(cursor = cursor + 1) else this
}
