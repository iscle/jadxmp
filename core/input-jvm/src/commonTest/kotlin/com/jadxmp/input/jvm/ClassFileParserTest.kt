package com.jadxmp.input.jvm

import com.jadxmp.io.ByteReaderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClassFileParserTest {
    @Test fun readsClassMembersAndPreservesOpaqueAttributes() {
        val file = ClassFileParser.parse(sample())
        assertEquals(61, file.majorVersion)
        assertEquals("sample/Example", file.name)
        assertEquals("java/lang/Object", file.superName)
        assertEquals(listOf("java/io/Serializable"), file.interfaces)
        assertEquals("answer", file.fields.single().name)
        assertEquals("I", file.fields.single().descriptor)
        assertEquals("run", file.methods.single().name)
        assertEquals("()V", file.methods.single().descriptor)
        assertEquals("FutureAttribute", file.attributes.single().name)
        assertTrue(file.attributes.single().bytes.contentEquals(byteArrayOf(7, 8, 9)))
    }

    @Test fun rejectsEveryTruncatedPrefixAndTrailingBytes() {
        val bytes = sample()
        for (length in bytes.indices) {
            assertFailsWith<ByteReaderException>("prefix $length") {
                ClassFileParser.parse(bytes.copyOf(length))
            }
        }
        assertFailsWith<ByteReaderException> { ClassFileParser.parse(bytes + 0) }
    }

    @Test fun rejectsBadHeaderReferencesAndUnboundedAttributeLengths() {
        for (offset in listOf(0, 4, 6, 8)) {
            val bytes = sample()
            bytes[offset] = 0xFF.toByte()
            assertFailsWith<ByteReaderException>("offset $offset") { ClassFileParser.parse(bytes) }
        }
        val bytes = sample()
        // Final opaque attribute has a four-byte length immediately before its three-byte body.
        for (index in bytes.size - 7 until bytes.size - 3) bytes[index] = 0xFF.toByte()
        assertFailsWith<ByteReaderException> { ClassFileParser.parse(bytes) }
    }

    @Test fun preservesHistoricalMinorVersionsAndModernPreviewMarker() {
        for ((major, minor) in listOf(45 to 65535, 50 to 7, 55 to 65535, 56 to 0, 65 to 65535)) {
            val bytes = sample()
            bytes[4] = (minor ushr 8).toByte(); bytes[5] = minor.toByte()
            bytes[6] = (major ushr 8).toByte(); bytes[7] = major.toByte()
            assertEquals(minor, ClassFileParser.parse(bytes).minorVersion)
        }
        val bytes = sample()
        bytes[5] = 1
        assertFailsWith<ByteReaderException> { ClassFileParser.parse(bytes) }
    }

    @Test fun instanceParameterLimitIncludesTheReceiver() {
        fun input(slots: Int, flags: Int) = ClassBytes().apply {
            u4(0xCAFEBABEL); u2(0); u2(61); u2(7)
            utf("Example"); u1(7); u2(1); utf("java/lang/Object"); u1(7); u2(3)
            utf("call"); utf("(" + "I".repeat(slots) + ")V")
            u2(0x21); u2(2); u2(4); u2(0); u2(0)
            u2(1); u2(flags); u2(5); u2(6); u2(0); u2(0)
        }.bytes()
        ClassFileParser.parse(input(255, 0x109)) // static native, no receiver
        ClassFileParser.parse(input(254, 0x101)) // instance native, receiver is slot 255
        assertFailsWith<ByteReaderException> { ClassFileParser.parse(input(255, 0x101)) }
    }

    @Test fun repeatedEnvelopeReferencesReuseBoundedSyntaxValidation() {
        // Duplicates are intentionally only syntax-checked at this stage, not class-verified.
        val longName = "a".repeat(65520)
        val bytes = ClassBytes().apply {
            u4(0xCAFEBABEL); u2(0); u2(61); u2(7)
            utf(longName); u1(7); u2(1); utf("java/lang/Object"); u1(7); u2(3)
            utf("b".repeat(65520)); utf("(L$longName;)V")
            u2(0x21); u2(2); u2(4)
            u2(20000); repeat(20000) { u2(2) }
            u2(0)
            u2(10000); repeat(10000) { u2(0x109); u2(5); u2(6); u2(0) }
            u2(0)
        }.bytes()
        val file = ClassFileParser.parse(bytes)
        assertEquals(20000, file.interfaces.size)
        assertEquals(10000, file.methods.size)
    }

    private fun sample(): ByteArray = ClassBytes().apply {
        u4(0xCAFEBABEL); u2(0); u2(61); u2(12)
        utf("sample/Example"); u1(7); u2(1)
        utf("java/lang/Object"); u1(7); u2(3)
        utf("java/io/Serializable"); u1(7); u2(5)
        utf("answer"); utf("I"); utf("run"); utf("()V"); utf("FutureAttribute")
        u2(0x21); u2(2); u2(4); u2(1); u2(6)
        u2(1); u2(1); u2(7); u2(8); u2(0)
        u2(1); u2(0x101); u2(9); u2(10); u2(0)
        u2(1); u2(11); u4(3); u1(7); u1(8); u1(9)
    }.bytes()
}

internal class ClassBytes {
    private val output = mutableListOf<Byte>()
    fun u1(value: Int) { output += value.toByte() }
    fun u2(value: Int) { u1(value ushr 8); u1(value) }
    fun u4(value: Long) { u2((value ushr 16).toInt()); u2(value.toInt()) }
    fun u8(value: Long) { u4(value ushr 32); u4(value) }
    fun utf(value: String) {
        require(value.all { it.code in 1..127 })
        u1(1); u2(value.length); value.forEach { u1(it.code) }
    }
    fun bytes(): ByteArray = output.toByteArray()
}
