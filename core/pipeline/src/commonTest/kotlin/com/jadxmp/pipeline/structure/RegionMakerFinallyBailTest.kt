package com.jadxmp.pipeline.structure

import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.IrOpcode
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Diagnosis / honest-bail regression guards for the four `try/finally` corpus samples that currently
 * emit `// JADXMP ERROR: unstructured control flow` (region==null from a [RegionMaker] `makeTry` bail):
 * `TestFinally3`, `TestTypeResolver17`, `TestNestedTryCatch4`, `TestUnreachableCatch`.
 *
 * Each test builds the ESSENTIAL CFG sub-shape that triggers the exact bail (the corpus methods are far
 * larger; the sub-shape is the minimal graph that reproduces the same `Bail` reason). These are all
 * `finally`-family shapes — cleanup on every path, split try-ranges, or a nested try inside a handler —
 * that need real `finally`-reconstruction (drop/double-run hazard, rule 4) rather than a bounded
 * follow/handler placement fix, so the correct behaviour today is the honest bail (no wrong output).
 *
 * These assertions guard against a *regression to mis-structuring* (silently emitting a wrong tree is far
 * worse than bailing). When `finally`-reconstruction across these shapes lands, flip the relevant
 * assertion to expect `FULLY_STRUCTURED`.
 */
class RegionMakerFinallyBailTest {

    private val sink = FakeMethodRef("Lc/F;", "sink", "V", emptyList())
    private val cleanup = FakeMethodRef("Lc/F;", "cleanup", "V", emptyList())

    /** Rule-4 honesty: a method we cannot prove-structure must leave `region==null`, never a partial/wrong tree. */
    private fun assertHonestBail(reader: FakeCodeReader, returnType: IrType, argTypes: List<IrType>) {
        val m = TestPipeline.buildMethod(reader, returnType = returnType, argTypes = argTypes)
        TestPipeline.structured(m)
        assertNull(m.region, "unprovable try/finally shape must bail to null-region (honest), not a wrong tree")
        assertTrue(
            m[PipelineAttrs.FULLY_STRUCTURED] != true,
            "a bailed method must NOT be flagged FULLY_STRUCTURED",
        )
    }

    // ---- TestTypeResolver17 essence: try/catch/finally with TWO normal exits ----
    // Bail: "try body with multiple normal exits not supported yet".
    // The finally cleanup (closeQuietly) is inlined on BOTH the fall-through return and the cond_0 return,
    // so the protected body has two distinct external exits (each an unprotected `cleanup(); return`). A
    // TryCatchRegion has a single follow; merging the two needs finally-factoring, not a placement fix.
    @Test
    fun tr17_twoExitTryCatchFinallyBails() {
        val reader = FakeCodeReader(
            4,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, methodRef = sink), // T0 try body (protected)
                Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4), // T0 end: -> cond0(off4) else fall
                Insn(Opcode.INVOKE_STATIC, 2, methodRef = cleanup), // exit1 (unprotected): finally copy
                Insn(Opcode.RETURN, 3, intArrayOf(1)),
                Insn(Opcode.INVOKE_STATIC, 4, methodRef = cleanup), // exit2 (unprotected): finally copy
                Insn(Opcode.RETURN, 5, intArrayOf(2)),
                Insn(Opcode.MOVE_EXCEPTION, 6, intArrayOf(3)), // catch (Exception)
                Insn(Opcode.INVOKE_STATIC, 7, methodRef = cleanup),
                Insn(Opcode.RETURN, 8, intArrayOf(2)),
                Insn(Opcode.MOVE_EXCEPTION, 9, intArrayOf(3)), // catchall (finally)
                Insn(Opcode.INVOKE_STATIC, 10, methodRef = cleanup),
                Insn(Opcode.THROW, 11, intArrayOf(3)),
            ),
            tries = listOf(
                FakeTryBlock(0, 1, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(6), 9)),
            ),
        )
        assertHonestBail(reader, IrType.OBJECT, listOf(IrType.INT, IrType.OBJECT, IrType.OBJECT, IrType.OBJECT))
    }

    // ---- TestFinally3 essence: TWO try ranges sharing ONE catchall (finally) — NOW STRUCTURES ----
    // FLIPPED (was `finally3_splitTryRangesSharedCatchallBails`, an honest-bail guard): the split-range
    // finally now reconstructs. javac split one `try { A; between; E } finally { cleanup(); }` into two
    // try-ranges (try1: A ; try2: E) sharing the SAME catch-all cleanup, joined by non-throwing unprotected
    // between-code C, with a cleanup copy inlined before BOTH normal returns (D and G). The structurer grows
    // the whole finally body {A, C, E} across the split ranges and factors ONE `try { … } finally { cleanup
    // }`: the two inlined copies + the handler copy collapse to a SINGLE cleanup in the finally, both returns
    // stay inside the try, the synthetic re-throw is hidden. (Rule 4: cleanup emitted exactly once per path.)
    @Test
    fun finally3_splitTryRangesSharedCatchallNowStructures() {
        val reader = FakeCodeReader(
            2, // v0 = cond (param), v1 = exception var
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, methodRef = sink), // A (try1): protected throwing work
                Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4), // C (unprotected between): -> E(off4) else D
                Insn(Opcode.INVOKE_STATIC, 2, methodRef = cleanup), // D exit: inlined cleanup copy
                Insn(Opcode.RETURN_VOID, 3), // D exit: return
                Insn(Opcode.INVOKE_STATIC, 4, methodRef = sink), // E (try2): protected throwing work → falls to G
                Insn(Opcode.INVOKE_STATIC, 5, methodRef = cleanup), // G exit: inlined cleanup copy
                Insn(Opcode.RETURN_VOID, 6), // G exit: return
                Insn(Opcode.MOVE_EXCEPTION, 7, intArrayOf(1)), // H: catchall for BOTH tries (finally)
                Insn(Opcode.INVOKE_STATIC, 8, methodRef = cleanup), // H cleanup copy
                Insn(Opcode.THROW, 9, intArrayOf(1)),
            ),
            tries = listOf(
                FakeTryBlock(0, 0, FakeCatchHandler(emptyList(), emptyList(), 7)), // try1: A
                FakeTryBlock(4, 4, FakeCatchHandler(emptyList(), emptyList(), 7)), // try2: E
            ),
        )
        val m = TestPipeline.buildMethod(reader, returnType = IrType.VOID, argTypes = listOf(IrType.INT))
        TestPipeline.structured(m)

        assertNotNull(m.region, "the split-range try/finally must now structure (region != null)")
        assertEquals(true, m[PipelineAttrs.FULLY_STRUCTURED], "structured across the split ranges")
        // Exactly ONE cleanup INVOKE survives (the two inlined copies collapse into the finally's single copy).
        val liveCleanups = m.blocks.sumOf { b ->
            b.instructions.count { insn ->
                insn is InvokeInstruction && insn.methodRef.name == "cleanup" && !insn.contains(AttrFlag.DONT_GENERATE)
            }
        }
        assertEquals(1, liveCleanups, "cleanup emitted exactly once (in the finally), never dropped or doubled")
        // Both normal returns survive (nothing dropped), and the synthetic re-throw is hidden by the finally.
        assertEquals(2, m.blocks.flatMap { it.instructions }.count { it.opcode == IrOpcode.RETURN })
        val throwHidden = m.blocks.flatMap { it.instructions }
            .single { it.opcode == IrOpcode.THROW }.contains(AttrFlag.DONT_GENERATE)
        assertTrue(throwHidden, "the synthetic re-throw is hidden (folded into the finally)")
    }

    // ---- Negative: a split-range exit that LACKS the inlined cleanup must still bail ----
    // Same split-range shape, but the D exit is a BARE `return` with no cleanup copy. Growing the body reaches
    // the method exit via D without a cleanup, which is a drop hazard — so reconstruction must decline and the
    // method bails honestly rather than factor a finally that adds cleanup to a path that never ran it.
    @Test
    fun finally3_splitRangeWithUncoveredExitBails() {
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, methodRef = sink), // A (try1)
                Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 3), // C (between): -> E(off3) else D
                Insn(Opcode.RETURN_VOID, 2), // D exit: BARE return (NO cleanup) — uncovered
                Insn(Opcode.INVOKE_STATIC, 3, methodRef = sink), // E (try2) → falls to G
                Insn(Opcode.INVOKE_STATIC, 4, methodRef = cleanup), // G exit: cleanup copy
                Insn(Opcode.RETURN_VOID, 5),
                Insn(Opcode.MOVE_EXCEPTION, 6, intArrayOf(1)), // H catchall
                Insn(Opcode.INVOKE_STATIC, 7, methodRef = cleanup),
                Insn(Opcode.THROW, 8, intArrayOf(1)),
            ),
            tries = listOf(
                FakeTryBlock(0, 0, FakeCatchHandler(emptyList(), emptyList(), 6)),
                FakeTryBlock(3, 3, FakeCatchHandler(emptyList(), emptyList(), 6)),
            ),
        )
        assertHonestBail(reader, IrType.VOID, listOf(IrType.INT))
    }

    // ---- TestNestedTryCatch4 / TestUnreachableCatch essence: a nested try INSIDE a handler ----
    // Bail: "nested try in handler not supported yet".
    // Both corpus methods desugar resource-closing / finally into catch handlers whose own bodies are
    // protected by a further try (try-with-resources `addSuppressed` chains, nested try/catch/finally).
    // finishTry bails on `isProtected(h)` for such a handler. Structuring it needs to open a nested try
    // from the move-exception catch entry — a cross-cutting change, not a bounded placement fix.
    @Test
    fun nestedTryInsideHandlerBails() {
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, methodRef = sink), // try1 body (protected by H1)
                Insn(Opcode.RETURN_VOID, 1), // follow
                Insn(Opcode.MOVE_EXCEPTION, 2, intArrayOf(0)), // H1 catch entry (itself protected by H2)
                Insn(Opcode.INVOKE_STATIC, 3, methodRef = cleanup), // H1 body (try2)
                Insn(Opcode.GOTO, 4, target = 1), // -> follow
                Insn(Opcode.MOVE_EXCEPTION, 5, intArrayOf(1)), // H2 (nested handler)
                Insn(Opcode.RETURN_VOID, 6),
            ),
            tries = listOf(
                FakeTryBlock(0, 0, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(2), -1)), // try1 -> H1
                FakeTryBlock(2, 3, FakeCatchHandler(listOf("Ljava/lang/RuntimeException;"), listOf(5), -1)), // try2 in handler
            ),
        )
        assertHonestBail(reader, IrType.VOID, emptyList())
    }
}
