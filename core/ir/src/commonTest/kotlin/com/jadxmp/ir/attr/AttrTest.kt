package com.jadxmp.ir.attr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttrTest {

    /** A minimal concrete node for exercising the base attribute API. */
    private class Node : AttrNode()

    @Test
    fun typedGetSetRoundTrip() {
        val key = AttrKey<String>("NAME")
        val node = Node()
        assertNull(node[key])
        assertFalse(node.contains(key))

        node[key] = "hello"
        assertEquals("hello", node[key])
        assertTrue(node.contains(key))

        node.remove(key)
        assertNull(node[key])
    }

    @Test
    fun keysAreIdentityNotName() {
        val a = AttrKey<Int>("SAME")
        val b = AttrKey<Int>("SAME")
        val node = Node()
        node[a] = 1
        node[b] = 2
        assertEquals(1, node[a]) // distinct keys despite equal names
        assertEquals(2, node[b])
    }

    @Test
    fun flags() {
        val node = Node()
        assertFalse(node.contains(AttrFlag.SYNTHETIC))
        node.add(AttrFlag.SYNTHETIC)
        assertTrue(node.contains(AttrFlag.SYNTHETIC))
        node.remove(AttrFlag.SYNTHETIC)
        assertFalse(node.contains(AttrFlag.SYNTHETIC))
    }

    @Test
    fun errorAttribute() {
        val node = Node()
        node[IrAttrs.ERROR] = DecompileError("boom")
        node.add(AttrFlag.HAS_ERROR)
        assertEquals("boom", node[IrAttrs.ERROR]?.message)
        assertTrue(node.contains(AttrFlag.HAS_ERROR))
    }

    @Test
    fun storageStartsEmpty() {
        assertTrue(AttrStorage().isEmpty)
        val s = AttrStorage()
        s.add(AttrFlag.SYNTHETIC)
        assertFalse(s.isEmpty)
    }
}
