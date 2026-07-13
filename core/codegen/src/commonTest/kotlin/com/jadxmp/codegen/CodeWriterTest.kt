package com.jadxmp.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeWriterTest {

    @Test
    fun indentationAndNewlinesAreDeterministic() {
        val w = CodeWriter()
        w.add("class X {").newLine()
        w.indented {
            add("int a;").newLine()
        }
        w.add("}").newLine()
        assertEquals("class X {\n    int a;\n}\n", w.finish().code)
    }

    @Test
    fun blankLinesCarryNoTrailingWhitespace() {
        val w = CodeWriter()
        w.incIndent()
        w.add("a;").newLine()
        w.newLine() // blank line while indented
        w.add("b;").newLine()
        // The middle line must be exactly empty, not four spaces.
        assertEquals("    a;\n\n    b;\n", w.finish().code)
    }

    @Test
    fun attachRecordsAnnotationAtTokenOffset() {
        val w = CodeWriter()
        w.add("int ")
        val ref = VarRef(0, "value")
        w.attachVariable(ref, declaration = true)
        val offset = w.length
        w.add("value").add(";")
        val info = w.finish()

        // The offset points exactly at the start of "value".
        assertEquals(offset, info.code.indexOf("value"))
        val ann = info.metadata.at(offset)
        assertTrue(ann is VariableAnnotation && ann.ref == ref && ann.declaration)
    }

    @Test
    fun lengthTracksEmittedCharacters() {
        val w = CodeWriter()
        assertEquals(0, w.length)
        w.add("abc")
        assertEquals(3, w.length)
        w.newLine()
        assertEquals(4, w.length)
    }

    @Test
    fun lineMappingRecordsBytecodeOffsets() {
        val w = CodeWriter()
        w.add("a;").mapLineToBytecode(4).newLine()
        w.add("b;").mapLineToBytecode(8).newLine()
        val map = w.finish().metadata.lineMapping
        assertEquals(4, map[1])
        assertEquals(8, map[2])
    }

    @Test
    fun emptyWriterProducesEmptyMetadata() {
        val info = CodeWriter().finish()
        assertEquals("", info.code)
        assertEquals(0, info.metadata.size)
        assertNull(info.metadata.at(0))
    }
}
