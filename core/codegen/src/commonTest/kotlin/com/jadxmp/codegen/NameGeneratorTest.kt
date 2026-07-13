package com.jadxmp.codegen

import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NameGeneratorTest {

    @Test
    fun uniqueDisambiguatesRepeats() {
        val ng = NameGenerator()
        assertEquals("i", ng.unique("i"))
        assertEquals("i2", ng.unique("i"))
        assertEquals("i3", ng.unique("i"))
    }

    @Test
    fun typeBasedNames() {
        val ng = NameGenerator()
        assertEquals("i", ng.forType(IrType.INT))
        assertEquals("str", ng.forType(IrType.STRING))
        assertEquals("j", ng.forType(IrType.LONG))
    }

    @Test
    fun objectNameFromSimpleClassName() {
        assertEquals("list", NameGenerator().forType(IrType.objectType("java.util.List")))
    }

    @Test
    fun arrayNameGetsArrSuffix() {
        assertEquals("iArr", NameGenerator().forType(IrType.array(IrType.INT)))
    }

    @Test
    fun keywordsAreAvoided() {
        val ng = NameGenerator()
        // A class literally named "int"/keyword-like should not produce a bare keyword identifier.
        val name = ng.unique("class")
        assertNotEquals("class", name)
    }

    @Test
    fun reservedNamesForceDisambiguation() {
        val ng = NameGenerator()
        ng.reserve("i")
        assertEquals("i2", ng.forType(IrType.INT))
    }
}
