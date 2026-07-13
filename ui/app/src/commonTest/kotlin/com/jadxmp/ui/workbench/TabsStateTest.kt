package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.CodeView
import com.jadxmp.ui.client.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabsStateTest {
    private val a = NodeId("a")
    private val b = NodeId("b")
    private val c = NodeId("c")

    @Test
    fun opensAndActivatesNewTab() {
        val s = TabsState().open(a, "A", CodeView.JAVA)
        assertEquals(1, s.tabs.size)
        assertEquals(0, s.activeIndex)
        assertEquals(a, s.active?.nodeId)
    }

    @Test
    fun reopeningSameNodeAndViewReactivatesInsteadOfDuplicating() {
        val s = TabsState()
            .open(a, "A", CodeView.JAVA)
            .open(b, "B", CodeView.JAVA)
            .open(a, "A", CodeView.JAVA)
        assertEquals(2, s.tabs.size)
        assertEquals(a, s.active?.nodeId)
    }

    @Test
    fun sameNodeDifferentViewIsSeparateTab() {
        val s = TabsState()
            .open(a, "A", CodeView.JAVA)
            .open(a, "A", CodeView.SMALI)
        assertEquals(2, s.tabs.size)
        assertEquals(CodeView.SMALI, s.active?.view)
    }

    @Test
    fun insertsNewTabAfterActive() {
        val s = TabsState()
            .open(a, "A", CodeView.JAVA)
            .open(b, "B", CodeView.JAVA)
            .activate(0)
            .open(c, "C", CodeView.JAVA)
        assertEquals(listOf(a, c, b), s.tabs.map { it.nodeId })
        assertEquals(c, s.active?.nodeId)
    }

    @Test
    fun closingActivePrefersLeftNeighbour() {
        val s = TabsState()
            .open(a, "A", CodeView.JAVA)
            .open(b, "B", CodeView.JAVA)
            .open(c, "C", CodeView.JAVA) // active = c (index 2)
            .close(2)
        assertEquals(listOf(a, b), s.tabs.map { it.nodeId })
        assertEquals(b, s.active?.nodeId)
    }

    @Test
    fun closingMiddleActiveSelectsLeftNeighbour() {
        // [a, b, c] with b active; closing b should activate a (the LEFT neighbour), not c.
        val s = TabsState()
            .open(a, "A", CodeView.JAVA)
            .open(b, "B", CodeView.JAVA)
            .open(c, "C", CodeView.JAVA)
            .activate(1) // active = b (index 1)
            .close(1)
        assertEquals(listOf(a, c), s.tabs.map { it.nodeId })
        assertEquals(a, s.active?.nodeId)
        assertEquals(0, s.activeIndex)
    }

    @Test
    fun closingFirstActiveKeepsRightNeighbour() {
        // Closing the first tab while it is active has no left neighbour → the new first tab (b) wins.
        val s = TabsState()
            .open(a, "A", CodeView.JAVA)
            .open(b, "B", CodeView.JAVA)
            .activate(0)
            .close(0)
        assertEquals(listOf(b), s.tabs.map { it.nodeId })
        assertEquals(b, s.active?.nodeId)
        assertEquals(0, s.activeIndex)
    }

    @Test
    fun closingTabBeforeActiveShiftsActiveIndex() {
        val s = TabsState()
            .open(a, "A", CodeView.JAVA)
            .open(b, "B", CodeView.JAVA) // active index 1 (b)
            .close(0)
        assertEquals(b, s.active?.nodeId)
        assertEquals(0, s.activeIndex)
    }

    @Test
    fun closingLastTabEmpties() {
        val s = TabsState().open(a, "A", CodeView.JAVA).close(0)
        assertTrue(s.isEmpty)
        assertEquals(-1, s.activeIndex)
        assertNull(s.active)
    }

    @Test
    fun closeOthersKeepsTargetAndPinned() {
        val s = TabsState()
            .open(a, "A", CodeView.JAVA)
            .open(b, "B", CodeView.JAVA)
            .open(c, "C", CodeView.JAVA)
            .togglePin(0) // pin a
            .closeOthers(2) // keep c
        assertEquals(setOf(a, c), s.tabs.map { it.nodeId }.toSet())
        assertEquals(c, s.active?.nodeId)
    }

    @Test
    fun togglePinFlips() {
        val s = TabsState().open(a, "A", CodeView.JAVA)
        assertFalse(s.active!!.pinned)
        assertTrue(s.togglePin(0).active!!.pinned)
    }

    @Test
    fun updateCaretPersistsPerTab() {
        val s = TabsState().open(a, "A", CodeView.JAVA).updateCaret(0, 42)
        assertEquals(42, s.active?.caret)
    }

    @Test
    fun caretSurvivesSwitchingTabsRoundTrip() {
        // Set a caret on tab a, move to b, come back to a → a's caret is intact (per-tab restore).
        val s = TabsState()
            .open(a, "A", CodeView.JAVA)
            .updateCaret(0, 37) // caret on a
            .open(b, "B", CodeView.JAVA) // now on b
            .updateCaret(1, 5) // caret on b
        assertEquals(37, s.activate(0).active?.caret)
        assertEquals(5, s.activate(1).active?.caret)
    }

    @Test
    fun setActiveViewSwitchesViewAndResetsCaret() {
        val s = TabsState().open(a, "A", CodeView.JAVA).updateCaret(0, 10).setActiveView(CodeView.SMALI)
        assertEquals(CodeView.SMALI, s.active?.view)
        assertEquals(0, s.active?.caret)
    }
}
