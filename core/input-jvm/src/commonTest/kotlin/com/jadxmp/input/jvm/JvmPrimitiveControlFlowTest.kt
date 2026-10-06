package com.jadxmp.input.jvm

import com.jadxmp.input.CodeReader
import com.jadxmp.input.InlineSwitchPayload
import com.jadxmp.input.Opcode
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmPrimitiveControlFlowTest {
    @Test fun branchesConsumeOperandsAndResolveBothReturns() {
        val instructions = read(fixture("(II)I", 2, 2,
            bytes(0x1a, 0x1b, 0xa2, 0, 5, 0x1a, 0xac, 0x1b, 0xac)))
        val branch = instructions.single { it.opcode == Opcode.IF_GE }
        assertEquals(listOf(2, 3), branch.registers)
        assertEquals(Opcode.MOVE, instructions[branch.target].opcode)
        assertEquals(115, instructions[branch.target].fileOffset)
        assertEquals(2, instructions.count { it.opcode == Opcode.RETURN })
    }

    @Test fun joinsPrimitiveStackValuesWithoutInventingCopies() {
        val instructions = read(fixture("(I)I", 1, 1,
            bytes(0x1a, 0x99, 0, 7, 0x04, 0xa7, 0, 4, 0x05, 0xac)))
        assertEquals(Opcode.CONST, instructions[instructions.single { it.opcode == Opcode.IF_EQZ }.target].opcode)
        assertEquals(Opcode.RETURN, instructions[instructions.single { it.opcode == Opcode.GOTO }.target].opcode)
        assertEquals(listOf(1), instructions.last().registers)
    }

    @Test fun backwardTargetAtRawZeroSkipsOneTimeParameterPrologue() {
        val instructions = read(fixture("(I)I", 1, 1,
            bytes(0x84, 0, 0xff, 0x1a, 0x9d, 0xff, 0xfc, 0x1a, 0xac)))
        val branch = instructions.single { it.opcode == Opcode.IF_GTZ }
        assertEquals(1, branch.target)
        assertEquals(Opcode.MOVE, instructions[0].opcode)
        assertEquals(Opcode.ADD_INT_LIT, instructions[branch.target].opcode)
    }

    @Test fun targetAtNopMapsToNextEmittedInstruction() {
        val instructions = read(fixture("(I)I", 1, 1,
            bytes(0x1a, 0x99, 0, 5, 0x04, 0xac, 0x00, 0x05, 0xac)))
        val branch = instructions.single { it.opcode == Opcode.IF_EQZ }
        assertEquals(Opcode.CONST, instructions[branch.target].opcode)
        assertEquals(115, instructions[branch.target].fileOffset)
    }

    @Test fun keepsExplicitSwitchDefaultSeparateFromFollowingCase() {
        val code = bytes(0x1a, 0xab, 0, 0) + i4(21) + i4(1) + i4(7) + i4(19) +
            bytes(0x04, 0xac, 0x05, 0xac)
        val instructions = read(fixture("(I)I", 1, 1, code))
        val branch = instructions.single { it.opcode == Opcode.SWITCH }
        val payload = branch.payload as InlineSwitchPayload
        assertEquals(listOf(7), payload.keys.toList())
        assertEquals(128, instructions[payload.targets.single()].fileOffset)
        assertEquals(130, instructions[payload.defaultTarget].fileOffset)
        assertTrue(payload.defaultTarget != branch.offset + 1)
    }

    @Test fun rejectsIncompatibleStackJoinsAndMaybeUnassignedLocals() {
        assertFailsWith<ByteReaderException> {
            fixture("(I)I", 1, 1, bytes(0x1a, 0x99, 0, 7, 0x04, 0xa7, 0, 4, 0x0c, 0xac))
        }
        assertFailsWith<ByteReaderException> {
            fixture("(I)I", 2, 1, bytes(0x1a, 0x99, 0, 5, 0x04, 0x3c, 0x1b, 0xac))
        }
    }

    @Test fun permitsInfinitePrimitiveLoopsButRejectsUnsupportedDeadRegions() {
        assertEquals(Opcode.GOTO, read(fixture("()V", 0, 0, bytes(0xa7, 0, 0))).single().opcode)
        assertFailsWith<ByteReaderException> { fixture("()V", 0, 0, bytes(0xb1, 0x00)) }
    }

    @Test fun boundsDenseFrameStorageAndWorkBeforeExhaustingHostResources() {
        val chain = (List(70) { bytes(0xa7, 0, 3) }.fold(byteArrayOf()) { a, b -> a + b }) + bytes(0xb1)
        val storage = assertFailsWith<ByteReaderException> { fixture("()V", 65535, 0, chain) }
        assertTrue(storage.message.orEmpty().contains("frame storage limit"))
        val work = assertFailsWith<ByteReaderException> {
            fixture("()V", 0, 0, bytes(0xa7, 0, 0), JvmAnalysisLimits(maxWork = 0))
        }
        assertTrue(work.message.orEmpty().contains("work limit"))
    }

    @Test fun resolvesWideBranchesAndTargetsAtRemovedPop() {
        val wide = read(fixture("()I", 0, 1, bytes(0x04, 0xc8, 0, 0, 0, 5, 0xac)))
        assertEquals(Opcode.RETURN, wide[wide.single { it.opcode == Opcode.GOTO }.target].opcode)
        val pop = read(fixture("()I", 0, 2, bytes(0x04, 0x05, 0xa7, 0, 3, 0x57, 0xac)))
        assertEquals(Opcode.RETURN, pop[pop.single { it.opcode == Opcode.GOTO }.target].opcode)
        assertEquals(listOf(0), pop.last().registers)
    }

    @Test fun revisitsLoopHeadsWhenAReachableBackEdgeInvalidatesLocals() {
        // Initial local0 is int; one iteration overwrites it with float before jumping to the load.
        val error = assertFailsWith<ByteReaderException> {
            fixture("(I)I", 1, 1, bytes(0x1a, 0x99, 0, 8, 0x0b, 0x43, 0xa7, 0xff, 0xfa, 0x03, 0xac))
        }
        assertTrue(error.message.orEmpty().contains("local 0 is not a usable value"))
    }

    private data class Seen(val offset: Int, val fileOffset: Int, val opcode: Opcode, val registers: List<Int>,
        val target: Int, val payload: com.jadxmp.input.InstructionPayload?)
    private fun read(reader: CodeReader): List<Seen> = buildList {
        reader.visitInstructions { instruction ->
            instruction.decode()
            add(Seen(instruction.offset, instruction.fileOffset, instruction.opcode,
                (0 until instruction.registerCount).map(instruction::register), instruction.target, instruction.payload))
        }
    }
    private fun fixture(descriptor: String, locals: Int, stack: Int, code: ByteArray,
        limits: JvmAnalysisLimits = JvmAnalysisLimits(),
    ): CodeReader {
        val pool = JvmConstantPool.read(ByteReader(u2(1)), 65)
        val attribute = JvmAttribute("Code", 100, u2(stack) + u2(locals) + i4(code.size) + code + u2(0) + u2(0))
        val member = JvmMember(8, "test", descriptor, listOf(attribute))
        return JvmRegisterNormalizer.normalize("Example", member, JvmMethodDescriptor.parse(descriptor), pool,
            JvmCodeAttribute.parse(attribute, pool), limits)
    }
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    private fun u2(value: Int) = bytes(value ushr 8, value)
    private fun i4(value: Int) = bytes(value ushr 24, value ushr 16, value ushr 8, value)
}
