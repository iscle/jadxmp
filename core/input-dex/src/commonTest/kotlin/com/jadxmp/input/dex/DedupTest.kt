package com.jadxmp.input.dex

import com.jadxmp.io.ByteArraySource
import kotlin.test.Test
import kotlin.test.assertEquals

class DedupTest {

    private fun oneClassDex(): ByteArray {
        val b = DexBuilder()
        val tFoo = b.addType("LFoo;")
        val tObj = b.addType("Ljava/lang/Object;")
        b.addClass(tFoo, tObj)
        return b.build()
    }

    @Test
    fun singleDexLoadsOneClass() {
        val result = DexInput.load("classes.dex", oneClassDex())
        assertEquals(1, result.classes.size)
        assertEquals("LFoo;", result.classes[0].type)
        assertEquals("Ljava/lang/Object;", result.classes[0].superType)
        assertEquals(0, result.duplicateClassCount)
    }

    @Test
    fun duplicateClassAcrossDexesResolvesFirstWins() {
        val dex = oneClassDex()
        val result = DexInput.load(
            listOf(
                ByteArraySource("classes.dex", dex),
                ByteArraySource("classes2.dex", dex),
            ),
        )
        assertEquals(1, result.classes.size, "duplicate LFoo; should collapse to one")
        assertEquals(1, result.duplicateClassCount)
        assertEquals("LFoo;", result.classes[0].type)
    }
}
