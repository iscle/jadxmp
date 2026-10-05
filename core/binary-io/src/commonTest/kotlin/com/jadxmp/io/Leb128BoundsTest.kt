package com.jadxmp.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Leb128BoundsTest {
    @Test
    fun unsigned32BitExtremaAndPaddedValuesRemainValid() {
        assertEquals(0xffff_ffffL, bytes(0xff, 0xff, 0xff, 0xff, 0x0f).readUleb128())
        assertEquals(0L, bytes(0x80, 0x80, 0x80, 0x80, 0).readUleb128())
        assertEquals(-1, bytes(0).readUleb128p1())
        assertEquals(Int.MAX_VALUE, bytes(0x80, 0x80, 0x80, 0x80, 0x08).readUleb128p1())
    }

    @Test
    fun signed32BitExtremaAndPaddedValuesRemainValid() {
        assertEquals(Int.MAX_VALUE, bytes(0xff, 0xff, 0xff, 0xff, 0x07).readSleb128())
        assertEquals(Int.MIN_VALUE, bytes(0x80, 0x80, 0x80, 0x80, 0x78).readSleb128())
        assertEquals(-1, bytes(0xff, 0xff, 0xff, 0xff, 0x7f).readSleb128())
        assertEquals(0, bytes(0x80, 0x80, 0x80, 0x80, 0).readSleb128())
    }

    @Test
    fun unsignedOverflowCannotWrapIntoAValidSizeOrIndex() {
        for (last in 0x10..0x7f) {
            assertFailsWith<ByteReaderException>("last byte $last") {
                bytes(0x80, 0x80, 0x80, 0x80, last).readUleb128()
            }
        }
    }

    @Test
    fun signedOverflowCannotTruncateIntoAnother32BitValue() {
        for (last in 0x08..0x77) {
            assertFailsWith<ByteReaderException>("last byte $last") {
                bytes(0x80, 0x80, 0x80, 0x80, last).readSleb128()
            }
        }
    }

    @Test
    fun continuationOnFifthByteFailsWithoutConsumingTheNextField() {
        for (signed in listOf(false, true)) {
            val reader = bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x42)
            assertFailsWith<ByteReaderException> {
                if (signed) reader.readSleb128() else reader.readUleb128()
            }
            assertEquals(5, reader.position)
            assertEquals(0x42, reader.readU8())
        }
    }

    @Test
    fun truncatedContinuationIsReportedAsAReaderError() {
        for (length in 1..4) {
            assertFailsWith<ByteReaderException> { ByteReader(ByteArray(length) { 0x80.toByte() }).readUleb128() }
            assertFailsWith<ByteReaderException> { ByteReader(ByteArray(length) { 0x80.toByte() }).readSleb128() }
        }
    }

    private fun bytes(vararg values: Int) = ByteReader(ByteArray(values.size) { values[it].toByte() })
}
