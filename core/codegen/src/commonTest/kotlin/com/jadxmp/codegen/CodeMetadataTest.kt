package com.jadxmp.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodeMetadataTest {

    private val classDef = DefinitionAnnotation(ClassNodeRef("a.b.Foo"))
    private val methodDef = DefinitionAnnotation(MethodNodeRef("a.b.Foo", "m", emptyList()))
    private val fieldRef = ReferenceAnnotation(FieldNodeRef("a.b.Foo", "x"))

    // Simulated layout of a class with one method:
    //   0  class Foo {        <- classDef
    //   10   int m(...) {     <- methodDef
    //   18       ... x ...    <- fieldRef (a use)
    //   26   }                <- NodeEnd (method)
    //   30 }                  <- NodeEnd (class)
    private fun sample(): CodeMetadata = CodeMetadata.build(
        mapOf(
            0 to classDef,
            10 to methodDef,
            18 to fieldRef,
            26 to NodeEndAnnotation,
            30 to NodeEndAnnotation,
        ),
        emptyMap(),
    )

    @Test
    fun atReturnsExactAnnotation() {
        val m = sample()
        assertEquals(methodDef, m.at(10))
        assertEquals(fieldRef, m.at(18))
        assertNull(m.at(11))
    }

    @Test
    fun closestUpFindsNearestEarlierAnnotation() {
        val m = sample()
        assertEquals(methodDef, m.closestUp(18))
        assertEquals(classDef, m.closestUp(10))
        assertNull(m.closestUp(0))
    }

    @Test
    fun nodeAtFindsEnclosingMethodThenClass() {
        val m = sample()
        // Inside the method body.
        assertEquals(MethodNodeRef("a.b.Foo", "m", emptyList()), m.nodeAt(18))
        // Between the method-end (26) and class-end (30): only the class still encloses.
        assertEquals(ClassNodeRef("a.b.Foo"), m.nodeAt(28))
        // At the class declaration itself.
        assertEquals(ClassNodeRef("a.b.Foo"), m.nodeAt(5))
    }

    @Test
    fun nodeBelowFindsFirstDeclarationForward() {
        val m = sample()
        assertEquals(ClassNodeRef("a.b.Foo"), m.nodeBelow(0))
        assertEquals(MethodNodeRef("a.b.Foo", "m", emptyList()), m.nodeBelow(5))
    }

    @Test
    fun searchUpAndDownFilterByCategory() {
        val m = sample()
        val defAbove = m.searchUp(20) { it is DefinitionAnnotation }
        assertEquals(methodDef, defAbove)
        val refBelow = m.searchDown(11) { it is ReferenceAnnotation }
        assertEquals(fieldRef, refBelow)
    }

    @Test
    fun emptyIsStable() {
        assertEquals(0, CodeMetadata.EMPTY.size)
        assertNull(CodeMetadata.EMPTY.at(0))
        assertNull(CodeMetadata.EMPTY.nodeAt(0))
    }
}
