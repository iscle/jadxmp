package com.jadxmp.input.jvm

import com.jadxmp.input.EncodedValue
import com.jadxmp.input.EncodedValueType
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmFieldConstantsTest {
    @Test fun typedConstantsPreserveNarrowingWideValuesAndStrings() {
        val cases = listOf(
            Triple("Z", 1, EncodedValue(EncodedValueType.BOOLEAN, true)),
            Triple("Z", 2, EncodedValue(EncodedValueType.BOOLEAN, false)),
            Triple("Z", -1, EncodedValue(EncodedValueType.BOOLEAN, true)),
            Triple("B", 255, EncodedValue(EncodedValueType.BYTE, (-1).toByte())),
            Triple("S", 65535, EncodedValue(EncodedValueType.SHORT, (-1).toShort())),
            Triple("C", -1, EncodedValue(EncodedValueType.CHAR, '\uffff')),
            Triple("I", Int.MIN_VALUE, EncodedValue(EncodedValueType.INT, Int.MIN_VALUE)),
        )
        for ((descriptor, bits, expected) in cases) {
            assertEquals(expected, read(descriptor, constantPool { u1(3); u4(bits.toLong()) }))
        }
        assertEquals(EncodedValue(EncodedValueType.LONG, Long.MIN_VALUE),
            read("J", constantPool(wide = true) { u1(5); u8(Long.MIN_VALUE) }))
        assertEquals(EncodedValue(EncodedValueType.STRING, "text"),
            read("Ljava/lang/String;", constantPool(extra = 1) { u1(8); u2(2); utf("text") }))
    }

    @Test fun floatingConstantsPreserveSignedZeroInfinityAndNan() {
        val negativeFloatZero = read("F", constantPool { u1(4); u4(0x80000000L) })!!.value as Float
        assertEquals(Int.MIN_VALUE, negativeFloatZero.toRawBits())
        val negativeDoubleZero = read("D", constantPool(wide = true) { u1(6); u8(Long.MIN_VALUE) })!!.value as Double
        assertEquals(Long.MIN_VALUE, negativeDoubleZero.toRawBits())
        assertEquals(Float.POSITIVE_INFINITY, read("F", constantPool { u1(4); u4(0x7f800000L) })!!.value)
        assertTrue((read("F", constantPool { u1(4); u4(0x7fc01234L) })!!.value as Float).isNaN())
        assertTrue((read("D", constantPool(wide = true) { u1(6); u8(0x7ff8000000001234L) })!!.value as Double).isNaN())
    }

    @Test fun onlyStaticFieldsReceiveInitializersAndFinalIsNotRequired() {
        val pool = constantPool { u1(3); u4(42) }
        assertEquals(EncodedValue(EncodedValueType.INT, 42), read("I", pool, flags = 8))
        assertEquals(EncodedValue(EncodedValueType.INT, 42), read("I", pool, flags = 24))
        assertNull(read("I", pool, flags = 1))
        assertNull(read("I", pool, flags = 17))
        assertNull(JvmFieldConstants.read(JvmMember(8, "value", "I", emptyList()), pool))
    }

    @Test fun malformedConstantAttributesProduceDiagnostics() {
        val pool = constantPool { u1(3); u4(42) }
        for (attributes in listOf(
            listOf(JvmAttribute("ConstantValue", 0, byteArrayOf(0))),
            listOf(JvmAttribute("ConstantValue", 0, byteArrayOf(0, 1, 0))),
            listOf(attribute(0)), listOf(attribute(2)),
            listOf(attribute(1), attribute(1)),
        )) {
            assertFailsWith<ByteReaderException> {
                JvmFieldConstants.read(JvmMember(8, "value", "I", attributes), pool)
            }
        }
        for (descriptor in listOf("J", "F", "D", "Ljava/lang/String;", "Ljava/lang/Object;", "[I")) {
            assertFailsWith<ByteReaderException>(descriptor) { read(descriptor, pool) }
        }
        assertFailsWith<ByteReaderException> { read("I", constantPool { utf("wrong tag") }) }
    }

    private fun read(descriptor: String, pool: JvmConstantPool, flags: Int = 8) =
        JvmFieldConstants.read(JvmMember(flags, "value", descriptor, listOf(attribute(1))), pool)

    private fun attribute(index: Int) =
        JvmAttribute("ConstantValue", 0, ClassBytes().apply { u2(index) }.bytes())

    private fun constantPool(wide: Boolean = false, extra: Int = 0, body: ClassBytes.() -> Unit): JvmConstantPool =
        JvmConstantPool.read(ByteReader(ClassBytes().apply { u2(2 + extra + if (wide) 1 else 0); body() }.bytes()), 61)
}
