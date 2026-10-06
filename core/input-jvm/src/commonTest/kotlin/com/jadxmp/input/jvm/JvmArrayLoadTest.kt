package com.jadxmp.input.jvm

import com.jadxmp.input.CodeReader
import com.jadxmp.input.Instruction
import com.jadxmp.input.Opcode
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmArrayLoadTest {
    @Test fun exactPrimitiveComponentsChooseTypedLoadsAndComputationalResults() {
        val cases = listOf(
            Triple("I", 0x2e, Opcode.AGET), Triple("J", 0x2f, Opcode.AGET_WIDE),
            Triple("F", 0x30, Opcode.AGET), Triple("D", 0x31, Opcode.AGET_WIDE),
            Triple("Z", 0x33, Opcode.AGET_BOOLEAN), Triple("B", 0x33, Opcode.AGET_BYTE),
            Triple("C", 0x34, Opcode.AGET_CHAR), Triple("S", 0x35, Opcode.AGET_SHORT),
        )
        for ((element, opcode, expected) in cases) {
            val result = when (element) { "J" -> "J"; "F" -> "F"; "D" -> "D"; else -> "I" }
            val ret = when (result) { "J" -> 0xad; "F" -> 0xae; "D" -> 0xaf; else -> 0xac }
            val reader = normalize("([$element)$result", bytes(0x2a, 0x03, opcode, ret))
            val load = read(reader).single { it.opcode == expected }
            assertEquals(listOf(1, 1, 2), (0 until load.registerCount).map(load::register), element)
            assertEquals(110, load.fileOffset)
            assertTrue(load.register(0) + (if (result == "J" || result == "D") 2 else 1) <= reader.registerCount)
        }
    }

    @Test fun booleanLoadMaterializesItsComputationalIntBeforeConsumers() {
        val instructions = read(normalize("([Z)I", bytes(0x2a, 0x03, 0x33, 0x04, 0x60, 0xac)))
        val index = instructions.indexOfFirst { it.opcode == Opcode.AGET_BOOLEAN }
        val conversion = instructions[index + 1]
        assertEquals(Opcode.BOOLEAN_TO_INT, conversion.opcode)
        assertEquals(listOf(1, 1), (0 until conversion.registerCount).map(conversion::register))
        assertEquals(instructions[index].fileOffset, conversion.fileOffset)
    }

    @Test fun referenceLoadsKeepExactComponentAndSupportNestedArrayLoads() {
        for (element in listOf("Ljava/lang/String;", "[I", "[[Ljava/lang/Object;")) {
            val instructions = read(normalize("([$element)$element", bytes(0x2a, 0x03, 0x32, 0xb0)))
            assertEquals(1, instructions.count { it.opcode == Opcode.AGET_OBJECT })
        }
        val nested = read(normalize("([[I)I", bytes(0x2a, 0x03, 0x32, 0x03, 0x2e, 0xac)))
        assertEquals(listOf(Opcode.AGET_OBJECT, Opcode.AGET), nested.filter { it.opcode in arrayLoads }.map { it.opcode })
    }

    @Test fun knownNullAndUnusedReadsRetainThrowingInstruction() {
        for (opcode in 0x2e..0x35) {
            val pop = if (opcode == 0x2f || opcode == 0x31) 0x58 else 0x57
            val instructions = read(normalize("()V", bytes(0x01, 0x03, opcode, pop, 0xb1), locals = 0))
            assertEquals(1, instructions.count { it.opcode == Opcode.THROW })
        }
        read(normalize("()Ljava/lang/String;", bytes(0x01, 0x03, 0x32, 0xb0), locals = 0))
    }

    @Test fun nullAndArrayMergeRetainsTypedArray() {
        // flag ? array : null; iconst_0; baload; ireturn
        val instructions = read(normalize("(Z[B)I", bytes(0x1a, 0x99, 0, 7, 0x2b, 0xa7, 0, 4, 0x01, 0x03, 0x33, 0xac), locals = 2))
        assertEquals(1, instructions.count { it.opcode == Opcode.AGET_BYTE })
    }

    @Test fun mismatchedComponentsAndUnresolvedReferencesAreRejected() {
        for ((type, opcode) in listOf("[J" to 0x2e, "[I" to 0x2f, "[I" to 0x30, "[J" to 0x31,
            "[I" to 0x32, "[[I" to 0x33, "[Z" to 0x34, "[B" to 0x35,
            "Ljava/lang/Object;" to 0x2e, "Ljava/lang/String;" to 0x32)) {
            val failure = assertFailsWith<ByteReaderException> { normalize("($type)V", bytes(0x2a, 0x03, opcode, 0xb1)) }
            assertTrue(failure.message.orEmpty().contains("array load"), failure.message)
        }
        // Different array component frames currently join Object; do not guess a component.
        assertFailsWith<ByteReaderException> { normalize("(Z[I[F)I", bytes(0x1a, 0x99, 0, 7, 0x2b, 0xa7, 0, 4, 0x2c, 0x03, 0x2e, 0xac), locals = 3) }
    }

    @Test fun wrongIndexArrayUnderflowAndWideOverflowAreRejected() {
        for (code in listOf(bytes(0x2a, 0x0b, 0x2e, 0xac), bytes(0x03, 0x03, 0x2e, 0xac),
            bytes(0x2a, 0x2e, 0xac), bytes(0x2e, 0xac))) {
            assertFailsWith<ByteReaderException> { normalize("([I)I", code) }
        }
        assertFailsWith<ByteReaderException> { normalize("([J)J", bytes(0x03, 0x2a, 0x03, 0x2f, 0xad), stack = 2) }
    }

    @Test fun guaranteedThrowStillValidatesOriginalNormalContinuation() {
        assertFailsWith<ByteReaderException> {
            normalize("()I", bytes(0x01, 0x03, 0x32, 0xac), locals = 0)
        }
    }

    @Test fun componentResolutionIsCachedAndBoundedWithConstantResolution() {
        val descriptor = "[L" + "x".repeat(2000) + ";"
        var work = 0L
        var resolutions = 0
        val resolver = JvmArrayOperands({ work += it }) { resolutions++; JvmFrameValue.Reference(it) }
        val type = JvmFrameValue.Reference(descriptor)
        repeat(1000) { resolver.load(type, 0x32) }
        assertEquals(descriptor.length.toLong() * 1000, work)
        assertEquals(1, resolutions)
        val failure = assertFailsWith<ByteReaderException> {
            normalize("($descriptor)Ljava/lang/Object;", bytes(0x2a, 0x03, 0x32, 0xb0),
                limits = JvmAnalysisLimits(maxConstantWork = 100))
        }
        assertTrue(failure.message.orEmpty().contains("work limit"), failure.message)
    }

    @Test fun repeatedLongDescriptorLookupsConsumeTheMethodBudget() {
        val descriptor = "[L" + "x".repeat(2000) + ";"
        val code = buildList { repeat(100) { addAll(listOf(0x2a, 0x03, 0x32, 0x57)) }; add(0xb1) }
        val failure = assertFailsWith<ByteReaderException> {
            normalize("($descriptor)V", code.map(Int::toByte).toByteArray(),
                limits = JvmAnalysisLimits(maxConstantWork = 20_000))
        }
        assertTrue(failure.message.orEmpty().contains("work limit"), failure.message)
    }

    private val arrayLoads = setOf(Opcode.AGET, Opcode.AGET_WIDE, Opcode.AGET_OBJECT, Opcode.AGET_BOOLEAN,
        Opcode.AGET_BYTE, Opcode.AGET_CHAR, Opcode.AGET_SHORT)
    private fun read(reader: CodeReader): List<Instruction> = buildList { reader.visitInstructions { it.decode(); add(it) } }
    private fun normalize(descriptor: String, code: ByteArray, locals: Int = 1, stack: Int = 2,
        limits: JvmAnalysisLimits = JvmAnalysisLimits()): CodeReader {
        val pool = JvmConstantPool.read(ByteReader(u2(1)), 65)
        val attribute = JvmAttribute("Code", 100, u2(stack) + u2(locals) + i4(code.size) + code + u2(0) + u2(0))
        val member = JvmMember(8, "test", descriptor, listOf(attribute))
        return JvmRegisterNormalizer.normalize("Example", member, JvmMethodDescriptor.parse(descriptor), pool,
            JvmCodeAttribute.parse(attribute, pool), limits)
    }
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
    private fun u2(value: Int) = bytes(value ushr 8, value)
    private fun i4(value: Int) = bytes(value ushr 24, value ushr 16, value ushr 8, value)
}
