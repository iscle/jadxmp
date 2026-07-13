package com.jadxmp.pipeline.cfg

import com.jadxmp.input.Opcode
import com.jadxmp.input.SwitchPayload
import com.jadxmp.ir.insn.SwitchInstruction
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.support.FakeCatchHandler
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeTryBlock
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CfgTest {

    /** A diamond: A(if) -> {B, C} -> D. */
    private fun diamond(): FakeCodeReader = FakeCodeReader(
        1,
        listOf(
            Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
            Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4),
            Insn(Opcode.CONST, 2, intArrayOf(0), literal = 10),
            Insn(Opcode.GOTO, 3, target = 5),
            Insn(Opcode.CONST, 4, intArrayOf(0), literal = 20),
            Insn(Opcode.RETURN_VOID, 5),
        ),
    )

    @Test
    fun diamondBlocksAndEdges() {
        val method = TestPipeline.buildMethod(diamond())
        TestPipeline.cfg(method)

        val a = TestPipeline.blockAt(method, 0)
        val b = TestPipeline.blockAt(method, 2)
        val c = TestPipeline.blockAt(method, 4)
        val d = TestPipeline.blockAt(method, 5)
        val entry = method.entryBlock!!
        val exit = method.exitBlock!!

        assertEquals(setOf(a), entry.successors.toSet())
        assertEquals(setOf(b, c), a.successors.toSet())
        assertEquals(setOf(d), b.successors.toSet())
        assertEquals(setOf(d), c.successors.toSet())
        assertEquals(setOf(exit), d.successors.toSet())
        // predecessors are the inverse
        assertEquals(setOf(a), b.predecessors.toSet())
        assertEquals(setOf(b, c), d.predecessors.toSet())
    }

    @Test
    fun ifBlockKeepsBothInstructions() {
        val method = TestPipeline.buildMethod(diamond())
        TestPipeline.cfg(method)
        val a = TestPipeline.blockAt(method, 0)
        assertEquals(2, a.instructions.size) // const + if
    }

    @Test
    fun exceptionEdgesConnectProtectedBlocksToHandler() {
        // Protected range [0,1]; handler at offset 3 catching java.lang.Exception.
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 1),
                Insn(Opcode.RETURN_VOID, 1),
                Insn(Opcode.NOP, 2), // filler so 3 is a distinct instruction
                Insn(Opcode.MOVE_EXCEPTION, 3, intArrayOf(1)),
                Insn(Opcode.RETURN_VOID, 4),
            ),
            tries = listOf(
                FakeTryBlock(0, 1, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(3), -1)),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.cfg(method)

        val handler = TestPipeline.blockAt(method, 3)
        val excHandler = handler[PipelineAttrs.EXC_HANDLER]
        assertNotNull(excHandler)
        assertEquals(1, excHandler.types.size)
        // the protected block flows to the handler
        val protectedBlock = TestPipeline.blockAt(method, 0)
        assertTrue(handler in protectedBlock.successors)
        // move-exception operand typed from the catch type
        val moveExc = handler.instructions.first()
        assertEquals("java.lang.Exception", (moveExc.result!!.type.toString()))
    }

    @Test
    fun packedSwitchResolvesCasesAndDefault() {
        // targets in the payload are relative to the switch instruction (offset 1): key0 -> +3 (=4),
        // key1 -> +5 (=6); default falls through to the next instruction (offset 2).
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
                Insn(Opcode.PACKED_SWITCH, 1, intArrayOf(0), target = 8),
                Insn(Opcode.CONST, 2, intArrayOf(1), literal = 1), // default
                Insn(Opcode.RETURN, 3, intArrayOf(1)),
                Insn(Opcode.CONST, 4, intArrayOf(1), literal = 10), // case 0
                Insn(Opcode.RETURN, 5, intArrayOf(1)),
                Insn(Opcode.CONST, 6, intArrayOf(1), literal = 20), // case 1
                Insn(Opcode.RETURN, 7, intArrayOf(1)),
                Insn(
                    Opcode.PACKED_SWITCH_PAYLOAD, 8,
                    payload = SwitchPayload(keys = intArrayOf(0, 1), targets = intArrayOf(3, 5)),
                ),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = com.jadxmp.ir.type.IrType.INT)
        TestPipeline.cfg(method)

        val switchBlock = TestPipeline.blockAt(method, 1)
        val sw = switchBlock.instructions.last() as SwitchInstruction
        assertEquals(listOf(0, 1), sw.keys.toList())
        assertEquals(listOf(4, 6), sw.caseTargets.toList())
        assertEquals(2, sw.defaultTarget)

        val case0 = TestPipeline.blockAt(method, 4)
        val case1 = TestPipeline.blockAt(method, 6)
        val default = TestPipeline.blockAt(method, 2)
        assertEquals(setOf(case0, case1, default), switchBlock.successors.toSet())
    }

    @Test
    fun switchWithSharedCaseTargetDedupsEdges() {
        // Both keys 0 and 1 branch to the same block (offset 4); the switch->target edge must appear once.
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
                Insn(Opcode.PACKED_SWITCH, 1, intArrayOf(0), target = 6),
                Insn(Opcode.CONST, 2, intArrayOf(1), literal = 1), // default
                Insn(Opcode.RETURN, 3, intArrayOf(1)),
                Insn(Opcode.CONST, 4, intArrayOf(1), literal = 2), // shared case body
                Insn(Opcode.RETURN, 5, intArrayOf(1)),
                Insn(Opcode.PACKED_SWITCH_PAYLOAD, 6, payload = SwitchPayload(intArrayOf(0, 1), intArrayOf(3, 3))),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = com.jadxmp.ir.type.IrType.INT)
        TestPipeline.cfg(method)
        val switchBlock = TestPipeline.blockAt(method, 1)
        val shared = TestPipeline.blockAt(method, 4)
        val default = TestPipeline.blockAt(method, 2)
        assertEquals(1, switchBlock.successors.count { it === shared }, "shared case edge must be de-duplicated")
        assertEquals(1, shared.predecessors.count { it === switchBlock })
        assertEquals(setOf(shared, default), switchBlock.successors.toSet())
    }

    @Test
    fun singleExitReachableFromEveryLeaf() {
        val method = TestPipeline.buildMethod(diamond())
        TestPipeline.cfg(method)
        val exit = method.exitBlock!!
        // every non-exit block with no successor would be a bug; only exit has none
        for (block in method.blocks) {
            if (block === exit) continue
            assertTrue(block.successors.isNotEmpty(), "block $block has no successor")
        }
    }
}
