package com.jadxmp.pipeline.structure

import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrContainer
import com.jadxmp.ir.region.IfRegion
import com.jadxmp.ir.region.LoopRegion
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.region.SwitchRegion
import com.jadxmp.ir.region.SyncRegion
import com.jadxmp.ir.region.TryCatchRegion
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.support.FakeCatchHandler
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.FakeTryBlock
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic guards for the **faithful split-range try/catch** reconstruction
 * ([RegionMaker.reconstructFaithfulRethrowTry]) — the shape `conditions/TestIfCodeStyle` exercises: a
 * SINGLE catch-all handler that re-throws but whose cleanup is a **branchy diamond** (`if (overflow) throw
 * RTE; else cleanup;`), protecting SEVERAL split try-ranges joined by non-throwing between-code, with an
 * inlined `[diamond-copy; return]` before every normal exit.
 *
 * jadx transcribes this 1:1 as `catch (Throwable e) { <diamond>; throw e; }` and keeps every inlined
 * cleanup copy inside the try body — it does NOT factor a `finally`. The reconstruction hides nothing but
 * the handler's `move-exception` (the catch param) and lets [RegionMaker.makeRegion] place every block
 * exactly once, so no cleanup copy is dropped or double-run.
 */
class RegionMakerFaithfulRethrowTryTest {

    private val sink = FakeMethodRef("Lc/F;", "sink", "V", emptyList())
    private val cleanup = FakeMethodRef("Lc/F;", "cleanup", "V", emptyList())

    private fun occurrences(container: IrContainer?, out: MutableList<BasicBlock>) {
        when (container) {
            is BasicBlock -> out.add(container)
            is SequenceRegion -> container.children.forEach { occurrences(it, out) }
            is IfRegion -> { occurrences(container.thenRegion, out); container.elseRegion?.let { occurrences(it, out) } }
            is LoopRegion -> occurrences(container.body, out)
            is SyncRegion -> occurrences(container.body, out)
            is SwitchRegion -> { container.cases.forEach { occurrences(it.body, out) }; container.defaultCase?.let { occurrences(it, out) } }
            is TryCatchRegion -> {
                occurrences(container.tryRegion, out)
                container.catches.forEach { occurrences(it.body, out) }
                container.finallyRegion?.let { occurrences(it, out) }
            }
            else -> {}
        }
    }

    private fun findTryCatch(container: IrContainer?): TryCatchRegion? {
        when (container) {
            is TryCatchRegion -> return container
            is SequenceRegion -> container.children.forEach { findTryCatch(it)?.let { r -> return r } }
            is IfRegion -> {
                findTryCatch(container.thenRegion)?.let { return it }
                container.elseRegion?.let { findTryCatch(it)?.let { r -> return r } }
            }
            is LoopRegion -> return findTryCatch(container.body)
            is SyncRegion -> return findTryCatch(container.body)
            else -> {}
        }
        return null
    }

    /**
     * Two split ranges, a branchy (diamond) catch-all that re-throws, an inlined `[diamond; return]` before
     * every normal exit. Must fully structure into ONE try body + a faithful `catch (Throwable)`, NOT a
     * `finally`. r0 = caught exception (local); r1 = which-range branch; r2 = overflow guard; r3 = throwable.
     */
    @Test
    fun splitRangeBranchyCatchAllStructuresAsFaithfulCatch() {
        val ch = FakeCatchHandler(emptyList(), emptyList(), 11) // catch-all, handler entry at off 11
        val reader = FakeCodeReader(
            4,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, methodRef = sink), // range1 body (protected) — HEAD
                Insn(Opcode.IF_EQZ, 1, intArrayOf(1), target = 6), // -> range2 (off6) / else exit-diamond1
                // exit-diamond 1 (unprotected inlined cleanup copy)
                Insn(Opcode.IF_EQZ, 2, intArrayOf(2), target = 4), // if !overflow -> cleanup / else throw
                Insn(Opcode.THROW, 3, intArrayOf(3)), // overflow -> throw
                Insn(Opcode.INVOKE_STATIC, 4, methodRef = cleanup), // cleanup copy
                Insn(Opcode.RETURN_VOID, 5), // return
                Insn(Opcode.INVOKE_STATIC, 6, methodRef = sink), // range2 body (protected)
                // exit-diamond 2 (unprotected inlined cleanup copy)
                Insn(Opcode.IF_EQZ, 7, intArrayOf(2), target = 9), // if !overflow -> cleanup / else throw
                Insn(Opcode.THROW, 8, intArrayOf(3)), // overflow -> throw
                Insn(Opcode.INVOKE_STATIC, 9, methodRef = cleanup), // cleanup copy
                Insn(Opcode.RETURN_VOID, 10), // return
                // catch-all handler: diamond `if (overflow) throw RTE; else cleanup; throw e`
                Insn(Opcode.MOVE_EXCEPTION, 11, intArrayOf(0)),
                Insn(Opcode.IF_EQZ, 12, intArrayOf(2), target = 14), // if !overflow -> cleanup / else throw
                Insn(Opcode.THROW, 13, intArrayOf(3)), // overflow -> throw RTE
                Insn(Opcode.INVOKE_STATIC, 14, methodRef = cleanup), // cleanup
                Insn(Opcode.THROW, 15, intArrayOf(0)), // rethrow caught exception
            ),
            tries = listOf(
                // Each range protects ONLY the read (off0 / off6); the range boundary forces the compare-
                // branch that follows into its own UNPROTECTED block — the non-throwing between-code that
                // splits the ranges, exactly as javac emits for TestIfCodeStyle.
                FakeTryBlock(0, 0, ch), // range 1: off0 only
                FakeTryBlock(6, 6, ch), // range 2: off6 only
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            returnType = IrType.VOID,
            argTypes = listOf(IrType.INT, IrType.INT, IrType.THROWABLE),
        )
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "split-range branchy catch-all must fully structure")
        assertFalse(method.contains(AttrFlag.HAS_ERROR), "correct structuring ⇒ no error flag")

        val tc = findTryCatch(method.region)
        assertTrue(tc != null, "must produce a TryCatchRegion")
        assertNull(tc.finallyRegion, "jadx transcribes the branchy cleanup as a catch, NOT a finally")
        assertEquals(1, tc.catches.size, "exactly one catch clause")
        val catch = tc.catches.single()
        assertEquals(listOf(IrType.THROWABLE), catch.exceptionTypes, "the catch-all renders as catch (Throwable)")
        assertTrue(catch.exceptionVar != null, "the move-exception binds the catch parameter")

        // Every block placed exactly once (no cleanup copy dropped or double-run) and both inlined
        // `return`s survive — the run-exactly-once guarantee.
        val occ = ArrayList<BasicBlock>().also { occurrences(method.region, it) }
        assertEquals(occ.size, occ.toSet().size, "no block is placed twice")
        val returns = method.blocks.filter { b -> b.instructions.any { it.opcode == IrOpcode.RETURN } }
        assertTrue(returns.size >= 2, "both inlined cleanup returns exist in the CFG")
        assertTrue(returns.all { it in occ }, "every inlined-cleanup return survives in the region tree")
    }

    /**
     * Rule-4 hardening guard: the same split-range rethrow-try, but the non-throwing between-code between
     * the two ranges now holds a **throwing** statement (an `invoke`) that is OUTSIDE every dex catch-all
     * range. Pulling that block lexically into `try {}` would newly route its exception through the
     * `catch (Throwable)` when the bytecode would propagate it directly — a silent behaviour change on a
     * non-javac input. The gate must REFUSE to reconstruct (region==null, honest bail), NOT pull it in.
     *
     * Load-bearing: with the between-range non-throwing assertion removed, this block is pulled into the
     * try body and the method structures (region != null) — so this test fails, catching the regression.
     * (The terminal inlined-cleanup tails `[cleanup; return]` stay allowed — they are the finally copies
     * jadx renders inside `try {}`; only NON-terminal throwing between-code bails.)
     */
    @Test
    fun throwingBetweenRangeCodeBailsInsteadOfPullingIntoTry() {
        val ch = FakeCatchHandler(emptyList(), emptyList(), 12) // catch-all, handler entry at off 12
        val reader = FakeCodeReader(
            4,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, methodRef = sink), // range1 body (protected [0,0]) — HEAD
                // between-code (UNPROTECTED, non-terminal — flows onward to range2/exit-diamond1) that THROWS:
                Insn(Opcode.INVOKE_STATIC, 1, methodRef = sink), // <-- the hazard: throwing, outside any range
                Insn(Opcode.IF_EQZ, 2, intArrayOf(1), target = 7), // -> range2 (off7) / else exit-diamond1
                // exit-diamond 1 (unprotected inlined cleanup copy)
                Insn(Opcode.IF_EQZ, 3, intArrayOf(2), target = 5),
                Insn(Opcode.THROW, 4, intArrayOf(3)),
                Insn(Opcode.INVOKE_STATIC, 5, methodRef = cleanup),
                Insn(Opcode.RETURN_VOID, 6),
                Insn(Opcode.INVOKE_STATIC, 7, methodRef = sink), // range2 body (protected [7,7])
                Insn(Opcode.IF_EQZ, 8, intArrayOf(2), target = 10),
                Insn(Opcode.THROW, 9, intArrayOf(3)),
                Insn(Opcode.INVOKE_STATIC, 10, methodRef = cleanup),
                Insn(Opcode.RETURN_VOID, 11),
                Insn(Opcode.MOVE_EXCEPTION, 12, intArrayOf(0)),
                Insn(Opcode.IF_EQZ, 13, intArrayOf(2), target = 15),
                Insn(Opcode.THROW, 14, intArrayOf(3)),
                Insn(Opcode.INVOKE_STATIC, 15, methodRef = cleanup),
                Insn(Opcode.THROW, 16, intArrayOf(0)),
            ),
            tries = listOf(
                FakeTryBlock(0, 0, ch), // range 1: off0 only
                FakeTryBlock(7, 7, ch), // range 2: off7 only
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            returnType = IrType.VOID,
            argTypes = listOf(IrType.INT, IrType.INT, IrType.THROWABLE),
        )
        TestPipeline.structured(method)

        assertNull(method.region, "throwing between-range code must force an honest bail, not be pulled into try{}")
        assertTrue(
            method[PipelineAttrs.FULLY_STRUCTURED] != true,
            "a bailed method must NOT be flagged FULLY_STRUCTURED",
        )
    }
}
