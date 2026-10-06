package com.jadxmp.input.jvm

import com.jadxmp.input.CodeReader
import com.jadxmp.input.Instruction
import com.jadxmp.input.Opcode
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException
import kotlin.test.*

class JvmReferenceNormalizerTest {
    @Test fun referenceLoadsSnapshotValuesBeforeLocalOverwrite() {
        val instructions = read(fixture("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 2, 2,
            bytes(0x2a, 0x2b, 0x4b, 0xb0)))
        assertEquals(listOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT, Opcode.RETURN),
            instructions.drop(2).map { it.opcode })
        assertEquals(listOf(2, 0), registers(instructions[2]))
        assertEquals(listOf(0, 3), registers(instructions[4]))
        assertEquals(listOf(2), registers(instructions[5]))
    }

    @Test fun nullAndWideReferenceLocalsRemainObjectMoves() {
        val instructions = read(fixture("()Ljava/lang/String;", 257, 1,
            bytes(0x01, 0xc4, 0x3a, 1, 0, 0xc4, 0x19, 1, 0, 0xb0)))
        assertEquals(listOf(Opcode.CONST, Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT, Opcode.RETURN), instructions.map { it.opcode })
        assertEquals(0L, instructions.first().literal)
        assertEquals(listOf(256, 257), registers(instructions[1]))
        assertEquals(listOf(257, 256), registers(instructions[2]))
    }

    @Test fun referenceReturnAndBranchRejectPrimitivesOrUnknownNarrowing() {
        for ((descriptor, code) in listOf(
            "(I)Ljava/lang/Object;" to bytes(0x1a, 0xb0),
            "(I)Ljava/lang/Object;" to bytes(0x2a, 0xb0),
            "()Ljava/lang/Object;" to bytes(0x03, 0x4b, 0x2a, 0xb0),
            "(I)I" to bytes(0x1a, 0xc6, 0, 5, 0x03, 0xac, 0x04, 0xac),
            "(Ljava/lang/Object;)Ljava/lang/String;" to bytes(0x2a, 0xb0),
        )) assertFailsWith<ByteReaderException> { fixture(descriptor, 1, 2, code) }
        // Assignment to Object requires no hierarchy lookup, including arrays.
        fixture("([I)Ljava/lang/Object;", 1, 1, bytes(0x2a, 0xb0))
    }

    @Test fun nullableReferenceJoinsConvergeWithoutErasingTheirKnownType() {
        val reference = JvmFrameValue.Reference("[Ljava/lang/String;")
        for (reverse in listOf(false, true)) {
            val left = JvmFrame(1, 1)
            val right = JvmFrame(1, 1)
            left.store(0, if (reverse) reference else JvmFrameValue.NullValue)
            left.push(left.local(0))
            right.store(0, if (reverse) JvmFrameValue.NullValue else reference)
            right.push(right.local(0))
            assertEquals(!reverse, left.mergeFrom(right))
            assertEquals(reference, left.local(0))
            assertEquals(listOf(reference), left.stack)
            assertFalse(left.mergeFrom(right))
        }
    }

    @Test fun distinctReferencesJoinConservativelyAndBadStackJoinsRemainAtomic() {
        val a = JvmFrameValue.Reference("Ljava/lang/String;")
        val b = JvmFrameValue.Reference("[I")
        val left = JvmFrame(1, 2).apply { store(0, a); push(a); push(JvmFrameValue.IntValue) }
        val right = JvmFrame(1, 2).apply { store(0, b); push(b); push(JvmFrameValue.FloatValue) }
        assertFailsWith<ByteReaderException> { left.mergeFrom(right) }
        assertEquals(a, left.local(0))
        assertEquals(listOf(a, JvmFrameValue.IntValue), left.stack)
        right.pop(); right.push(JvmFrameValue.IntValue)
        assertTrue(left.mergeFrom(right))
        val joined = JvmFrameValue.Reference("Ljava/lang/Object;")
        assertEquals(joined, left.local(0))
        assertEquals(listOf(joined, JvmFrameValue.IntValue), left.stack)
        assertFalse(left.mergeFrom(right))
    }

    @Test fun referenceStoresInvalidateOverlappingWideLocals() {
        fixture("()Ljava/lang/Object;", 2, 2, bytes(0x09, 0x3f, 0x01, 0x4c, 0x2b, 0xb0))
        assertFailsWith<ByteReaderException> { fixture("()J", 2, 2, bytes(0x09, 0x3f, 0x01, 0x4c, 0x1e, 0xad)) }
    }

    @Test fun repeatedLongReferenceJoinsConsumeTheAnalysisWorkBudget() {
        val count = 64
        val branchTarget = 4 + count + 3
        val code = bytes(0x1a, 0x99) + u2(branchTarget - 1) + ByteArray(count) { 0x2b } +
            bytes(0xa7) + u2(3 + count) + ByteArray(count) { 0x2c } + ByteArray(count) { 0x57 } + bytes(0xb1)
        fixture("(ZLA;LB;)V", 3, count, code, JvmAnalysisLimits(maxWork = 3000))
        val prefix = "x".repeat(2048)
        val failure = assertFailsWith<ByteReaderException> {
            fixture("(ZL${prefix}A;L${prefix}B;)V", 3, count, code, JvmAnalysisLimits(maxWork = 3000))
        }
        assertTrue(failure.message.orEmpty().contains("work limit"))
    }

    private fun registers(insn: Instruction) = (0 until insn.registerCount).map(insn::register)
    private fun read(reader: CodeReader): List<Instruction> = buildList { reader.visitInstructions { it.decode(); add(it) } }
    private fun fixture(descriptor: String, locals: Int, stack: Int, code: ByteArray, limits: JvmAnalysisLimits = JvmAnalysisLimits()): CodeReader {
        val pool = JvmConstantPool.read(ByteReader(bytes(0, 1)), 65)
        val attr = JvmAttribute("Code", 100, u2(stack) + u2(locals) + i4(code.size) + code + u2(0) + u2(0))
        val method = JvmMember(8, "test", descriptor, listOf(attr))
        return JvmRegisterNormalizer.normalize("Example", method, JvmMethodDescriptor.parse(descriptor), pool,
            JvmCodeAttribute.parse(attr, pool), limits)
    }
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    private fun u2(value: Int) = bytes(value ushr 8, value)
    private fun i4(value: Int) = bytes(value ushr 24, value ushr 16, value ushr 8, value)
}
