package com.jadxmp.input.jvm

import com.jadxmp.input.CodeReader
import com.jadxmp.input.Instruction
import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmArrayStoreTest {
    @Test fun exactPrimitiveComponentsSelectStoreAndPreserveWideSourceWords() {
        for ((element, raw, store) in listOf(
            Triple("I", 0x4f, Opcode.APUT), Triple("J", 0x50, Opcode.APUT_WIDE),
            Triple("F", 0x51, Opcode.APUT), Triple("D", 0x52, Opcode.APUT_WIDE),
            Triple("B", 0x54, Opcode.APUT_BYTE), Triple("Z", 0x54, Opcode.APUT_BOOLEAN),
            Triple("C", 0x55, Opcode.APUT_CHAR), Triple("S", 0x56, Opcode.APUT_SHORT),
        )) {
            val valueType = if (element in listOf("B", "Z", "C", "S")) "I" else element
            val load = when (element) { "J" -> 0x1f; "D" -> 0x27; "F" -> 0x23; else -> 0x1b }
            val wide = element == "J" || element == "D"
            val locals = if (wide) 3 else 2
            val code = read(normalize("([$element$valueType)V", bytes(0x2a, 0x03, load, raw, 0xb1), locals, if (wide) 4 else 3))
            val operation = code.single { it.opcode == store }
            assertEquals(listOf(locals + 2, locals, locals + 1), (0 until operation.registerCount).map(operation::register))
            assertEquals(111, operation.fileOffset)
        }
    }

    @Test fun narrowIntegerStoresMaterializeJvmTruncation() {
        for ((element, raw, conversion) in listOf(Triple("B", 0x54, Opcode.INT_TO_BYTE),
            Triple("C", 0x55, Opcode.INT_TO_CHAR), Triple("S", 0x56, Opcode.INT_TO_SHORT))) {
            val code = read(normalize("([$element" + "I)V", bytes(0x2a, 0x03, 0x1b, raw, 0xb1), 2, 3))
            val cast = code.single { it.opcode == conversion }
            assertEquals(listOf(4, 4), (0 until cast.registerCount).map(cast::register))
            assertEquals(111, cast.fileOffset)
        }
    }

    @Test fun booleanStoresMaskLowBitBeforeExplicitBooleanConversion() {
        val code = read(normalize("([ZI)V", bytes(0x2a, 0x03, 0x1b, 0x54, 0xb1), 2, 3))
        val index = code.indexOfFirst { it.opcode == Opcode.AND_INT_LIT }
        assertEquals(1L, code[index].literal)
        assertEquals(Opcode.INT_TO_BOOLEAN, code[index + 1].opcode)
        assertEquals(Opcode.APUT_BOOLEAN, code[index + 2].opcode)
        assertEquals(listOf(4, 4), (0 until code[index + 1].registerCount).map(code[index + 1]::register))
    }

    @Test fun referenceStoresValidateReferenceArrayShapeWithoutInventingAssignability() {
        for (array in listOf("[Ljava/lang/Object;", "[Ljava/lang/String;", "[[I", "[[Ljava/lang/String;")) {
            val code = read(normalize("($array" + "Ljava/lang/Object;)V", bytes(0x2a, 0x03, 0x2b, 0x53, 0xb1), 2, 3))
            assertEquals(1, code.count { it.opcode == Opcode.APUT_OBJECT })
            val view = code.single { it.opcode == Opcode.REFERENCE_ARRAY_TO_OBJECT_ARRAY }
            assertEquals(IndexType.NONE, view.indexType)
            assertEquals(-1, view.index)
            assertEquals(listOf(2, 2), (0 until view.registerCount).map(view::register))
            assertEquals(111, view.fileOffset)
        }
    }

    @Test fun knownNullStoresRemainThrowingAfterValueEvaluation() {
        for ((raw, value, wide) in listOf(Triple(0x4f, 0x03, false), Triple(0x50, 0x09, true),
            Triple(0x51, 0x0b, false), Triple(0x52, 0x0e, true), Triple(0x53, 0x01, false),
            Triple(0x54, 0x03, false), Triple(0x55, 0x03, false), Triple(0x56, 0x03, false))) {
            val code = read(normalize("()V", bytes(0x01, 0x03, value, raw, 0xb1), 0, if (wide) 4 else 3))
            assertEquals(1, code.count { it.opcode == Opcode.THROW })
            assertEquals(111, code.single { it.opcode == Opcode.THROW }.fileOffset)
        }
        // Original verifier continuation is still checked after a guaranteed throw.
        assertFailsWith<ByteReaderException> { normalize("()I", bytes(0x01, 0x03, 0x03, 0x4f, 0xac), 0, 3) }
    }

    @Test fun mismatchedComponentsValuesAndUnresolvedArrayFramesFail() {
        for ((array, value, raw) in listOf(Triple("[J", 0x03, 0x4f), Triple("[I", 0x09, 0x50),
            Triple("[D", 0x0b, 0x51), Triple("[F", 0x0e, 0x52), Triple("[I", 0x01, 0x53),
            Triple("[C", 0x03, 0x54), Triple("[B", 0x03, 0x55), Triple("[Z", 0x03, 0x56),
            Triple("Ljava/lang/Object;", 0x03, 0x4f), Triple("[I", 0x0b, 0x4f), Triple("[Ljava/lang/Object;", 0x03, 0x53))) {
            assertFailsWith<ByteReaderException> { normalize("($array)V", bytes(0x2a, 0x03, value, raw, 0xb1), 1, 4) }
        }
        assertFailsWith<ByteReaderException> { normalize("([I)V", bytes(0x2a, 0x0b, 0x03, 0x4f, 0xb1), 1, 3) }
    }

    @Test fun duplicatedAssignmentValueKeepsIndependentStackSnapshot() {
        // JVM permits preserving the original Int while bastore truncates its separate copy.
        val code = read(normalize("([BI)I", bytes(0x2a, 0x03, 0x1b, 0x5b, 0x54, 0xac), 2, 4))
        val cast = code.single { it.opcode == Opcode.INT_TO_BYTE }
        val returned = code.last().register(0)
        assertTrue(cast.register(0) != returned)
    }

    private fun read(reader: CodeReader): List<Instruction> = buildList { reader.visitInstructions { it.decode(); add(it) } }
    private fun normalize(descriptor: String, code: ByteArray, locals: Int, stack: Int): CodeReader {
        val pool = JvmConstantPool.read(ByteReader(u2(1)), 65)
        val attribute = JvmAttribute("Code", 100, u2(stack) + u2(locals) + i4(code.size) + code + u2(0) + u2(0))
        val member = JvmMember(8, "test", descriptor, listOf(attribute))
        return JvmRegisterNormalizer.normalize("Example", member, JvmMethodDescriptor.parse(descriptor), pool,
            JvmCodeAttribute.parse(attribute, pool))
    }
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
    private fun u2(value: Int) = bytes(value ushr 8, value)
    private fun i4(value: Int) = bytes(value ushr 24, value ushr 16, value ushr 8, value)
}
