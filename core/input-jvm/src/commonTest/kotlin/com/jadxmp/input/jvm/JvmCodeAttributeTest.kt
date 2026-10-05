package com.jadxmp.input.jvm

import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmCodeAttributeTest {
    private val constants = JvmConstantPool.read(ByteReader(ClassBytes().apply {
        u2(4); utf("java/lang/Exception"); u1(7); u2(1); utf("LineNumberTable")
    }.bytes()), 61)

    @Test fun preservesOrderedHandlersAndAbsoluteNestedAttributeOffsets() {
        val bytes = ClassBytes().apply {
            u2(1); u2(2); u4(2); u1(0); u1(177)
            u2(2)
            u2(0); u2(1); u2(1); u2(2)
            u2(0); u2(2); u2(1); u2(0)
            u2(1); u2(3); u4(6); u2(1); u2(0); u2(42)
        }.bytes()
        val code = JvmCodeAttribute.parse(JvmAttribute("Code", 100, bytes), constants)
        assertEquals(1, code.maxStack)
        assertEquals(2, code.maxLocals)
        assertEquals(108, code.codeOffset)
        assertTrue(code.bytes.contentEquals(byteArrayOf(0, 177.toByte())))
        assertEquals(listOf("java/lang/Exception", null), code.handlers.map { it.catchType })
        assertEquals(listOf(1, 2), code.handlers.map { it.endOffset })
        assertEquals("LineNumberTable", code.attributes.single().name)
        assertEquals(136, code.attributes.single().offset)
    }

    @Test fun rejectsTruncationOversizedCodeAndTrailingPayload() {
        val body = ClassBytes().apply { u2(0); u2(0); u4(1); u1(177); u2(0); u2(0) }.bytes()
        for (size in body.indices) {
            assertFailsWith<ByteReaderException>("length $size") { parse(body.copyOf(size)) }
        }
        assertFailsWith<ByteReaderException> { parse(body + 0) }
        for (length in listOf(0L, 65536L, 0xFFFFFFFFL)) {
            assertFailsWith<ByteReaderException> { parse(ClassBytes().apply { u2(0); u2(0); u4(length) }.bytes()) }
        }
    }

    @Test fun rejectsInvalidHandlerRangesAndCatchTypes() {
        for ((start, end, handler, type) in listOf(
            listOf(1, 1, 0, 0), listOf(1, 0, 0, 0), listOf(0, 3, 0, 0),
            listOf(0, 2, 2, 0), listOf(0, 2, 1, 1), listOf(0, 2, 1, 4),
        )) {
            val bytes = ClassBytes().apply {
                u2(1); u2(0); u4(2); u1(0); u1(177); u2(1)
                u2(start); u2(end); u2(handler); u2(type); u2(0)
            }.bytes()
            assertFailsWith<ByteReaderException> { parse(bytes) }
        }
    }

    @Test fun validatesHandlerInstructionBoundariesIncludingCodeEnd() {
        fun input(start: Int, end: Int, handler: Int) = ClassBytes().apply {
            u2(1); u2(0); u4(3); u1(0x10); u1(0); u1(177); u2(1)
            u2(start); u2(end); u2(handler); u2(0); u2(0)
        }.bytes()
        for ((start, end, handler) in listOf(listOf(1, 3, 2), listOf(0, 1, 2), listOf(0, 3, 1))) {
            assertFailsWith<ByteReaderException> { parse(input(start, end, handler)).decodeInstructions() }
        }
        assertEquals(listOf(0, 2), parse(input(0, 3, 2)).decodeInstructions().map { it.offset })
        assertEquals(listOf(0, 2), parse(input(0, 2, 2)).decodeInstructions().map { it.offset })
    }

    private fun parse(bytes: ByteArray) = JvmCodeAttribute.parse(JvmAttribute("Code", 0, bytes), constants)
}
