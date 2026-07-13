package com.jadxmp.input.dex

import com.jadxmp.input.EncodedValue
import com.jadxmp.input.EncodedValueType
import com.jadxmp.input.dex.DexTestSupport.bytes
import com.jadxmp.input.dex.DexTestSupport.minimalHeader
import com.jadxmp.io.ByteReader
import kotlin.test.Test
import kotlin.test.assertEquals

class EncodedValueTest {

    private val parser = EncodedValueParser(Dex(minimalHeader(), "t", 0))

    private fun parse(vararg b: Int): EncodedValue = parser.parseValue(ByteReader(bytes(*b)))

    @Test
    fun parsesNull() {
        assertEquals(EncodedValue.NULL, parse(0x1e))
    }

    @Test
    fun parsesBoolean() {
        assertEquals(EncodedValue(EncodedValueType.BOOLEAN, true), parse(0x3f)) // arg=1 -> true
        assertEquals(EncodedValue(EncodedValueType.BOOLEAN, false), parse(0x1f)) // arg=0 -> false
    }

    @Test
    fun parsesSignExtendedInt() {
        assertEquals(EncodedValue(EncodedValueType.INT, 5), parse(0x04, 0x05)) // size 1
        assertEquals(EncodedValue(EncodedValueType.INT, -1), parse(0x04, 0xFF)) // sign extended
    }

    @Test
    fun parsesLongAndByte() {
        assertEquals(EncodedValue(EncodedValueType.BYTE, 7.toByte()), parse(0x00, 0x07))
        // 0x0102 little-endian across 2 bytes, VALUE_LONG (0x06) with arg = size-1 = 1
        assertEquals(EncodedValue(EncodedValueType.LONG, 0x0102L), parse(0x26, 0x02, 0x01))
    }

    @Test
    fun parsesFloatAndDouble() {
        // 1.0f = 0x3F800000; DEX stores the high bytes only, arg = size-1
        assertEquals(EncodedValue(EncodedValueType.FLOAT, 1.0f), parse(0x30, 0x80, 0x3F))
        // 1.0 = 0x3FF0000000000000
        assertEquals(EncodedValue(EncodedValueType.DOUBLE, 1.0), parse(0x31, 0xF0, 0x3F))
    }

    @Test
    fun parsesEncodedArrayOfInts() {
        // VALUE_ARRAY (0x1c): uleb count=2, then INT 1, INT 2
        val value = parse(0x1c, 0x02, 0x04, 0x01, 0x04, 0x02)
        assertEquals(EncodedValueType.ARRAY, value.type)
        @Suppress("UNCHECKED_CAST")
        val list = value.value as List<EncodedValue>
        assertEquals(listOf(EncodedValue(EncodedValueType.INT, 1), EncodedValue(EncodedValueType.INT, 2)), list)
    }
}
