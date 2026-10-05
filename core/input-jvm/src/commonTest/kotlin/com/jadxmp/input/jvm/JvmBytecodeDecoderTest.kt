package com.jadxmp.input.jvm

import com.jadxmp.io.ByteReaderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmBytecodeDecoderTest {
    @Test fun accountsForEveryDefinedOpcodeAndItsOperandWidth() {
        val covered = mutableSetOf<Int>()
        fun check(opcode: Int, bytes: IntArray, operand: JvmOperand) {
            val instruction = decode(opcode, *bytes).single()
            assertEquals(opcode, instruction.opcode)
            assertEquals(bytes.size + 1, instruction.size)
            assertEquals(operand, instruction.operand)
            covered += opcode
        }
        for (opcode in (0x00..0x0f) + (0x1a..0x35) + (0x3b..0x83) + (0x85..0x98) +
            (0xac..0xb1) + listOf(0xbe, 0xbf, 0xc2, 0xc3)) {
            check(opcode, intArrayOf(), JvmOperand.None)
        }
        for (opcode in (0x15..0x19) + (0x36..0x3a) + 0xa9) {
            check(opcode, intArrayOf(127), JvmOperand.Local(127))
        }
        for (opcode in listOf(0x13, 0x14, 0xbb, 0xbd, 0xc0, 0xc1) + (0xb2..0xb8)) {
            check(opcode, intArrayOf(0x80, 1), JvmOperand.Constant(32769))
        }
        for (opcode in (0x99..0xa8) + listOf(0xc6, 0xc7)) {
            check(opcode, intArrayOf(0, 0), JvmOperand.Branch(0))
        }
        for (opcode in 0xc8..0xc9) check(opcode, intArrayOf(0, 0, 0, 0), JvmOperand.Branch(0))
        // Variable and specialized operand formats are covered by the focused tests below.
        covered += listOf(0x10, 0x11, 0x12, 0x84, 0xaa, 0xab, 0xb9, 0xba, 0xbc, 0xc4, 0xc5)
        assertEquals((0x00..0xc9).toSet(), covered)
    }

    @Test fun preservesSignedConstantsAndUnsignedIndexes() {
        val instructions = decode(0x10, 0x80, 0x11, 0x80, 0, 0x12, 255, 0x13, 0xff, 0xff,
            0x15, 255, 0x84, 255, 0x80, 0xb1)
        assertEquals(listOf(0, 2, 5, 7, 10, 12, 15), instructions.map { it.offset })
        assertEquals(JvmOperand.Immediate(-128), instructions[0].operand)
        assertEquals(JvmOperand.Immediate(-32768), instructions[1].operand)
        assertEquals(JvmOperand.Constant(255), instructions[2].operand)
        assertEquals(JvmOperand.Constant(65535), instructions[3].operand)
        assertEquals(JvmOperand.Local(255), instructions[4].operand)
        assertEquals(JvmOperand.Increment(255, -128), instructions[5].operand)
    }

    @Test fun decodesEveryWideLocalFormWithoutCreatingEmbeddedInstructionBoundary() {
        for (opcode in (0x15..0x19) + (0x36..0x3a) + 0xa9) {
            val instruction = decode(0xc4, opcode, 0xff, 0xff, 0xb1).first()
            assertEquals(opcode, instruction.opcode)
            assertEquals(4, instruction.size)
            assertTrue(instruction.wide)
            assertEquals(JvmOperand.Local(65535), instruction.operand)
        }
        assertEquals(JvmOperand.Increment(32768, -32768),
            decode(0xc4, 0x84, 0x80, 0, 0x80, 0, 0xb1).first().operand)
        rejects(0xa7, 0, 4, 0xc4, 0x15, 0, 1, 0xb1)
        rejects(0xc4, 0x00, 0, 0)
    }

    @Test fun resolvesSignedBranchesAndRejectsNonInstructionTargets() {
        assertEquals(JvmOperand.Branch(0), decode(0, 0xa7, 0xff, 0xff)[1].operand)
        assertEquals(JvmOperand.Branch(0), decode(0, 0xc8, 0xff, 0xff, 0xff, 0xff)[1].operand)
        assertEquals(JvmOperand.Branch(3), decode(0xa8, 0, 3, 0xb1)[0].operand)
        for (bytes in listOf(intArrayOf(0xa7, 0, 1), intArrayOf(0xa7, 0, 3),
            intArrayOf(0xa7, 0xff, 0xff), intArrayOf(0xc8, 0x7f, 0xff, 0xff, 0xff))) {
            rejects(*bytes)
        }
    }

    @Test fun decodesSwitchesAtAllAlignmentsAndAllowsUnspecifiedPaddingBytes() {
        for (prefix in 0..3) {
            val table = switchBytes(prefix, 0xaa, listOf(0, -1, 1, 0, 0, 0))
            val instruction = JvmBytecodeDecoder.decode(table).last()
            assertEquals(JvmOperand.Switch(prefix, listOf(
                JvmSwitchCase(-1, prefix), JvmSwitchCase(0, prefix), JvmSwitchCase(1, prefix))), instruction.operand)
            val lookup = switchBytes(prefix, 0xab, listOf(0, 2, Int.MIN_VALUE, 0, Int.MAX_VALUE, 0))
            assertEquals(JvmOperand.Switch(prefix, listOf(
                JvmSwitchCase(Int.MIN_VALUE, prefix), JvmSwitchCase(Int.MAX_VALUE, prefix))),
                JvmBytecodeDecoder.decode(lookup).last().operand)
        }
        assertEquals(JvmOperand.Switch(0, emptyList()),
            JvmBytecodeDecoder.decode(switchBytes(0, 0xab, listOf(0, 0))).single().operand)
    }

    @Test fun rejectsMalformedOrUnboundedSwitchesBeforeAllocation() {
        for ((opcode, words) in listOf(
            0xaa to listOf(0, 1, 0),
            0xaa to listOf(0, Int.MIN_VALUE, Int.MAX_VALUE),
            0xaa to listOf(0, 0, 1, 0),
            0xab to listOf(0, -1),
            0xab to listOf(0, Int.MAX_VALUE),
            0xab to listOf(0, 2, 1, 0, 1, 0),
            0xab to listOf(0, 2, 2, 0, 1, 0),
            0xab to listOf(1, 0),
            0xaa to listOf(0, 0, 0, 4),
        )) {
            assertFailsWith<ByteReaderException> { JvmBytecodeDecoder.decode(switchBytes(0, opcode, words)) }
        }
    }

    @Test fun validatesInvocationReservedBytesCountsAndArrayEncodings() {
        assertEquals(JvmOperand.InterfaceCall(65535, 255), decode(0xb9, 255, 255, 255, 0)[0].operand)
        assertEquals(JvmOperand.Constant(1), decode(0xba, 0, 1, 0, 0)[0].operand)
        assertEquals(JvmOperand.MultiArray(1, 255), decode(0xc5, 0, 1, 255)[0].operand)
        for (atype in 4..11) assertEquals(JvmOperand.ArrayType(atype), decode(0xbc, atype)[0].operand)
        for (bytes in listOf(intArrayOf(0xb9, 0, 1, 0, 0), intArrayOf(0xb9, 0, 1, 1, 1),
            intArrayOf(0xba, 0, 1, 1, 0), intArrayOf(0xba, 0, 1, 0, 1),
            intArrayOf(0xc5, 0, 1, 0), intArrayOf(0xbc, 3), intArrayOf(0xbc, 12),
            intArrayOf(0x12, 0), intArrayOf(0xb6, 0, 0))) rejects(*bytes)
    }

    @Test fun rejectsEveryReservedOpcodeAndTruncatedOperandFamily() {
        for (opcode in 0xca..0xff) rejects(opcode)
        for (bytes in listOf(intArrayOf(0x10), intArrayOf(0x11, 1), intArrayOf(0x12),
            intArrayOf(0x13, 1), intArrayOf(0x15), intArrayOf(0x84, 1), intArrayOf(0xa7, 0),
            intArrayOf(0xc8, 0, 0, 0), intArrayOf(0xb9, 0, 1, 1), intArrayOf(0xba, 0, 1, 0),
            intArrayOf(0xbc), intArrayOf(0xc5, 0, 1), intArrayOf(0xc4),
            intArrayOf(0xc4, 0x15, 0), intArrayOf(0xc4, 0x84, 0, 1, 0), intArrayOf(0xaa))) rejects(*bytes)
    }

    @Test fun boundsCodeLength() {
        assertFailsWith<ByteReaderException> { JvmBytecodeDecoder.decode(byteArrayOf()) }
        assertFailsWith<ByteReaderException> { JvmBytecodeDecoder.decode(ByteArray(65536)) }
        assertEquals(65535, JvmBytecodeDecoder.decode(ByteArray(65535)).size)
    }

    private fun decode(vararg values: Int) = JvmBytecodeDecoder.decode(values.map { it.toByte() }.toByteArray())
    private fun rejects(vararg values: Int) {
        assertFailsWith<ByteReaderException>(values.joinToString()) { decode(*values) }
    }

    private fun switchBytes(prefix: Int, opcode: Int, words: List<Int>): ByteArray {
        val bytes = MutableList(prefix) { 0.toByte() }
        bytes += opcode.toByte()
        while (bytes.size % 4 != 0) bytes += 0x7f.toByte()
        for (word in words) for (shift in listOf(24, 16, 8, 0)) bytes += (word ushr shift).toByte()
        return bytes.toByteArray()
    }
}
