package com.jadxmp.input.jvm

import com.jadxmp.input.Instruction
import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException
import kotlin.test.*

class JvmFieldAccessTest {
    @Test fun staticAndInstanceReadsRetainExactFieldIdentityAndWideResult() {
        for ((type, ret) in listOf("I" to 0xac, "J" to 0xad, "F" to 0xae, "D" to 0xaf, "Ljava/lang/Object;" to 0xb0, "[I" to 0xb0)) {
            for (static in listOf(false, true)) {
                val code = normalize(type, if (static) 8 else 0,
                    (if (static) byteArrayOf() else bytes(0x2a)) + bytes(if (static) 0xb2 else 0xb4, 0, 6, ret),
                    if (static) "()$type" else "(LExample;)$type", if (static) 0 else 1, 2)
                val get = code.single { it.opcode == if (static) Opcode.SGET else Opcode.IGET }
                assertEquals(IndexType.FIELD_REF, get.indexType)
                assertEquals(6, get.index)
                assertEquals("LExample;", get.indexAsField().declaringClassType)
                assertEquals("value", get.indexAsField().name)
                assertEquals(type, get.indexAsField().type)
                assertFailsWith<ByteReaderException> { get.indexAsType() }
            }
        }
    }

    @Test fun smallStoresNarrowConsumedSnapshotAndBooleanReadsBecomeComputationalInt() {
        for ((type, conversion) in listOf("B" to Opcode.INT_TO_BYTE, "C" to Opcode.INT_TO_CHAR, "S" to Opcode.INT_TO_SHORT, "Z" to Opcode.INT_TO_BOOLEAN)) {
            val code = normalize(type, 8, bytes(0x1a, 0x59, 0xb3, 0, 6, 0xac), "(I)I", 1, 2)
            assertEquals(1, code.count { it.opcode == conversion })
            assertNotEquals(code.single { it.opcode == conversion }.register(0), code.last().register(0))
            if (type == "Z") assertEquals(1L, code.single { it.opcode == Opcode.AND_INT_LIT }.literal)
        }
        val read = normalize("Z", 8, bytes(0xb2, 0, 6, 0x04, 0x60, 0xac), "()I", 0, 2)
        assertEquals(Opcode.BOOLEAN_TO_INT, read[1].opcode)
    }

    @Test fun referenceStoresRequireProvenAssignmentWithoutInventingACast() {
        for ((type, descriptor, load) in listOf(Triple("Ljava/lang/Object;", "(Ljava/lang/String;)V", 0x2a),
            Triple("[I", "([I)V", 0x2a), Triple("Ljava/lang/String;", "()V", 0x01))) {
            val code = normalize(type, 8, bytes(load, 0xb3, 0, 6, 0xb1), descriptor, if (load == 0x01) 0 else 1, 1)
            assertEquals(1, code.count { it.opcode == Opcode.SPUT })
            assertFalse(code.any { it.opcode == Opcode.CHECK_CAST })
        }
        assertFailsWith<ByteReaderException> { normalize("Ljava/lang/String;", 8, bytes(0x2a, 0xb3, 0, 6, 0xb1), "(Ljava/lang/Object;)V", 1, 1) }
    }

    @Test fun nullInstanceAccessRetainsPriorValueEffectsAndContinuationValidation() {
        val get = normalize("I", 0, bytes(0x01, 0xb4, 0, 6, 0xac), "()I", 0, 1)
        assertEquals(1, get.count { it.opcode == Opcode.THROW })
        val put = normalize("I", 0, bytes(0x01, 0x04, 0x03, 0x6c, 0xb5, 0, 6, 0xb1), "()V", 0, 3)
        assertTrue(put.indexOfFirst { it.opcode == Opcode.DIV_INT } < put.indexOfFirst { it.opcode == Opcode.THROW })
        assertFailsWith<ByteReaderException> { normalize("J", 0, bytes(0x01, 0xb4, 0, 6, 0xac), "()I", 0, 2) }
    }

    @Test fun mismatchedStaticKindsFinalFieldsForeignOwnersAndReceiverShapesDiagnose() {
        for ((flags, raw) in listOf(0 to 0xb2, 8 to 0xb4, 24 to 0xb2)) assertFailsWith<ByteReaderException> {
            normalize("I", flags, bytes(0x2a, raw, 0, 6, 0xac), "(LExample;)I", 1, 2)
        }
        assertFailsWith<ByteReaderException> { normalize("I", 0, bytes(0x2a, 0xb4, 0, 6, 0xac), "(Ljava/lang/Object;)I", 1, 1) }
        assertFailsWith<ByteReaderException> { normalize("I", 8, bytes(0xb2, 0, 6, 0xac), "()I", 0, 1, fieldOwner = "Foreign") }
        assertFailsWith<ByteReaderException> { normalize("I", 8, bytes(0xb2, 0, 6, 0xac), "()I", 0, 1, tag = 10) }
    }

    @Test fun declarationsRejectSourceNameCollisionsAndBudgetRepeatedLongFieldUses() {
        assertFailsWith<ByteReaderException> { JvmDeclaredFields("Example", listOf(
            JvmMember(8, "same", "I", emptyList()), JvmMember(8, "same", "J", emptyList()))) }
        assertFailsWith<ByteReaderException> { JvmDeclaredFields("Example", listOf(
            JvmMember(8, "value", "I", emptyList())), maxWork = 10) }
        val name = "x".repeat(2048)
        val once = bytes(0xb2, 0, 6, 0xac)
        normalize("I", 8, once, "()I", 0, 1, fieldName = name, limits = JvmAnalysisLimits(maxConstantWork = 10000))
        val repeated = (0 until 100).fold(byteArrayOf()) { code, _ -> code + bytes(0xb2, 0, 6, 0x57) } + bytes(0xb1)
        val gets = normalize("I", 8, repeated, "()V", 0, 1, fieldName = name).filter { it.opcode == Opcode.SGET }
        assertEquals(100, gets.size)
        for (get in gets) assertSame(gets.first().indexAsField(), get.indexAsField())
        assertFailsWith<ByteReaderException> { normalize("I", 8, repeated, "()V", 0, 1,
            fieldName = name, limits = JvmAnalysisLimits(maxConstantWork = 10000)) }
    }

    private fun normalize(type: String, flags: Int, code: ByteArray, descriptor: String, locals: Int, stack: Int,
        fieldOwner: String = "Example", tag: Int = 9, fieldName: String = "value",
        limits: JvmAnalysisLimits = JvmAnalysisLimits()): List<Instruction> {
        val entries = listOf(utf8(fieldOwner), bytes(7, 0, 1), utf8(fieldName), utf8(type), bytes(12, 0, 3, 0, 4), bytes(tag, 0, 2, 0, 5))
        val pool = JvmConstantPool.read(ByteReader(u2(7) + entries.fold(byteArrayOf()) { a, b -> a + b }), 65)
        val attr = JvmAttribute("Code", 100, u2(stack) + u2(locals) + i4(code.size) + code + u2(0) + u2(0))
        val member = JvmMember(8, "test", descriptor, listOf(attr))
        val reader = JvmRegisterNormalizer.normalize("Example", member, JvmMethodDescriptor.parse(descriptor), pool, JvmCodeAttribute.parse(attr, pool), limits,
            fields = JvmDeclaredFields("Example", listOf(JvmMember(flags, fieldName, type, emptyList()))))
        return buildList { reader.visitInstructions { it.decode(); add(it) } }
    }
    private fun utf8(value: String) = bytes(1) + u2(value.length) + value.encodeToByteArray()
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
    private fun u2(value: Int) = bytes(value ushr 8, value)
    private fun i4(value: Int) = bytes(value ushr 24, value ushr 16, value ushr 8, value)
}
