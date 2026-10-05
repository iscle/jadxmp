package com.jadxmp.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmByteReaderTest {
    @Test
    fun bigEndianPrimitivesPreserveBitsAndUnsignedRanges() {
        val input = bytes(0xfe, 0xdc, 0x80, 0, 0, 1, 0xff, 0xff, 0xff, 0xff,
            0x80, 0, 0, 0, 0, 0, 0, 1)
        assertEquals(0xfedc, input.readU16BE())
        assertEquals(Int.MIN_VALUE + 1, input.readS32BE())
        assertEquals(0xffff_ffffL, input.readU32BE())
        assertEquals(Long.MIN_VALUE + 1, input.readS64BE())
        assertEquals(0, input.remaining)
    }

    @Test
    fun truncatedBigEndianReadsFailBeforeAdvancing() {
        for (width in listOf(2, 4, 8)) {
            val input = ByteReader(ByteArray(width - 1))
            assertFailsWith<ByteReaderException> {
                when (width) {
                    2 -> input.readU16BE()
                    4 -> input.readS32BE()
                    else -> input.readS64BE()
                }
            }
            assertEquals(0, input.position)
        }
    }

    @Test
    fun jvmModifiedUtf8UsesByteLengthAndPreservesUtf16CodeUnits() {
        val input = bytes(0x41, 0xc0, 0x80, 0xc2, 0xa2, 0xed, 0xa0, 0xbd, 0xed, 0xba, 0x80, 0x42)
        assertEquals("A\u0000¢\uD83D\uDE80", input.readMutf8Bytes(11))
        assertEquals(0x42, input.readU8(), "next constant-pool field must remain unread")
        assertEquals("", bytes().readMutf8Bytes(0))
        assertEquals("\uD800", bytes(0xed, 0xa0, 0x80).readMutf8Bytes(3), "MUTF-8 preserves lone UTF-16 surrogates")
    }

    @Test
    fun jvmModifiedUtf8RejectsMalformedAndNonModifiedEncodings() {
        for (bad in listOf(
            intArrayOf(0), intArrayOf(0x80), intArrayOf(0xc1, 0x81),
            intArrayOf(0xe0, 0x80, 0xaf), intArrayOf(0xc2, 0x41),
            intArrayOf(0xe1, 0x80, 0x41), intArrayOf(0xf0, 0x9f, 0x9a, 0x80),
        )) {
            assertFailsWith<ByteReaderException>(bad.joinToString()) { bytes(*bad).readMutf8Bytes(bad.size) }
        }
    }

    @Test
    fun truncatedCharacterCannotConsumeBytesOutsideItsDeclaredLength() {
        val two = bytes(0xc2, 0xa2)
        assertFailsWith<ByteReaderException> { two.readMutf8Bytes(1) }
        assertEquals(1, two.position)
        assertEquals(0xa2, two.readU8())
        val three = bytes(0xe1, 0x80, 0x80)
        assertFailsWith<ByteReaderException> { three.readMutf8Bytes(2) }
        assertEquals(1, three.position)
    }

    @Test
    fun untrustedByteLengthsAreBoundedBeforeAllocation() {
        for (size in listOf(-1, 2, Int.MAX_VALUE)) {
            val input = bytes(0x41)
            assertFailsWith<ByteReaderException> { input.readMutf8Bytes(size) }
            assertEquals(0, input.position)
        }
    }

    private fun bytes(vararg values: Int) = ByteReader(ByteArray(values.size) { values[it].toByte() })
}
