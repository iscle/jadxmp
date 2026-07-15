package com.jadxmp.pipeline.cfg

import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.IfInstruction
import com.jadxmp.ir.insn.SwitchInstruction
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.region.IfRegion
import com.jadxmp.ir.region.LoopRegion
import com.jadxmp.ir.region.Region
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.region.SwitchRegion
import com.jadxmp.ir.region.SyncRegion
import com.jadxmp.ir.region.TryCatchRegion
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Node-splitting for irreducible **multi-entry loops** ([FixMultiEntryLoops]). The two-entry cycle used
 * here mirrors `corpus/smali/loops/TestMultiEntryLoop`: an initial branch either falls through a
 * straight-line preamble into a shared body block `C` (entry 1) or jumps straight to the exit-test
 * header (entry 2), and a latch closes the cycle back to `C`. That cycle has two entries (`C` and the
 * test) so it is irreducible; duplicating the straight-line `C` onto the preamble path makes it reducible.
 */
class FixMultiEntryLoopsTest {

    private val f = FakeMethodRef("Lc/F;", "f", "V", emptyList())

    private fun invoke(offset: Int) =
        Insn(Opcode.INVOKE_STATIC, offset, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = f)

    /**
     * A two-entry loop shaped like TestMultiEntryLoop, kept **φ-free** (the only register `r0` is defined
     * once in the entry block, dominating every use) so that de-SSA is trivial and the test isolates the
     * CFG transform. Body blocks are side-effecting `f()` calls (never dead-code-eliminated, so the blocks
     * survive as distinct nodes). Cycle = {C(3), header(4), latch(5,6)} with entries C (via preamble 2)
     * and the header (via B0's direct branch).
     */
    private fun multiEntryLoop(): FakeCodeReader = FakeCodeReader(
        1,
        listOf(
            Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0), // r0 = 0 (single dominating def)
            Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4), // B0: if (r0==0) -> header(4) else preamble(2)
            invoke(2), // preamble (entry 1) -> C
            invoke(3), // C (shared body) -> header
            Insn(Opcode.IF_EQZ, 4, intArrayOf(0), target = 7), // header: if (r0==0) -> exit(7) else latch(5)
            invoke(5), // latch
            Insn(Opcode.GOTO, 6, target = 3), // back edge to C
            Insn(Opcode.RETURN_VOID, 7),
        ),
    )

    /** Baseline block count of the same body with the CFG built but not transformed. */
    private fun baselineBlocks(reader: () -> FakeCodeReader): Int =
        TestPipeline.buildMethod(reader(), methodName = "b").let { TestPipeline.cfg(it); it.blocks.size }

    @Test
    fun multiEntryLoopBecomesReducibleWithOneDuplicatedBlock() {
        val method = TestPipeline.buildMethod(multiEntryLoop(), methodName = "m")
        // Run through dominators (which invokes FixMultiEntryLoops), then check the CFG contract directly.
        TestPipeline.dominators(method)

        assertTrue(isReducible(method), "the multi-entry loop must be reducible after node-splitting")
        assertEquals(
            baselineBlocks(::multiEntryLoop) + 1,
            method.blocks.size,
            "exactly one straight-line block is duplicated (bounded node-splitting)",
        )
    }

    @Test
    fun reducedMultiEntryLoopStructuresFully() {
        val method = TestPipeline.buildMethod(multiEntryLoop(), methodName = "m")
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "the split multi-entry loop must structure")
        assertFalse(method.contains(AttrFlag.HAS_ERROR), "a faithfully structured method carries no error")
        assertNotNull(findLoop(method.region), "a real LoopRegion must be produced")
        assertNoLeakedBranch(method)
    }

    /**
     * A two-entry loop where BOTH entries are fed by the SAME predecessor block, which BRANCHES directly
     * to the header. jadx's `isEndBlockEntry` would reroute one arm through the duplicated block and tear
     * down the other — collapsing the two-way branch and silently changing control flow. The added guard
     * refuses the split, so the CFG stays irreducible (no duplication) and structuring honestly BAILS to
     * an unstructured method (region null) rather than emitting mis-structured code. Kept φ-free so the
     * bail is specifically the reducibility check, not an unrelated de-SSA failure.
     */
    @Test
    fun sharedPredecessorTwoEntryLoopIsNotSplitAndBails() {
        val reader = {
            FakeCodeReader(
                1,
                listOf(
                    Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
                    Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4), // B0 branches to header(4) AND falls to C(2)
                    invoke(2), // C part 1
                    invoke(3), // C part 2 -> header
                    Insn(Opcode.IF_EQZ, 4, intArrayOf(0), target = 7), // header -> exit(7) or latch(5)
                    invoke(5), // latch
                    Insn(Opcode.GOTO, 6, target = 2), // back edge to C
                    Insn(Opcode.RETURN_VOID, 7),
                ),
            )
        }
        val method = TestPipeline.buildMethod(reader(), methodName = "m")
        TestPipeline.dominators(method)

        // The unsafe shape is refused: no duplication and the CFG remains irreducible.
        assertEquals(baselineBlocks(reader), method.blocks.size, "the guard must refuse to duplicate the unsafe shape")
        assertFalse(isReducible(method), "the guarded shape stays irreducible (no unsafe split performed)")

        // End to end it is an honest bail: unstructured, never mis-structured.
        TestPipeline.structured(method)
        assertNull(method.region, "an unfixable irreducible loop stays unstructured")
        assertFalse(method[PipelineAttrs.FULLY_STRUCTURED] == true, "must not claim to be structured")
        assertNoLeakedBranch(method)
    }

    /**
     * **Rule-4 polarity guard (shape 1 / header-successor entry).** A multi-entry loop whose latch is a
     * CONDITIONAL two-way `if(cond) goto header else exit` — the back-edge (taken) arm is `successors[0]`.
     * Node-splitting reroutes that arm through the duplicated header. Because branch-arm identity is
     * POSITIONAL, the reroute must keep the taken arm at index 0 (now pointing at the copy that leads back
     * into the loop) and the exit at index 1 — a slot swap would invert the loop-continue/exit test while
     * the condition text is unchanged (recompilable-but-wrong). Asserts the exact slot order plus full,
     * error-free structuring.
     *
     * CFG: D `if r0==0 goto H(4) else SE(2)`; SE(2,3) -> LE(6); H(4,5) straight -> SE(2); LE(6)
     * `if r0==0 goto H(4) else exit(7)`. Cycle {H,SE,LE} entered at H and SE (multi-entry).
     */
    @Test
    fun conditionalLatchKeepsBranchPolarityAfterHeaderSplit() {
        val method = TestPipeline.buildMethod(
            FakeCodeReader(
                1,
                listOf(
                    Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
                    Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4), // D: taken->H(4), fall->SE(2)
                    invoke(2), Insn(Opcode.GOTO, 3, target = 6), // SE -> LE(6)
                    invoke(4), Insn(Opcode.GOTO, 5, target = 2), // H (straight) -> SE(2)
                    Insn(Opcode.IF_EQZ, 6, intArrayOf(0), target = 4), // LE latch: taken(succ0)->H(4), fall->exit(7)
                    Insn(Opcode.RETURN_VOID, 7),
                ),
            ),
            methodName = "m",
        )
        TestPipeline.structured(method)

        val latch = blockWithOffset(method, 6) // the conditional latch (its IF)
        assertEquals(2, latch.successors.size, "latch stays two-way")
        // succ[0] (taken / back-edge arm) must reach the DUPLICATED header code (offset 4), NOT the exit.
        assertTrue(latch.successors[0].instructions.any { it.offset == 4 }, "taken arm (succ[0]) still routes into the loop via the copy")
        assertTrue(latch.successors[1].instructions.any { it.offset == 7 }, "fall-through arm (succ[1]) still exits")

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "the split loop structures")
        assertFalse(method.contains(AttrFlag.HAS_ERROR))
        assertNoLeakedBranch(method)
    }

    /**
     * **Rule-4 polarity guard (shape 2 / end-block entry, the reviewer's Case A).** The cross-edge start
     * `X = if(c2) goto LE else Z` reaches the loop-end LE on its TAKEN arm (`successors[0]`). Node-splitting
     * duplicates LE onto that arm. The reroute must keep the taken arm at index 0 (now the copy that leads
     * into the loop) and Z at index 1 — a slot swap would invert `X`'s if. This topology (a loop reached
     * from two branch arms) honestly BAILS at structuring; the assertion is that branch polarity is never
     * inverted and no wrong code is emitted (honest bail, no leaked branch).
     *
     * CFG: B0 `if goto H(4) else X(2)`; X(2) `if goto LE(8) else Z(3,return)`; H(4,5) -> M(6); M(6)
     * `if goto LE(8) else exit(7)`; LE(8,9) straight -> H(4). Cycle {H,M,LE} entered at H and LE.
     */
    @Test
    fun crossEdgeStartKeepsBranchPolarityAfterEndBlockSplit() {
        val method = TestPipeline.buildMethod(
            FakeCodeReader(
                1,
                listOf(
                    Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
                    Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4), // B0: taken->H(4), fall->X(2)
                    Insn(Opcode.IF_EQZ, 2, intArrayOf(0), target = 8), // X: taken(succ0)->LE(8), fall->Z(3)
                    Insn(Opcode.RETURN_VOID, 3), // Z
                    invoke(4), Insn(Opcode.GOTO, 5, target = 6), // H -> M(6)
                    Insn(Opcode.IF_EQZ, 6, intArrayOf(0), target = 8), // M: taken->LE(8), fall->exit(7)
                    Insn(Opcode.RETURN_VOID, 7), // exit
                    invoke(8), Insn(Opcode.GOTO, 9, target = 4), // LE (straight) -> H(4)
                ),
            ),
            methodName = "m",
        )
        TestPipeline.structured(method)

        val x = blockWithOffset(method, 2) // the cross-edge start (its IF)
        assertEquals(2, x.successors.size, "X stays two-way")
        // succ[0] (taken arm) must reach the DUPLICATED loop-end code (offset 8), NOT Z's return.
        assertTrue(x.successors[0].instructions.any { it.offset == 8 }, "taken arm (succ[0]) still routes into the loop via the copy")
        assertTrue(x.successors[1].instructions.any { it.offset == 3 }, "fall-through arm (succ[1]) still reaches Z")

        // This topology honestly bails; the guarantee is no inverted/leaked branch — never wrong code.
        assertNoLeakedBranch(method)
    }

    /**
     * **Shape 3 — fallback header back-edge split (the TestMultiEntryLoop2 orientation).** A two-entry
     * cycle {H, L, M} where the *second* entry is the straight-line back-edge header `M` reached from an
     * outside block `G` via a DFS *tree* edge — NOT the header-successor cross edge shape 1 keys on, and
     * with no cross edge into the loop-end, so shape 2 also declines. jadxmp's DFS discovers `M` before the
     * dominating exit-test header `H`, so the multi-entry back edge is `L -> M` (header `M`, straight-line
     * `M -> H`). The fallback duplicates `M` onto the back edge (`L -> copy(M) -> H`), leaving `G -> M -> H`
     * intact, making the loop single-entry (header `H`) and reducible. Mirrors `corpus/.../TestMultiEntryLoop2`.
     *
     * CFG: B0 `if r0==0 goto G(4) else F(2)`; F(2,3) -> H(6); G(4,5) -> M(8); H(6) `if r0==0 goto exit(10)
     * else L(7)`; L(7) latch `if r0==0 goto H(6) else M(8)`; M(8,9) straight -> H(6). Cycle {H,L,M} entered
     * at H (from F) and M (from G).
     */
    @Test
    fun headerBackEdgeSplitReducesTreeEntryTwoEntryLoop() {
        val reader = {
            FakeCodeReader(
                1,
                listOf(
                    Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
                    Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4), // B0: taken->G(4), fall->F(2)
                    invoke(2), Insn(Opcode.GOTO, 3, target = 6), // F -> H(6)
                    invoke(4), Insn(Opcode.GOTO, 5, target = 8), // G -> M(8)
                    Insn(Opcode.IF_EQZ, 6, intArrayOf(0), target = 10), // H: taken->exit(10), fall->L(7)
                    Insn(Opcode.IF_EQZ, 7, intArrayOf(0), target = 6), // L latch: taken(succ0)->H(6), fall->M(8)
                    invoke(8), Insn(Opcode.GOTO, 9, target = 6), // M (straight) -> H(6)
                    Insn(Opcode.RETURN_VOID, 10), // exit
                ),
            )
        }
        val method = TestPipeline.buildMethod(reader(), methodName = "m")
        TestPipeline.dominators(method)

        assertTrue(isReducible(method), "the tree-entry multi-entry loop must be reducible after node-splitting")
        assertEquals(
            baselineBlocks(reader) + 1,
            method.blocks.size,
            "exactly one straight-line header block is duplicated (bounded node-splitting)",
        )

        // Rule-4 polarity: the latch L is a two-way `if`; its taken arm (succ[0]) targets H and is left
        // untouched, its fall-through arm (succ[1], the back edge to M) is the one rerouted to copy(M).
        // The slot order MUST be preserved — a swap would invert the loop-continue/exit test.
        val latch = blockWithOffset(method, 7)
        assertEquals(2, latch.successors.size, "latch stays two-way")
        assertTrue(latch.successors[0].instructions.any { it.offset == 6 }, "taken arm (succ[0]) still targets H(6)")
        assertTrue(
            latch.successors[1].instructions.any { it.offset == 8 },
            "fall-through arm (succ[1]) routes into the duplicated M (offset 8)",
        )
    }

    @Test
    fun headerBackEdgeSplitTwoEntryLoopStructuresFully() {
        val method = TestPipeline.buildMethod(
            FakeCodeReader(
                1,
                listOf(
                    Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
                    Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4),
                    invoke(2), Insn(Opcode.GOTO, 3, target = 6),
                    invoke(4), Insn(Opcode.GOTO, 5, target = 8),
                    Insn(Opcode.IF_EQZ, 6, intArrayOf(0), target = 10),
                    Insn(Opcode.IF_EQZ, 7, intArrayOf(0), target = 6),
                    invoke(8), Insn(Opcode.GOTO, 9, target = 6),
                    Insn(Opcode.RETURN_VOID, 10),
                ),
            ),
            methodName = "m",
        )
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "the split two-entry loop must structure")
        assertFalse(method.contains(AttrFlag.HAS_ERROR), "a faithfully structured method carries no error")
        assertNotNull(findLoop(method.region), "a real LoopRegion must be produced")
        assertNoLeakedBranch(method)
    }

    // ---- helpers ------------------------------------------------------------

    private fun blockWithOffset(method: IrMethod, offset: Int) =
        method.blocks.first { b -> b.instructions.any { it.offset == offset } }

    /** RegionMaker's reducibility test, replicated (no exception edges here, so raw == clean successors). */
    private fun isReducible(m: IrMethod): Boolean {
        for (b in m.blocks) {
            for (s in b.successors) {
                if (s.order != -1 && b.order != -1 && s.order <= b.order && s.id !in b.dominators) return false
            }
        }
        return true
    }

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

    private fun assertNoLeakedBranch(method: IrMethod) {
        val region = method.region ?: return
        val emitted = HashSet<com.jadxmp.ir.node.BasicBlock>()
        fun walk(c: com.jadxmp.ir.node.IrContainer?) {
            when (c) {
                is com.jadxmp.ir.node.BasicBlock -> emitted.add(c)
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
            assertFalse(leaks, "block B${b.id} leaks an un-consumed branch")
        }
    }
}
