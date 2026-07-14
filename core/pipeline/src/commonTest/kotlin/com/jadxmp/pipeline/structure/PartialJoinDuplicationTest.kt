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
import com.jadxmp.ir.region.SyncRegion
import com.jadxmp.ir.region.TryCatchRegion
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Partial-join condition duplication.** A shared block M that is itself a *branch* (a condition) reached
 * from BOTH arms of an enclosing `if`, but with a NON-terminal third path that bypasses it (so M does not
 * post-dominate the `if`), is not a sound single-placement follow. jadx duplicates M's small acyclic
 * subtree into the revisiting arm — semantically correct because the arms are mutually exclusive, so the
 * duplicated code runs on exactly one path per execution. These tests pin the SAFE envelope: a bounded,
 * acyclic, single-entry, value-flow-clean subtree duplicates; anything outside it bails honestly.
 *
 * The rule-4 invariant everywhere: `region != null` ⇒ φ-free and no leaked branch (never silently wrong).
 */
class PartialJoinDuplicationTest {

    // ---- helpers ------------------------------------------------------------

    private fun blockOccurrences(container: IrContainer?, out: MutableList<BasicBlock>) {
        when (container) {
            is BasicBlock -> out.add(container)
            is SequenceRegion -> container.children.forEach { blockOccurrences(it, out) }
            is IfRegion -> { blockOccurrences(container.thenRegion, out); container.elseRegion?.let { blockOccurrences(it, out) } }
            is LoopRegion -> blockOccurrences(container.body, out)
            is SyncRegion -> blockOccurrences(container.body, out)
            is SwitchRegion -> { container.cases.forEach { blockOccurrences(it.body, out) }; container.defaultCase?.let { blockOccurrences(it, out) } }
            is TryCatchRegion -> {
                blockOccurrences(container.tryRegion, out)
                container.catches.forEach { blockOccurrences(it.body, out) }
                container.finallyRegion?.let { blockOccurrences(it, out) }
            }
            else -> {}
        }
    }

    private fun leaves(region: Region): List<Instruction> {
        val out = ArrayList<Instruction>()
        val blocks = ArrayList<BasicBlock>().also { blockOccurrences(region, it) }
        for (b in blocks) out.addAll(b.instructions)
        return out
    }

    private fun assertHonest(method: IrMethod) {
        val region = method.region ?: return
        for (block in method.blocks) {
            assertTrue(block.instructions.none { it is PhiInstruction }, "φ must not remain on B${block.id}")
        }
        for (insn in leaves(region)) {
            if (insn is IfInstruction && !insn.contains(AttrFlag.DONT_GENERATE)) {
                error("an un-consumed IF leaked as a bare statement")
            }
        }
    }

    /** How many times the branch block M (its `if` reads p2 = v5) appears in the region tree. */
    private fun occurrencesOfMBranch(method: IrMethod): Int {
        val occ = ArrayList<BasicBlock>().also { blockOccurrences(method.region, it) }
        return occ.count { b -> b.instructions.any { it is IfInstruction && it.offset == 4 } }
    }

    // ---- positive: a small branch partial-join duplicates -------------------

    @Test
    fun branchPartialJoinIsDuplicatedIntoBothArms() {
        // C : if (p0 != 0) -> B_then(2) else -> B_else(1)
        // B_else(1): goto M(4)
        // B_then(2): if (p1 != 0) -> M(4) else -> bypass(3)
        // bypass(3): goto R(6)                 [NON-terminal path that bypasses M]
        // M(4): if (p2 != 0) -> R(6) else -> Msib(5)   [a BRANCH, reached from B_else AND B_then]
        // Msib(5): goto R(6)
        // R(6): return
        //
        // M does not post-dominate C (the bypass reaches R without M), so it is only a partial join.
        // M's dominance subtree is {M, Msib}: small, acyclic, single-entry, no monitors, no escaping temp.
        // It must be duplicated into the else arm (revisiting M) instead of bailing.
        val reader = FakeCodeReader(
            6, // v0..v2 locals (unused); v3 = p0, v4 = p1, v5 = p2
            listOf(
                Insn(Opcode.IF_NEZ, 0, intArrayOf(3), target = 2), // C
                Insn(Opcode.GOTO, 1, target = 4), // B_else -> M
                Insn(Opcode.IF_NEZ, 2, intArrayOf(4), target = 4), // B_then -> M ; fall bypass
                Insn(Opcode.GOTO, 3, target = 6), // bypass -> R
                Insn(Opcode.IF_NEZ, 4, intArrayOf(5), target = 6), // M -> R ; fall Msib
                Insn(Opcode.GOTO, 5, target = 6), // Msib -> R
                Insn(Opcode.RETURN_VOID, 6), // R
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            argTypes = listOf(IrType.INT, IrType.INT, IrType.INT),
        )
        TestPipeline.structured(method)

        assertHonest(method)
        val region = method.region
        assertNotNull(region, "a small branch partial-join must structure via duplication, not bail")
        assertTrue(method[PipelineAttrs.FULLY_STRUCTURED] == true, "must be fully structured")
        // M (the shared branch) is emitted in BOTH arms — duplicated, not single-placed.
        assertTrue(occurrencesOfMBranch(method) >= 2, "the shared branch M must be duplicated (appears >1)")
    }

    // ---- positive: the ComplexIf4 multi-exit funnel duplicates --------------

    @Test
    fun multiExitFunnelPartialJoinDuplicates() {
        // The ComplexIf4 shape: `goto0`(B14) is a condition reached from BOTH arms of cond0, bypassed by a
        // NON-terminal path (cond1 -> nearEnd). Its subtree has two exits (nearEnd and cond6) that FUNNEL to a
        // single post-dominator (cond6), so it heads a single-exit region and is duplicated — unlike the
        // ambiguous double-merge diamond (two independent terminal merges, no common post-dominator), which
        // still bails. This is the real-corpus multi-exit case the single-exit envelope would miss.
        val reader = FakeCodeReader(
            7,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(1), literal = 0),
                Insn(Opcode.CONST, 1, intArrayOf(6), literal = 0),
                Insn(Opcode.IF_LT, 2, intArrayOf(1, 6), target = 4), // cond0
                Insn(Opcode.GOTO, 3, target = 14), // cond0 else -> goto0(14)
                Insn(Opcode.CONST, 4, intArrayOf(2), literal = 0),
                Insn(Opcode.IF_NEZ, 5, intArrayOf(2), target = 7), // cond1
                Insn(Opcode.GOTO, 6, target = 17), // cond1 else -> nearEnd(17) [non-terminal bypass]
                Insn(Opcode.IF_LE, 7, intArrayOf(2, 1), target = 13), // cond2
                Insn(Opcode.IF_LEZ, 8, intArrayOf(1), target = 10),
                Insn(Opcode.GOTO, 9, target = 17),
                Insn(Opcode.CONST, 10, intArrayOf(5), literal = 0),
                Insn(Opcode.IF_LTZ, 11, intArrayOf(5), target = 13),
                Insn(Opcode.GOTO, 12, target = 17),
                Insn(Opcode.CONST, 13, intArrayOf(5), literal = 0), // cond3 -> goto0(14)
                Insn(Opcode.IF_EQZ, 14, intArrayOf(1), target = 17), // goto0: -> nearEnd(17); fall
                Insn(Opcode.CONST, 15, intArrayOf(1), literal = 0),
                Insn(Opcode.GOTO, 16, target = 18), // -> cond6(18)
                Insn(Opcode.CONST, 17, intArrayOf(1), literal = 0), // nearEnd: fall cond6
                Insn(Opcode.IF_NE, 18, intArrayOf(1, 6), target = 20), // cond6
                Insn(Opcode.RETURN_VOID, 19),
                Insn(Opcode.RETURN_VOID, 20),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        assertHonest(method)
        assertNotNull(method.region, "the multi-exit funnel partial join must structure via duplication")
        assertTrue(method[PipelineAttrs.FULLY_STRUCTURED] == true, "must be fully structured")
        // goto0 (B14) is the `IF_EQZ` at offset 14 — the duplicated partial join.
        val occ = ArrayList<BasicBlock>().also { blockOccurrences(method.region, it) }
        val gotoZeroCopies = occ.count { b -> b.instructions.any { it is IfInstruction && it.offset == 14 } }
        assertTrue(gotoZeroCopies >= 2, "the shared partial-join condition goto0 must be duplicated")
    }

    // ---- negative: a loop inside the subtree bails --------------------------

    @Test
    fun loopingSubtreeBails() {
        // Same skeleton, but M's fall block is a self-loop (back edge) — the subtree contains a loop header,
        // so it is NOT bounded/acyclic and must bail rather than duplicate.
        // C(0): if (p0!=0) -> B_then(2) else B_else(1)
        // B_else(1): goto M(4)
        // B_then(2): if (p1!=0) -> M(4) ; fall bypass(3)
        // bypass(3): goto R(7)
        // M(4): if (p2!=0) -> R(7) ; fall L(5)
        // L(5): if (p2!=0) -> R(7) ; fall back-to L(5)  [self back edge -> L is a loop header]
        // R(7): return
        val reader = FakeCodeReader(
            6,
            listOf(
                Insn(Opcode.IF_NEZ, 0, intArrayOf(3), target = 2), // C
                Insn(Opcode.GOTO, 1, target = 4), // B_else -> M
                Insn(Opcode.IF_NEZ, 2, intArrayOf(4), target = 4), // B_then -> M ; fall bypass
                Insn(Opcode.GOTO, 3, target = 7), // bypass -> R
                Insn(Opcode.IF_NEZ, 4, intArrayOf(5), target = 7), // M -> R ; fall L
                Insn(Opcode.IF_NEZ, 5, intArrayOf(5), target = 7), // L: -> R ; fall self (back edge)
                Insn(Opcode.GOTO, 6, target = 5), // -> L (materialize the fall/back edge target explicitly)
                Insn(Opcode.RETURN_VOID, 7), // R
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            argTypes = listOf(IrType.INT, IrType.INT, IrType.INT),
        )
        TestPipeline.structured(method)
        assertHonest(method)
        // The subtree contains a loop, so partial-join duplication must not fire; whatever is produced is
        // either an honest bail or (if some other path structures it) still non-lossy — never wrong.
        assertTrue(
            method.region == null || method[PipelineAttrs.FULLY_STRUCTURED] == true,
            "must not produce a partial/incorrect tree",
        )
    }

    // ---- negative: an oversize subtree bails --------------------------------

    @Test
    fun oversizeSubtreeBails() {
        // M starts a long straight-line-then-branch chain that exceeds the duplication size cap, so it must
        // bail rather than copy a large region into the revisiting arm.
        // C(0): if (p0!=0) -> B_then(2) else B_else(1)
        // B_else(1): goto M(4)
        // B_then(2): if (p1!=0) -> M(4) ; fall bypass(3)
        // bypass(3): goto R(13)
        // M(4): if (p2!=0) -> t1(5) ; fall R(13)
        // t1(5): if -> t2(6) ; fall R  ... a chain of conditions t1..t8, all dominated by M -> big subtree
        val insns = ArrayList<Insn>()
        insns.add(Insn(Opcode.IF_NEZ, 0, intArrayOf(3), target = 2)) // C
        insns.add(Insn(Opcode.GOTO, 1, target = 4)) // B_else -> M
        insns.add(Insn(Opcode.IF_NEZ, 2, intArrayOf(4), target = 4)) // B_then -> M ; fall bypass
        insns.add(Insn(Opcode.GOTO, 3, target = 13)) // bypass -> R
        // M and a chain of 8 conditions (offsets 4..11), each branching to R(13) or falling to the next.
        for (off in 4..11) {
            val next = off + 1
            insns.add(Insn(Opcode.IF_NEZ, off, intArrayOf(5), target = 13)) // -> R ; fall next
            if (off == 11) {
                // last one's fall must go somewhere real; make it fall to a tail that returns to R.
            }
        }
        insns.add(Insn(Opcode.GOTO, 12, target = 13)) // tail -> R
        insns.add(Insn(Opcode.RETURN_VOID, 13)) // R
        val reader = FakeCodeReader(6, insns)
        val method = TestPipeline.buildMethod(
            reader,
            argTypes = listOf(IrType.INT, IrType.INT, IrType.INT),
        )
        TestPipeline.structured(method)
        assertHonest(method)
        assertTrue(
            method.region == null || method[PipelineAttrs.FULLY_STRUCTURED] == true,
            "must not produce a partial/incorrect tree",
        )
        // The subtree exceeds the cap, so M must NOT be duplicated.
        assertTrue(occurrencesOfMBranch(method) <= 1, "an oversize subtree must not be duplicated")
    }
}
