package com.jadxmp.pipeline.structure

import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.IfInstruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.SwitchInstruction
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrContainer
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.region.IfRegion
import com.jadxmp.ir.region.LoopRegion
import com.jadxmp.ir.region.Region
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.region.SwitchRegion
import com.jadxmp.ir.region.SyncRegion
import com.jadxmp.ir.region.TryCatchRegion
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Multi-exit loop collapse for a loop whose header carries a real statement before its exit test.
 *
 * The positive case models `corpus/loops/TestLoopCondition5` (`lastIndexOf`): a `while`-shaped loop
 * whose header is `v = -1; if (i < lo) exit`, with an in-body early `return i` (a terminal side-exit)
 * *and* a normal loop-exit that also returns. Both exits are `return` blocks, so the header's immediate
 * post-dominator collapses to the method exit and the multi-exit collapse path is taken. Before the fix
 * this bailed ("multi-exit loop") because [RegionMaker.multiExitFollow] refused a header that was not a
 * *pure* test (the leading `const`); it now identifies the header's out-of-loop edge as the follow, pulls
 * the terminal early-return into the body, and structures as `while (true) { v=-1; if(i<lo) break; … }`.
 *
 * The negative case guards the widening: a loop with TWO genuinely non-terminal exits (one exit target
 * shared with flow from OUTSIDE the loop, so it cannot be pulled into the body — the shape of the inner
 * iterator loop in `TestLoopRestore3`) must STILL bail honestly, never a mis-structured tree.
 */
class RegionMakerMultiExitLoopTest {

    private fun findLoop(region: Region?): LoopRegion? {
        val out = ArrayList<LoopRegion>()
        fun visit(r: Region?) {
            when (r) {
                is LoopRegion -> { out.add(r); visit(r.body) }
                is SequenceRegion -> r.children.forEach { if (it is Region) visit(it) }
                is IfRegion -> { visit(r.thenRegion); r.elseRegion?.let { visit(it) } }
                else -> {}
            }
        }
        visit(region)
        return out.firstOrNull()
    }

    private fun collectOpcodes(region: Region): List<IrOpcode> {
        val out = ArrayList<IrOpcode>()
        fun visit(c: IrContainer?) {
            when (c) {
                is BasicBlock -> c.instructions.forEach { out.add(it.opcode) }
                is SequenceRegion -> c.children.forEach { visit(it) }
                is IfRegion -> { visit(c.thenRegion); c.elseRegion?.let { visit(it) } }
                is LoopRegion -> visit(c.body)
                else -> {}
            }
        }
        visit(region)
        return out
    }

    /** The invariant every structured method must keep: no emitted block leaks an un-consumed branch. */
    private fun assertNoLeakedBranch(method: IrMethod) {
        val region = method.region ?: return // honest bail is fine
        val emitted = HashSet<BasicBlock>()
        fun walk(c: IrContainer?) {
            when (c) {
                is BasicBlock -> emitted.add(c)
                is SequenceRegion -> c.children.forEach { walk(it) }
                is IfRegion -> { walk(c.thenRegion); c.elseRegion?.let { walk(it) } }
                is LoopRegion -> walk(c.body)
                is SwitchRegion -> { c.cases.forEach { walk(it.body) }; c.defaultCase?.let { walk(it) } }
                is TryCatchRegion -> { walk(c.tryRegion); c.catches.forEach { walk(it.body) }; c.finallyRegion?.let { walk(it) } }
                is SyncRegion -> walk(c.body)
                else -> {}
            }
        }
        walk(region)
        for (b in emitted) {
            val last = b.instructions.lastOrNull() ?: continue
            val leaks = (last is IfInstruction || last is SwitchInstruction) && !last.contains(AttrFlag.DONT_GENERATE)
            assertFalse(leaks, "block B${b.id} leaks an un-consumed branch as a bare statement")
        }
    }

    @Test
    fun whileWithHeaderStatementAndTerminalSideExitCollapsesToOneFollow() {
        // int lastIndexOf(int a, int lo, int target):   (a=p0=r2, lo=p1=r3, target=p2=r4; i=r0, ret=r1)
        //   i = a - 1;
        //   while (true) { r1 = -1; if (i < lo) break; if (i != target) { i--; continue; } return i; }
        //   return r1;   // == -1
        val reader = FakeCodeReader(
            5,
            listOf(
                Insn(Opcode.ADD_INT_LIT, 0, intArrayOf(0, 2), literal = -1), // B0: i = a - 1
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = -1), // B1 header: r1 = -1  (a real stmt)
                Insn(Opcode.IF_LT, 2, intArrayOf(0, 3), target = 7), // B1: if (i < lo) -> B5 (exit)
                Insn(Opcode.IF_NE, 3, intArrayOf(0, 4), target = 5), // B2: if (i != target) -> B4
                Insn(Opcode.RETURN, 4, intArrayOf(0)), // B3: return i   (in-body terminal side-exit)
                Insn(Opcode.ADD_INT_LIT, 5, intArrayOf(0, 0), literal = -1), // B4: i = i - 1
                Insn(Opcode.GOTO, 6, target = 1), // B4: back edge to header
                Insn(Opcode.RETURN, 7, intArrayOf(1)), // B5: return r1  (== -1)
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            returnType = IrType.INT,
            argTypes = listOf(IrType.INT, IrType.INT, IrType.INT),
        )
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "a header-with-statement multi-exit while must structure")
        assertFalse(method.contains(AttrFlag.HAS_ERROR), "correct structuring ⇒ no error flag")
        assertNotNull(method.region, "region must be produced")
        assertNoLeakedBranch(method)

        val loop = findLoop(method.region)
        assertNotNull(loop, "a LoopRegion must be produced")
        // The single conditional loop-exit is a break; the in-body early return stays a return.
        val ops = collectOpcodes(loop.body)
        assertTrue(ops.any { it == IrOpcode.BREAK }, "the header exit test must become a break")
        assertTrue(ops.any { it == IrOpcode.RETURN }, "the in-body early return must remain in the loop body")
        // Both return blocks are placed exactly once (no drop, no duplication of the distinct returns).
        val returnBlocks = method.blocks.filter { b -> b.instructions.any { it.opcode == IrOpcode.RETURN } }
        assertEquals(2, returnBlocks.size, "sanity: two distinct return blocks in the CFG")
    }

    @Test
    fun loopWithTwoNonTerminalExitsSharedWithOutsideStillBails() {
        // A loop with TWO non-terminal exits — the exhausted exit target X is ALSO reached from the
        // pre-check OUTSIDE the loop (so X is not header-dominated and cannot be pulled in), and the found
        // exit target Y is distinct. This is the inner-iterator shape of TestLoopRestore3 and has no single
        // loop follow: it MUST bail honestly (region null), never mis-structure.  (p0=r2, p1=r3; r0,r1 locals)
        val reader = FakeCodeReader(
            4,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0), // r0 = 0
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = 0), // r1 = 0
                Insn(Opcode.IF_EQZ, 2, intArrayOf(2), target = 7), // pre-check: if (p0==0) -> X (outside loop)
                Insn(Opcode.IF_EQZ, 3, intArrayOf(3), target = 7), // header: if (p1==0) -> X (exhausted exit)
                Insn(Opcode.IF_NEZ, 4, intArrayOf(0), target = 8), // body: if (r0!=0) -> Y (found exit)
                Insn(Opcode.ADD_INT_LIT, 5, intArrayOf(0, 0), literal = 1), // r0 = r0 + 1
                Insn(Opcode.GOTO, 6, target = 3), // back edge to header
                Insn(Opcode.ADD_INT_LIT, 7, intArrayOf(1, 1), literal = 5), // X: r1 = r1 + 5  -> falls to Y
                Insn(Opcode.RETURN, 8, intArrayOf(1)), // Y (merge): return r1
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT, argTypes = listOf(IrType.INT, IrType.INT))
        TestPipeline.structured(method)

        assertNull(method.region, "a genuine two-non-terminal-exit loop must bail (no single follow)")
        assertFalse(method[PipelineAttrs.FULLY_STRUCTURED] == true, "the unstructurable loop must not be flagged structured")
        assertNoLeakedBranch(method)
    }
}
