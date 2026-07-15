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
    /** Marked by the user for quick return (P1#9). Independent of [pinned]; renders a small badge. */
    val bookmarked: Boolean = false,
    /**
     * Caret line (1-based), persisted per tab so re-selecting a tab restores the reading position.
     * 0 means "unset" — the viewer treats it as line 1. Fed by the code viewer's `onCaretLine`.
     */
    val caret: Int = 0,
)

/** Stable identity of an open tab — a node shown in one view. Keys the last-used ordering ([TabsState.recent]). */
@Immutable
data class TabId(val nodeId: NodeId, val view: CodeView)

/** This tab's stable identity (unchanged by caret/title/pin/bookmark copies; changes only with the view). */
val EditorTab.tabId: TabId get() = TabId(nodeId, view)

/**
 * Pure, immutable tab-strip model. All transitions return a new [TabsState] — no coroutines, no
 * Compose — so the open/close/pin/bookmark/activate/caret-restore/last-used rules are fully
 * unit-testable in commonTest.
 */
@Immutable
data class TabsState(
    val tabs: List<EditorTab> = emptyList(),
    val activeIndex: Int = -1,
    /**
     * Most-recently-used tab identities, most-recent first. Every [open]/[activate] moves a tab to the
     * front; closes prune it. Drives Ctrl+Tab last-used switching (see [lastUsedIndex]). Identity-based
     * (not index-based) so it survives insert/remove/reorder without re-indexing.
     */
    val recent: List<TabId> = emptyList(),
) {
    val active: EditorTab? get() = tabs.getOrNull(activeIndex)
    val isEmpty: Boolean get() = tabs.isEmpty()

    /** Open (or re-activate) a tab for a node+view, inserting it just after the active tab. */
    fun open(nodeId: NodeId, title: String, view: CodeView, kind: NodeKind? = null): TabsState {
        val id = TabId(nodeId, view)
        val existing = tabs.indexOfFirst { it.nodeId == nodeId && it.view == view }
        if (existing >= 0) return copy(activeIndex = existing, recent = recent.promote(id))
        val insertAt = if (activeIndex in tabs.indices) activeIndex + 1 else tabs.size
        val updated = tabs.toMutableList().apply { add(insertAt, EditorTab(nodeId, title, view, kind)) }
        return copy(tabs = updated, activeIndex = insertAt, recent = recent.promote(id))
    }

    fun activate(index: Int): TabsState =
        if (index in tabs.indices) copy(activeIndex = index, recent = recent.promote(tabs[index].tabId)) else this

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
        return copy(tabs = updated, activeIndex = newActive, recent = recent.prunedTo(updated))
    }

    /** Close every tab except [index] and any pinned tabs. */
    fun closeOthers(index: Int): TabsState {
        val keep = tabs.getOrNull(index) ?: return this
        val remaining = tabs.filter { it === keep || it.pinned }
        return copy(tabs = remaining, activeIndex = remaining.indexOf(keep), recent = recent.prunedTo(remaining))
    }

    /** Close every tab to the LEFT of [index] (pinned tabs are always kept). */
    fun closeToLeft(index: Int): TabsState {
        if (index !in tabs.indices) return this
        return keeping(tabs.filterIndexed { i, t -> i >= index || t.pinned }, anchor = tabs[index].tabId)
    }

    /** Close every tab to the RIGHT of [index] (pinned tabs are always kept). */
    fun closeToRight(index: Int): TabsState {
        if (index !in tabs.indices) return this
        return keeping(tabs.filterIndexed { i, t -> i <= index || t.pinned }, anchor = tabs[index].tabId)
    }

    /** Close every tab except pinned ones. */
    fun closeAll(): TabsState = keeping(tabs.filter { it.pinned }, anchor = null)

    fun togglePin(index: Int): TabsState {
        if (index !in tabs.indices) return this
        val updated = tabs.toMutableList()
        updated[index] = updated[index].copy(pinned = !updated[index].pinned)
        return copy(tabs = updated)
    }

    /** Flip the bookmark flag for a tab (mirrors [togglePin]); identity is unchanged so [recent] stands. */
    fun toggleBookmark(index: Int): TabsState {
        if (index !in tabs.indices) return this
        val updated = tabs.toMutableList()
        updated[index] = updated[index].copy(bookmarked = !updated[index].bookmarked)
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
        val oldId = tabs[i].tabId
        val newId = TabId(tabs[i].nodeId, view)
        val updated = tabs.toMutableList()
        updated[i] = updated[i].copy(view = view, caret = 0)
        // The tab's identity moved with its view; keep its place in the last-used order under the new id.
        return copy(tabs = updated, recent = recent.map { if (it == oldId) newId else it })
    }

    /**
     * Index of the tab to switch to on Ctrl+Tab: the most-recently-used tab that isn't the current
     * active one, or null when there is no such tab (0/1 tabs). Because every open/activate promotes a
     * tab to the front of [recent], the active tab is normally `recent[0]` and this returns `recent[1]`,
     * so repeated presses toggle between the two most-recent tabs.
     */
    fun lastUsedIndex(): Int? {
        val activeId = active?.tabId
        for (id in recent) {
            if (id == activeId) continue
            val idx = tabs.indexOfFirst { it.tabId == id }
            if (idx >= 0) return idx
        }
        return null
    }

    /**
     * Replace [tabs] with [kept] (a subset in original order), choosing a sensible active tab: the
     * currently-active tab if it survived, otherwise the [anchor] tab (the right-clicked one), otherwise
     * the first remaining tab. A no-op when nothing was actually removed.
     */
    private fun keeping(kept: List<EditorTab>, anchor: TabId?): TabsState {
        if (kept.size == tabs.size) return this
        val activeId = active?.tabId
        val newActive = when {
            kept.isEmpty() -> -1
            else -> {
                val byActive = activeId?.let { id -> kept.indexOfFirst { it.tabId == id } } ?: -1
                when {
                    byActive >= 0 -> byActive
                    anchor != null -> kept.indexOfFirst { it.tabId == anchor }.coerceAtLeast(0)
                    else -> 0
                }
            }
        }
        return copy(tabs = kept, activeIndex = newActive, recent = recent.prunedTo(kept))
    }
}

/** Move [id] to the front of a most-recently-used list, de-duplicating any prior occurrence. */
private fun List<TabId>.promote(id: TabId): List<TabId> = listOf(id) + filterNot { it == id }

/** Drop any last-used entries whose tab no longer exists after a close. */
private fun List<TabId>.prunedTo(tabs: List<EditorTab>): List<TabId> {
    val live = tabs.mapTo(HashSet()) { it.tabId }
    return filter { it in live }
}
