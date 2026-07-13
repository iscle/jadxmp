package com.jadxmp.ui.workbench

import androidx.compose.runtime.Immutable
import com.jadxmp.ui.client.CodeView
import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.NodeKind

/** One open editor tab. Identity is (nodeId, view) so Java and Smali of the same class can coexist. */
@Immutable
data class EditorTab(
    val nodeId: NodeId,
    val title: String,
    val view: CodeView,
    /** What the node is (class/method/field/…). Drives the breadcrumb's member rendering; null = unknown. */
    val kind: NodeKind? = null,
    val pinned: Boolean = false,
    /**
     * Caret line (1-based), persisted per tab so re-selecting a tab restores the reading position.
     * 0 means "unset" — the viewer treats it as line 1. Fed by the code viewer's `onCaretLine`.
     */
    val caret: Int = 0,
)

/**
 * Pure, immutable tab-strip model. All transitions return a new [TabsState] — no coroutines, no
 * Compose — so the open/close/pin/activate/caret-restore rules are fully unit-testable in commonTest.
 */
@Immutable
data class TabsState(
    val tabs: List<EditorTab> = emptyList(),
    val activeIndex: Int = -1,
) {
    val active: EditorTab? get() = tabs.getOrNull(activeIndex)
    val isEmpty: Boolean get() = tabs.isEmpty()

    /** Open (or re-activate) a tab for a node+view, inserting it just after the active tab. */
    fun open(nodeId: NodeId, title: String, view: CodeView, kind: NodeKind? = null): TabsState {
        val existing = tabs.indexOfFirst { it.nodeId == nodeId && it.view == view }
        if (existing >= 0) return copy(activeIndex = existing)
        val insertAt = if (activeIndex in tabs.indices) activeIndex + 1 else tabs.size
        val updated = tabs.toMutableList().apply { add(insertAt, EditorTab(nodeId, title, view, kind)) }
        return copy(tabs = updated, activeIndex = insertAt)
    }

    fun activate(index: Int): TabsState = if (index in tabs.indices) copy(activeIndex = index) else this

    /**
     * Close a tab, keeping the active selection sensible. When the active tab itself is closed we
     * activate the **neighbour to the left** (`activeIndex - 1`), the usual IDE convention; closing
     * the first tab clamps to 0, so its right neighbour takes over. Closing a tab before the active
     * one shifts the active index down; closing one after it leaves the active tab untouched.
     */
    fun close(index: Int): TabsState {
        if (index !in tabs.indices) return this
        val updated = tabs.toMutableList().apply { removeAt(index) }
        val newActive = when {
            updated.isEmpty() -> -1
            index < activeIndex -> activeIndex - 1
            index == activeIndex -> (activeIndex - 1).coerceIn(0, updated.lastIndex)
            else -> activeIndex
        }
        return copy(tabs = updated, activeIndex = newActive)
    }

    /** Close every tab except [index] and any pinned tabs. */
    fun closeOthers(index: Int): TabsState {
        val keep = tabs.getOrNull(index) ?: return this
        val remaining = tabs.filter { it === keep || it.pinned }
        return copy(tabs = remaining, activeIndex = remaining.indexOf(keep))
    }

    fun togglePin(index: Int): TabsState {
        if (index !in tabs.indices) return this
        val updated = tabs.toMutableList()
        updated[index] = updated[index].copy(pinned = !updated[index].pinned)
        return copy(tabs = updated)
    }

    /** Record the caret offset for a tab so it can be restored on re-selection. */
    fun updateCaret(index: Int, caret: Int): TabsState {
        if (index !in tabs.indices || tabs[index].caret == caret) return this
        val updated = tabs.toMutableList()
        updated[index] = updated[index].copy(caret = caret)
        return copy(tabs = updated)
    }

    /** Switch the active tab to a different source view (Java ↔ Smali ↔ Kotlin) in place. */
    fun setActiveView(view: CodeView): TabsState {
        val i = activeIndex
        if (i !in tabs.indices || tabs[i].view == view) return this
        val updated = tabs.toMutableList()
        updated[i] = updated[i].copy(view = view, caret = 0)
        return copy(tabs = updated)
    }
}
