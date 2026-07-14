package com.jadxmp.pipeline.structure

import com.jadxmp.input.Opcode
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.support.FakeCatchHandler
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.FakeTryBlock
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
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

    // ---- TestFinally3 essence: TWO try ranges sharing ONE catchall (finally) ----
    // Bail: "try body with multiple normal exits not supported yet".
    // javac split one try/finally into two try ranges (try1: A,B ; try2: E,F) both protected by the same
    // catch-all cleanup handler, with unprotected code (C,D) between them and F reached by a jump from A.
    // collectProtectedRegion from A gathers {A,B,F} (E is only reachable through unprotected C), leaving two
    // external exits {C, G}. Correct reconstruction is a single try/finally across the split ranges.
    @Test
    fun finally3_splitTryRangesSharedCatchallBails() {
        val reader = FakeCodeReader(
            3, // v0, v1, v2(exc)
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0), // entry (unprotected)
                Insn(Opcode.IF_NEZ, 1, intArrayOf(1), target = 6), // A (try1): if v1 -> F(off6) else fall
                Insn(Opcode.INVOKE_STATIC, 2, methodRef = sink), // B (try1 end)
                Insn(Opcode.IF_NEZ, 3, intArrayOf(1), target = 5), // C (unprotected): -> E(off5) else fall
                Insn(Opcode.RETURN, 4, intArrayOf(0)), // D (unprotected)
                Insn(Opcode.INVOKE_STATIC, 5, methodRef = sink), // E (try2): falls to F
                Insn(Opcode.INVOKE_STATIC, 6, methodRef = sink), // F (try2 end): shared jump target
                Insn(Opcode.RETURN, 7, intArrayOf(0)), // G (unprotected)
                Insn(Opcode.MOVE_EXCEPTION, 8, intArrayOf(2)), // H: catchall for BOTH tries (finally)
                Insn(Opcode.INVOKE_STATIC, 9, methodRef = cleanup),
                Insn(Opcode.THROW, 10, intArrayOf(2)),
            ),
            tries = listOf(
                FakeTryBlock(1, 2, FakeCatchHandler(emptyList(), emptyList(), 8)), // try1: A,B
                FakeTryBlock(5, 6, FakeCatchHandler(emptyList(), emptyList(), 8)), // try2: E,F
            ),
        )
        assertHonestBail(reader, IrType.OBJECT, listOf(IrType.OBJECT, IrType.INT))
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
