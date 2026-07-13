package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavHistoryTest {
    private val a = NodeId("a")
    private val b = NodeId("b")
    private val c = NodeId("c")

    @Test
    fun visitAppendsAndAdvancesCursor() {
        val h = NavHistory().visit(a).visit(b)
        assertEquals(b, h.current)
        assertTrue(h.canGoBack)
        assertFalse(h.canGoForward)
    }

    @Test
    fun visitingCurrentIsNoOp() {
        val h = NavHistory().visit(a).visit(a)
        assertEquals(1, h.entries.size)
    }

    @Test
    fun backAndForwardMoveCursorWithoutMutatingEntries() {
        val h = NavHistory().visit(a).visit(b).visit(c)
        val back = h.back()
        assertEquals(b, back.current)
        assertTrue(back.canGoForward)
        assertEquals(c, back.forward().current)
    }

    @Test
    fun visitAfterBackTruncatesForwardHistory() {
        val h = NavHistory().visit(a).visit(b).visit(c).back().back() // at a
        val branched = h.visit(NodeId("d"))
        assertEquals(listOf(a, NodeId("d")), branched.entries)
        assertFalse(branched.canGoForward)
    }

    @Test
    fun moveToExistingEntryRepositionsCursorWithoutTruncating() {
        // [a, b, c] then Back to a. Re-focusing tab c (moveTo) must NOT drop the forward stack.
        val h = NavHistory().visit(a).visit(b).visit(c).back().back() // at a, forward = [b, c]
        val moved = h.moveTo(c)
        assertEquals(listOf(a, b, c), moved.entries) // nothing truncated
        assertEquals(c, moved.current)
        // Re-focusing b instead keeps c reachable via Forward.
        val toB = h.moveTo(b)
        assertEquals(b, toB.current)
        assertTrue(toB.canGoForward)
        assertEquals(c, toB.forward().current)
    }

    @Test
    fun moveToUnknownEntryIsANoOp() {
        val h = NavHistory().visit(a).visit(b) // at b
        val moved = h.moveTo(NodeId("z"))
        assertEquals(h, moved)
    }

    @Test
    fun backAtStartIsClamped() {
        val h = NavHistory().visit(a)
        assertFalse(h.canGoBack)
        assertEquals(a, h.back().current)
    }
}
