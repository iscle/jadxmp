package com.jadxmp.pipeline.structure

import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.IfInstruction
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrContainer
import com.jadxmp.ir.region.IfRegion
import com.jadxmp.ir.region.LoopRegion
import com.jadxmp.ir.region.Region
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Diamond-CHAIN structuring: a sequence of diamonds where each diamond's merge is the condition-header of
 * the next must structure as SIBLING [IfRegion]s at the same nesting level (each followed by the next),
 * NOT nested one inside another's arm. Each merge is owned by exactly one region — placed once by the
 * enclosing chain, which then resumes from it as the next diamond's header. Covers the post-dominator
 * merge (plain diamonds) and the dominance-frontier merge (early-return diamonds) paths.
 */
class DiamondChainStructuringTest {

    private fun topChildren(region: Region?): List<Any> =
        (region as? SequenceRegion)?.children?.toList() ?: emptyList()

    private fun topLevelIfs(region: Region?): List<IfRegion> =
        topChildren(region).filterIsInstance<IfRegion>()

    private fun leaves(region: Region): List<Instruction> {
        val out = ArrayList<Instruction>()
        fun walk(c: IrContainer) {
            when (c) {
                is BasicBlock -> out.addAll(c.instructions)
                is IfRegion -> { walk(c.thenRegion); c.elseRegion?.let { walk(it) } }
                is LoopRegion -> walk(c.body)
                is SequenceRegion -> c.children.forEach { walk(it) }
                else -> {}
            }
        }
        walk(region)
        return out
    }

    private fun assertNoLeakedBranch(region: Region) {
        for (insn in leaves(region)) {
            if (insn is IfInstruction && !insn.contains(AttrFlag.DONT_GENERATE)) {
                error("an un-consumed IF leaked as a bare statement")
            }
        }
    }

    @Test
    fun plainSiblingDiamondChainStructuresAsTwoSiblingIfs() {
        // B0: if(a) T1|E1 ; T1,E1 -> M ; M: if(b) T2|E2 ; T2,E2 -> N ; N: return
        // Both diamonds reconverge cleanly (post-dominator merges), so M is B0's follow AND diamond 2's
        // header: the two ifs must be siblings in the top sequence, each placed once.
        val reader = FakeCodeReader(
            4, // v0=a, v1=b, v2,v3 temps
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(0), target = 3), // B0: a==0 -> off3(E1); fall off1(T1)
                Insn(Opcode.CONST, 1, intArrayOf(2), literal = 1), // T1: v2=1
                Insn(Opcode.GOTO, 2, target = 4), // -> M(off4)
                Insn(Opcode.CONST, 3, intArrayOf(2), literal = 2), // E1: v2=2
                Insn(Opcode.IF_EQZ, 4, intArrayOf(1), target = 7), // M: b==0 -> off7(E2); fall off5(T2)
                Insn(Opcode.CONST, 5, intArrayOf(3), literal = 3), // T2: v3=3
                Insn(Opcode.GOTO, 6, target = 8), // -> N(off8)
                Insn(Opcode.CONST, 7, intArrayOf(3), literal = 4), // E2: v3=4
                Insn(Opcode.RETURN_VOID, 8), // N
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "a plain diamond chain must fully structure")
        val region = method.region
        assertNotNull(region)
        assertEquals(
            2,
            topLevelIfs(region).size,
            "the two diamonds are SIBLING if-regions at the top level, not nested",
        )
        for (ifr in topLevelIfs(region)) {
            assertNotNull(ifr.elseRegion, "each diamond keeps both arms (if/else)")
        }
        assertNoLeakedBranch(region)
    }

    @Test
    fun earlyReturnDiamondChainStructuresAsTwoSiblingIfs() {
        // Each diamond has a nested early-return arm, so ipostdom collapses to the exit and the merge is
        // recovered by findOutBlock. Diamond 1's recovered merge M is diamond 2's header.
        //   B0: if(a) -> B2 | B1     B1: if(b) -> RET1(return) | B4     B4,B2 -> M
        //   M : if(c) -> My | M1     M1: if(d) -> RET2(return) | M4     M4,My -> N     N: return
        val reader = FakeCodeReader(
            4, // a,b,c,d
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(0), target = 4), // B0: a==0 -> off4(B2); fall off1(B1)
                Insn(Opcode.IF_EQZ, 1, intArrayOf(1), target = 3), // B1: b==0 -> off3(RET1); fall off2(B4)
                Insn(Opcode.GOTO, 2, target = 5), // B4 -> M(off5)
                Insn(Opcode.RETURN_VOID, 3), // RET1 early return
                Insn(Opcode.GOTO, 4, target = 5), // B2 -> M(off5)
                Insn(Opcode.IF_EQZ, 5, intArrayOf(2), target = 9), // M: c==0 -> off9(My); fall off6(M1)
                Insn(Opcode.IF_EQZ, 6, intArrayOf(3), target = 8), // M1: d==0 -> off8(RET2); fall off7(M4)
                Insn(Opcode.GOTO, 7, target = 10), // M4 -> N(off10)
                Insn(Opcode.RETURN_VOID, 8), // RET2 early return
                Insn(Opcode.GOTO, 9, target = 10), // My -> N(off10)
                Insn(Opcode.RETURN_VOID, 10), // N
            ),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.INT, IrType.INT, IrType.INT, IrType.INT))
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "an early-return diamond chain must fully structure")
        val region = method.region
        assertNotNull(region)
        // The two chain heads (B0 and its recovered merge M) are siblings at the top level.
        assertEquals(
            2,
            topLevelIfs(region).size,
            "diamond 2 (headed by diamond 1's recovered merge) is a SIBLING if, not nested in an arm",
        )
        // Both early returns survive.
        assertTrue(
            leaves(region).count { it.opcode == IrOpcode.RETURN } >= 3,
            "both early returns and the final return are all preserved",
        )
        assertNoLeakedBranch(region)
    }
}
