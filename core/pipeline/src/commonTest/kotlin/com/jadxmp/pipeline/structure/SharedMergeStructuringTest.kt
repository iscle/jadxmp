package com.jadxmp.pipeline.structure

import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.IfInstruction
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.PhiInstruction
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrContainer
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.region.IfRegion
import com.jadxmp.ir.region.LoopRegion
import com.jadxmp.ir.region.Region
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.region.SwitchRegion
import com.jadxmp.ir.region.TryCatchRegion
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Shared conditional merge** structuring — the case where a merge block M reached from BOTH arms of an
 * enclosing `if` is *itself a condition* (it starts the next nested structure). These tests pin down two
 * things that RegionMaker must always honour (rule 4):
 *
 *  1. A **genuinely sound** shared conditional merge — one that post-dominates the enclosing branch
 *     (directly, or *modulo terminal `return`/`throw` arms*) with no entry from outside the branch —
 *     structures FULLY, with M emitted once and no branch left leaking as a bare comparison. This is
 *     handled by the immediate-post-dominator merge and, when early terminals collapse the post-dominator
 *     to the method exit, by the dominance-frontier out-block ([RegionMaker.findOutBlock] /
 *     `isGenuineMerge`). Both merge-detection paths are exercised below.
 *
 *  2. A shared block that is only a **partial join** — reached from both arms but with a *non-terminal*
 *     third path that bypasses it, so it does NOT post-dominate the branch even modulo terminals — is NOT
 *     a sound single-placement follow (routing both arms to stop at it would strand the bypassing path).
 *     RegionMaker must never mis-place it: it either bails to the honest null-region fallback, or (should a
 *     future duplication pass land) structures it losslessly. The invariant asserted is the rule-4 one:
 *     **`region != null` ⇒ no leaked branch and φ-free** — never a silently-wrong tree.
 */
class SharedMergeStructuringTest {

    // ---- helpers ------------------------------------------------------------

    private fun leaves(region: Region): List<Instruction> {
        val out = ArrayList<Instruction>()
        fun walk(c: IrContainer) {
            when (c) {
                is BasicBlock -> out.addAll(c.instructions)
                is IfRegion -> { walk(c.thenRegion); c.elseRegion?.let { walk(it) } }
                is LoopRegion -> walk(c.body)
                is SequenceRegion -> c.children.forEach { walk(it) }
                is SwitchRegion -> { c.cases.forEach { walk(it.body) }; c.defaultCase?.let { walk(it) } }
                is TryCatchRegion -> { walk(c.tryRegion); c.catches.forEach { walk(it.body) }; c.finallyRegion?.let { walk(it) } }
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

    private fun assertPhiFree(method: IrMethod) {
        for (block in method.blocks) {
            assertTrue(block.instructions.none { it is PhiInstruction }, "φ must not remain on B${block.id}")
        }
    }

    private fun countIfRegions(region: Region): Int {
        var n = 0
        fun walk(c: IrContainer) {
            when (c) {
                is IfRegion -> { n++; walk(c.thenRegion); c.elseRegion?.let { walk(it) } }
                is LoopRegion -> walk(c.body)
                is SequenceRegion -> c.children.forEach { walk(it) }
                else -> {}
            }
        }
        walk(region)
        return n
    }

    /** Rule-4 invariant: a produced tree is never silently wrong (no leaked branch, φ-free). */
    private fun assertHonest(method: IrMethod) {
        val region = method.region
        if (region != null) {
            assertPhiFree(method)
            assertNoLeakedBranch(region)
        }
    }

    // ---- positive: the B15 shape (M is itself an IF), post-dominator path ----

    @Test
    fun crossArmConditionalMergeStructuresFully() {
        // The exact "shared conditional merge" shape: C's two arms both reach M, and M is itself a two-way
        // `if`. M post-dominates C (both arms flow into it), so it is the immediate post-dominator merge and
        // is placed ONCE after the `if`, then structured as the next nested `if`. Each arm assigns a distinct
        // (live) value so C stays a genuine two-way branch (empty arms would collapse to a dead condition).
        //
        //   C : if (a != 0) -> armB else -> armA
        //   armA: r = 1; goto M        armB: r = 2; -> M
        //   M : if (b != 0) -> X else -> Y     (a genuine reconvergence that starts the next structure)
        //   X/Y: return r
        val reader = FakeCodeReader(
            4, // v0 = r (coalesced local), v1 unused; v2 = a (p0), v3 = b (p1)
            listOf(
                Insn(Opcode.IF_NEZ, 0, intArrayOf(2), target = 3), // C: a!=0 -> armB(3); fall armA(1)
                Insn(Opcode.CONST, 1, intArrayOf(0), literal = 1), // armA: r = 1; fall
                Insn(Opcode.GOTO, 2, target = 4), // armA -> M(4)
                Insn(Opcode.CONST, 3, intArrayOf(0), literal = 2), // armB: r = 2; fall M(4)
                Insn(Opcode.IF_NEZ, 4, intArrayOf(3), target = 6), // M: b!=0 -> X(6); fall Y(5)
                Insn(Opcode.RETURN, 5, intArrayOf(0)), // Y: return r
                Insn(Opcode.RETURN, 6, intArrayOf(0)), // X: return r
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT, argTypes = listOf(IrType.INT, IrType.INT))
        TestPipeline.structured(method)

        assertHonest(method)
        val region = method.region
        assertNotNull(region, "a cross-arm conditional merge (M is an IF) must structure")
        // Outer `if` at C and the merge `if` at M — the merge is structured, not dumped as a bare block.
        assertTrue(countIfRegions(region) >= 2, "both the branch and its shared conditional merge are IfRegions")
    }

    // ---- positive: modulo-terminal shared merge, dominance-frontier path -----

    @Test
    fun equalsPatternSharedMergeModuloTerminalsStructuresFully() {
        // The javac `equals()` field-comparison idiom: whichever way the null-check goes, the "fields equal"
        // paths converge at M (the next comparison) and the "fields differ" paths `return false`. M is a
        // genuine shared conditional merge that post-dominates the null-check *modulo* the terminal returns,
        // so the post-dominator collapses to the method exit and M is recovered via the dominance-frontier
        // out-block. The shared `return false` tail is duplicable and must NOT masquerade as a second merge.
        //
        //   C : if (a == null) -> cond2 else -> E
        //   E : v2 = a.equals(o.a); if (v2 != 0) -> M else -> retFalse
        //   cond2: if (o.a == null) -> M else -> retFalse
        //   retFalse: return false            (shared duplicable tail, reached from BOTH arms)
        //   M : if (c != 0) -> X else -> Y     (the shared conditional merge; non-duplicable)
        val reader = FakeCodeReader(
            6, // v0=true, v1=false, v2 scratch; v4=a(p0) v5=o.a(p1) ... args are int stand-ins
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 1), // v0 = true
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = 0), // v1 = false
                Insn(Opcode.IF_EQZ, 2, intArrayOf(4), target = 6), // C: a==null -> cond2(6); fall E
                Insn(Opcode.ADD_INT_LIT, 3, intArrayOf(2, 5), literal = 0), // E: v2 = a.equals(o.a) stand-in
                Insn(Opcode.IF_NEZ, 4, intArrayOf(2), target = 8), // E: equal -> M(8); fall
                Insn(Opcode.GOTO, 5, target = 7), // E: not-equal -> retFalse(7)
                Insn(Opcode.IF_EQZ, 6, intArrayOf(5), target = 8), // cond2: o.a==null -> M(8); fall retFalse
                Insn(Opcode.RETURN, 7, intArrayOf(1)), // retFalse: return false (shared tail)
                Insn(Opcode.IF_NE, 8, intArrayOf(4, 5), target = 10), // M: next comparison -> X(10); fall Y(9)
                Insn(Opcode.RETURN, 9, intArrayOf(0)), // Y: return true
                Insn(Opcode.RETURN, 10, intArrayOf(1)), // X: return false
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            returnType = IrType.INT,
            argTypes = listOf(IrType.INT, IrType.INT),
        )
        TestPipeline.structured(method)

        assertHonest(method)
        val region = method.region
        assertNotNull(region, "the equals()-pattern modulo-terminal shared conditional merge must structure")
        // Outer null-check `if`, its non-null `equals` arm `if`, the null arm `if`, and the merge `if`.
        assertTrue(countIfRegions(region) >= 4, "every branch including the shared merge is structured")
        // Both the shared `return false` and the two merge-arm returns survive — nothing dropped.
        assertTrue(
            leaves(region).count { it.opcode == IrOpcode.RETURN } >= 3,
            "the shared return-false tail and the merge arms are all preserved",
        )
    }

    // ---- soundness: a partial join is never mis-placed ----------------------

    @Test
    fun partialJoinConditionMergeIsNeverMisplaced() {
        // ComplexIf4-style shape. `goto0` is a condition block reached from BOTH arms of `cond0` (directly
        // on the else side, and via cond2/cond3 on the then side), BUT a NON-terminal path
        // (cond1 -> nearEnd -> cond6) bypasses it. So `goto0` does not post-dominate `cond0` even modulo
        // terminals — it is only a PARTIAL join, and routing both arms to stop at it would strand the
        // bypassing path (jadx duplicates it instead). RegionMaker must never emit a wrong tree here: it
        // bails to the honest null-region fallback today; the rule-4 invariant asserted is that IF a tree is
        // ever produced (e.g. a future sound duplication pass) it is non-lossy — no leaked branch, φ-free.
        val reader = FakeCodeReader(
            7,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(1), literal = 0), // v1 = 0
                Insn(Opcode.CONST, 1, intArrayOf(6), literal = 0), // v6 = 0
                Insn(Opcode.IF_LT, 2, intArrayOf(1, 6), target = 4), // cond0: v1<v6 -> cond1(4); fall
                Insn(Opcode.GOTO, 3, target = 14), // cond0 else -> goto0(14)
                Insn(Opcode.CONST, 4, intArrayOf(2), literal = 0), // cond1 head
                Insn(Opcode.IF_NEZ, 5, intArrayOf(2), target = 7), // cond1: -> cond2(7); fall
                Insn(Opcode.GOTO, 6, target = 17), // cond1 else -> nearEnd(17) [NON-terminal bypass of goto0]
                Insn(Opcode.IF_LE, 7, intArrayOf(2, 1), target = 13), // cond2: -> cond3(13); fall
                Insn(Opcode.IF_LEZ, 8, intArrayOf(1), target = 10), // cond2b: -> cond4(10); fall
                Insn(Opcode.GOTO, 9, target = 17), // -> nearEnd
                Insn(Opcode.CONST, 10, intArrayOf(5), literal = 0), // cond4
                Insn(Opcode.IF_LTZ, 11, intArrayOf(5), target = 13), // cond5: -> cond3(13); fall
                Insn(Opcode.GOTO, 12, target = 17), // -> nearEnd
                Insn(Opcode.CONST, 13, intArrayOf(5), literal = 0), // cond3: work; fall goto0
                Insn(Opcode.IF_EQZ, 14, intArrayOf(1), target = 17), // goto0: -> nearEnd(17); fall
                Insn(Opcode.CONST, 15, intArrayOf(1), literal = 0), // goto0-else: v1=0; fall
                Insn(Opcode.GOTO, 16, target = 18), // -> cond6(18)
                Insn(Opcode.CONST, 17, intArrayOf(1), literal = 0), // nearEnd: v1=0; fall cond6
                Insn(Opcode.IF_NE, 18, intArrayOf(1, 6), target = 20), // cond6: -> m1(20); fall m2(19)
                Insn(Opcode.RETURN_VOID, 19), // m2
                Insn(Opcode.RETURN_VOID, 20), // m1
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        // Rule 4: whatever the structurer decides, it is never silently wrong.
        assertHonest(method)
    }
}
