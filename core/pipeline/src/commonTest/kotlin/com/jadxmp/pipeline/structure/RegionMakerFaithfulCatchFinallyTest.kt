package com.jadxmp.pipeline.structure

import com.jadxmp.input.Opcode
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic guards for the **two-handler faithful catch + finally-as-catch** reconstruction
 * ([RegionMaker.reconstructFaithfulCatchFinally]) — the shape `trycatch/TestNestedTryCatch4` exercises: a body
 * protected by BOTH an explicit `catch (E)` AND a re-throwing catch-all whose cleanup is branchy/nested, with
 * the catch-all also protecting the explicit catch's body. jadx renders the catch-all as a `finally` and
 * DOUBLE-closes on the normal path; jadxmp transcribes it 1:1 as `catch (Throwable)` NESTING OUTSIDE the typed
 * catch (Phase A's handler-granular gate), placing every block EXACTLY ONCE so each path runs its cleanup once.
 *
 * Nothing is hidden or de-duplicated (only the two `move-exception` binders become catch params); the
 * block/edge coverage nets are the run-exactly-once proof. These tests pin: the two-handler nesting, a nested
 * try inside the typed-catch body structured recursively, the route-(a) terminal-tail widening, and the honest
 * bails.
 */
class RegionMakerFaithfulCatchFinallyTest {

    private val sink = FakeMethodRef("Lc/F;", "sink", "V", emptyList())
    private val handleE = FakeMethodRef("Lc/F;", "handleE", "V", emptyList())
    private val cleanup = FakeMethodRef("Lc/F;", "cleanup", "V", emptyList())
    private val risky = FakeMethodRef("Lc/F;", "risky", "V", emptyList())

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

    /** Depth-first: the FIRST TryCatchRegion found (outermost on the primary path). */
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

    private fun assertEachBlockPlacedOnce(method: com.jadxmp.ir.node.IrMethod) {
        val occ = ArrayList<BasicBlock>().also { occurrences(method.region, it) }
        assertEquals(occ.size, occ.toSet().size, "no block is placed twice (run-exactly-once: no cleanup doubled)")
        for (b in method.blocks) {
            if (b === method.entryBlock || b === method.exitBlock) continue
            assertTrue(b in occ, "every block is placed (no cleanup / catch dropped): B${b.id} missing")
        }
    }

    // (a) The core two-handler shape: body protected by {catch E, branchy re-throwing catch-all}; the catch-all
    // ALSO protects the typed-catch body, so it must nest OUTSIDE the typed catch. Must structure as
    // `try { try { body } catch (E) { … } } catch (Throwable) { cleanup; throw }` — NOT a finally, NOT siblings.
    @Test
    fun twoHandlerBranchyCatchAllNestsOutsideTypedCatch() {
        val reader = FakeCodeReader(
            4,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, methodRef = sink), // B: body (protected by E@2 AND catch-all@5) — HEAD
                Insn(Opcode.RETURN_VOID, 1), // follow (shared normal exit)
                Insn(Opcode.MOVE_EXCEPTION, 2, intArrayOf(0)), // catch (E) entry
                Insn(Opcode.INVOKE_STATIC, 3, methodRef = handleE), // catch body — protected by the catch-all (the nesting)
                Insn(Opcode.GOTO, 4, target = 1), // -> follow
                // catch-all: BRANCHY re-throwing cleanup `if (guard) throw RTE; else cleanup; throw e` (finallyChain==null)
                Insn(Opcode.MOVE_EXCEPTION, 5, intArrayOf(0)),
                Insn(Opcode.IF_EQZ, 6, intArrayOf(2), target = 8), // if !overflow -> cleanup / else throw RTE
                Insn(Opcode.THROW, 7, intArrayOf(3)),
                Insn(Opcode.INVOKE_STATIC, 8, methodRef = cleanup),
                Insn(Opcode.THROW, 9, intArrayOf(0)), // rethrow the caught throwable
            ),
            tries = listOf(
                // body [0,0]: explicit catch(IllegalStateException)@2 tried BEFORE the catch-all@5.
                FakeTryBlock(0, 0, FakeCatchHandler(listOf("Ljava/lang/IllegalStateException;"), listOf(2), 5)),
                // the typed-catch body [3,3] is protected by the catch-all — the finally covers it.
                FakeTryBlock(3, 3, FakeCatchHandler(emptyList(), emptyList(), 5)),
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            returnType = IrType.VOID,
            argTypes = listOf(IrType.OBJECT, IrType.INT, IrType.INT, IrType.THROWABLE),
        )
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "two-handler faithful catch/finally must structure")

        val outer = findTryCatch(method.region)
        assertTrue(outer != null, "must produce a TryCatchRegion")
        assertNull(outer.finallyRegion, "the catch-all is transcribed as a catch, NOT a finally (no double-close)")
        assertEquals(1, outer.catches.size, "outer has exactly one catch clause")
        assertEquals(listOf(IrType.THROWABLE), outer.catches.single().exceptionTypes, "outer catch is catch (Throwable)")

        // The typed catch nests INSIDE the catch-all's try region.
        val inner = findTryCatch(outer.tryRegion)
        assertTrue(inner != null, "the typed catch nests inside the catch-all try")
        assertNull(inner.finallyRegion, "inner is a plain typed catch")
        assertEquals(1, inner.catches.size, "inner has one catch clause")
        assertTrue(
            inner.catches.single().exceptionTypes.none { it == IrType.THROWABLE },
            "inner catch binds the explicit exception type, not Throwable",
        )
        assertTrue(inner.catches.single().exceptionVar != null, "the typed catch binds its parameter")

        assertEachBlockPlacedOnce(method)
    }

    // (b) The typed-catch body itself contains a nested `try { risky } catch (RuntimeException) {}` (both under
    // the catch-all). It must be structured RECURSIVELY (item 2) — a nested TryCatchRegion inside the catch (E).
    @Test
    fun nestedTryInsideTypedCatchBodyStructuresRecursively() {
        val reader = FakeCodeReader(
            4,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, methodRef = sink), // B0 body [E@2, ca@7] — HEAD
                Insn(Opcode.RETURN_VOID, 1), // follow
                Insn(Opcode.MOVE_EXCEPTION, 2, intArrayOf(0)), // catch (E) entry
                Insn(Opcode.INVOKE_STATIC, 3, methodRef = risky), // nested-try body [H2@5, ca@7]
                Insn(Opcode.GOTO, 4, target = 1), // nested-try normal exit -> follow
                Insn(Opcode.MOVE_EXCEPTION, 5, intArrayOf(0)), // nested catch (RuntimeException) entry (empty)
                Insn(Opcode.GOTO, 6, target = 1), // -> follow
                Insn(Opcode.MOVE_EXCEPTION, 7, intArrayOf(0)), // catch-all branchy rethrow
                Insn(Opcode.IF_EQZ, 8, intArrayOf(2), target = 10),
                Insn(Opcode.THROW, 9, intArrayOf(3)),
                Insn(Opcode.INVOKE_STATIC, 10, methodRef = cleanup),
                Insn(Opcode.THROW, 11, intArrayOf(0)),
            ),
            tries = listOf(
                FakeTryBlock(0, 0, FakeCatchHandler(listOf("Ljava/lang/IllegalStateException;"), listOf(2), 7)),
                FakeTryBlock(3, 3, FakeCatchHandler(listOf("Ljava/lang/RuntimeException;"), listOf(5), 7)),
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            returnType = IrType.VOID,
            argTypes = listOf(IrType.OBJECT, IrType.INT, IrType.INT, IrType.THROWABLE),
        )
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "nested try inside the typed-catch body must structure")
        val outer = findTryCatch(method.region)!!
        assertNull(outer.finallyRegion, "catch-all as catch, not finally")
        val inner = findTryCatch(outer.tryRegion)!! // the typed catch nests inside the catch-all try
        // The typed catch body must itself hold a nested TryCatchRegion (the recursively-structured inner try).
        val nested = inner.catches.single().body
        assertTrue(findTryCatch(nested) != null, "the typed-catch body's nested try/catch is structured recursively")
        assertEachBlockPlacedOnce(method)
    }

    // (c) A body block that is itself a `try { closeTail } catch (IOException) {}` and is NOT under the catch-all
    // (only its own IOException catch) — the route-(a) terminal-tail widening. It is placed ONCE, rendered
    // lexically inside the catch-all try (its escaping non-IOException would newly route through the catch-all —
    // the accepted, documented, jadx-identical widening). Structuring must succeed with each block placed once.
    @Test
    fun ownCatchTailWidenedIntoCatchAllTryPlacedOnce() {
        val reader = FakeCodeReader(
            4,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, methodRef = sink), // B0 body [E@5, ca@7] — HEAD
                Insn(Opcode.INVOKE_STATIC, 1, methodRef = cleanup), // B1 close-tail [H_close@3] — NOT under ca (widened)
                Insn(Opcode.RETURN_VOID, 2), // follow
                Insn(Opcode.MOVE_EXCEPTION, 3, intArrayOf(0)), // H_close handler (empty catch IOException)
                Insn(Opcode.GOTO, 4, target = 2), // -> follow
                Insn(Opcode.MOVE_EXCEPTION, 5, intArrayOf(0)), // catch (E) entry
                Insn(Opcode.GOTO, 6, target = 2), // catch body -> follow
                Insn(Opcode.MOVE_EXCEPTION, 7, intArrayOf(0)), // catch-all branchy rethrow
                Insn(Opcode.IF_EQZ, 8, intArrayOf(2), target = 10),
                Insn(Opcode.THROW, 9, intArrayOf(3)),
                Insn(Opcode.INVOKE_STATIC, 10, methodRef = cleanup),
                Insn(Opcode.THROW, 11, intArrayOf(0)),
            ),
            tries = listOf(
                FakeTryBlock(0, 0, FakeCatchHandler(listOf("Ljava/lang/IllegalStateException;"), listOf(5), 7)),
                // the close-tail is protected by ONLY its own IOException catch — NOT the catch-all.
                FakeTryBlock(1, 1, FakeCatchHandler(listOf("Ljava/io/IOException;"), listOf(3), -1)),
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            returnType = IrType.VOID,
            argTypes = listOf(IrType.OBJECT, IrType.INT, IrType.INT, IrType.THROWABLE),
        )
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "the route-(a) widened tail must structure")
        val outer = findTryCatch(method.region)!!
        assertNull(outer.finallyRegion, "catch-all as catch, not finally")
        // The close-tail's own try/catch is rendered lexically inside the catch-all's try region (widened).
        val innerTries = ArrayList<TryCatchRegion>()
        collectTryCatches(outer.tryRegion, innerTries)
        assertTrue(innerTries.size >= 2, "the widened close-tail try nests inside the catch-all try")
        assertEachBlockPlacedOnce(method)
    }

    // Bail: the catch-all does NOT re-throw (a real `catch (Throwable) { … return }`). It must NOT be rendered as
    // this cleanup-rethrow faithful shape — [reconstructFaithfulCatchFinally] declines (handlerReThrows == false)
    // and the ordinary two-catch path renders it, so there is NO `throw` synthesised into the catch-all body.
    @Test
    fun nonRethrowingCatchAllIsNotRenderedAsCleanupRethrow() {
        val reader = FakeCodeReader(
            4,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, methodRef = sink), // B0 body [E@2, ca@4]
                Insn(Opcode.RETURN_VOID, 1), // follow
                Insn(Opcode.MOVE_EXCEPTION, 2, intArrayOf(0)), // catch (E)
                Insn(Opcode.GOTO, 3, target = 1),
                Insn(Opcode.MOVE_EXCEPTION, 4, intArrayOf(0)), // catch-all that RETURNS (real catch (Throwable))
                Insn(Opcode.INVOKE_STATIC, 5, methodRef = cleanup),
                Insn(Opcode.RETURN_VOID, 6), // returns — does NOT re-throw
            ),
            tries = listOf(
                FakeTryBlock(0, 0, FakeCatchHandler(listOf("Ljava/lang/IllegalStateException;"), listOf(2), 4)),
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            returnType = IrType.VOID,
            argTypes = listOf(IrType.OBJECT, IrType.INT, IrType.INT, IrType.THROWABLE),
        )
        TestPipeline.structured(method)
        // If it structures at all, the catch-all body must contain NO throw (it is a real catch, not a rethrow).
        if (method[PipelineAttrs.FULLY_STRUCTURED] == true) {
            val tc = findTryCatch(method.region)!!
            val caBody = tc.catches.firstOrNull { it.exceptionTypes.contains(IrType.THROWABLE) }?.body
            if (caBody != null) {
                val occ = ArrayList<BasicBlock>().also { occurrences(caBody, it) }
                assertTrue(
                    occ.none { b -> b.instructions.any { it.opcode == IrOpcode.THROW } },
                    "a real catch (Throwable) must not be rendered with a synthesised re-throw",
                )
            }
        }
    }

    // Bail (route-(a) gate): an UNPROTECTED, throwing, NON-terminal block sits in the body between the head and
    // the follow. Pulling it lexically inside the catch-all `try {}` would NEWLY route its exception through the
    // catch-all (the bytecode propagates it directly) — a silent widening on a non-javac input.
    // [reconstructFaithfulCatchFinally] must REFUSE (region stays null; the catch body is catch-all-protected so
    // the ordinary path also bails on the nested-try-in-handler). Load-bearing: without the gate the block is
    // pulled in and the method mis-structures.
    @Test
    fun throwingBetweenCodeInBodyBailsInsteadOfWidening() {
        val reader = FakeCodeReader(
            4,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, methodRef = sink), // B0 body [E@3, ca@6] — HEAD
                Insn(Opcode.INVOKE_STATIC, 1, methodRef = risky), // B1 UNPROTECTED throwing non-terminal (hazard)
                Insn(Opcode.RETURN_VOID, 2), // follow
                Insn(Opcode.MOVE_EXCEPTION, 3, intArrayOf(0)), // catch (E)
                Insn(Opcode.INVOKE_STATIC, 4, methodRef = handleE), // catch body [ca@6] — CA-protected (nesting)
                Insn(Opcode.GOTO, 5, target = 2),
                Insn(Opcode.MOVE_EXCEPTION, 6, intArrayOf(0)), // catch-all branchy rethrow
                Insn(Opcode.IF_EQZ, 7, intArrayOf(2), target = 9),
                Insn(Opcode.THROW, 8, intArrayOf(3)),
                Insn(Opcode.INVOKE_STATIC, 9, methodRef = cleanup),
                Insn(Opcode.THROW, 10, intArrayOf(0)),
            ),
            tries = listOf(
                FakeTryBlock(0, 0, FakeCatchHandler(listOf("Ljava/lang/IllegalStateException;"), listOf(3), 6)),
                FakeTryBlock(4, 4, FakeCatchHandler(emptyList(), emptyList(), 6)),
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            returnType = IrType.VOID,
            argTypes = listOf(IrType.OBJECT, IrType.INT, IrType.INT, IrType.THROWABLE),
        )
        TestPipeline.structured(method)
        assertNull(method.region, "throwing between-code must force an honest bail, not be widened into the catch-all")
        assertTrue(method[PipelineAttrs.FULLY_STRUCTURED] != true, "a bailed method must NOT be FULLY_STRUCTURED")
    }

    // The LIVE corpus flip: `trycatch/TestTryCatchFinally15` (the `IInterface.transact` idiom). A faithful
    // synthetic of its exact shape — a body protected by BOTH `catch (RuntimeException)` AND a re-throwing
    // catch-all, where the RTE catch does DIFFERENT cleanup (`obtain.recycle()`) than the finally
    // (`data.recycle()`), and the NORMAL path ends in a terminal `data.recycle(); return`. Because the RTE
    // cleanup differs from the finally, [reconstructTryCatchFinally] legitimately declines (it can only factor an
    // identical inlined cleanup) and THIS faithful path takes it — rendering
    // `try { try { body } catch (RuntimeException) { obtain.recycle(); throw } data.recycle(); return }
    //  catch (Throwable) { data.recycle(); throw }`, matching jadx-1.5.6 (parity, an improvement over the prior
    // awkward double-`catch(Throwable)`).
    //
    // PIN (must-fail if [reconstructFaithfulCatchFinally] is removed/broken — without it this shape has NO
    // structurer: the dedup finally path declines on the differing cleanup AND on the nested-try catch entry, and
    // the ordinary finishTry bails on the nested try in the handler ⇒ region == null):
    //  - the RTE `catch` nests INSIDE `catch (Throwable)` (the finally covers the RTE catch body);
    //  - run-exactly-once: `data.recycle()` (the finally cleanup) is placed in EXACTLY TWO blocks — the normal
    //    terminal tail (runs once on the normal/RTE-rethrow paths) and the catch-all body (runs once on the
    //    throwable path) — never doubled; `obtain.recycle()` (recycle2) is placed in EXACTLY ONE block (the RTE
    //    catch, runs once on the RTE path). Every block is placed exactly once (no cleanup dropped/doubled).
    @Test
    fun tryCatchFinally15ShapeStructuresAsNestedCatchNotDoubleCatchAll() {
        val recycleData = FakeMethodRef("Lc/F;", "recycleData", "V", emptyList())
        val recycleObtain = FakeMethodRef("Lc/F;", "recycleObtain", "V", emptyList())
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, methodRef = sink), // B: body [catch RuntimeException@3, catch-all@6] — HEAD
                Insn(Opcode.INVOKE_STATIC, 1, methodRef = recycleData), // normal tail: the finally cleanup copy
                Insn(Opcode.RETURN_VOID, 2), // terminal return
                Insn(Opcode.MOVE_EXCEPTION, 3, intArrayOf(0)), // catch (RuntimeException) entry
                Insn(Opcode.INVOKE_STATIC, 4, methodRef = recycleObtain), // RTE cleanup — DIFFERENT from the finally
                Insn(Opcode.THROW, 5, intArrayOf(0)), // rethrow
                Insn(Opcode.MOVE_EXCEPTION, 6, intArrayOf(0)), // catch-all (finally) entry
                Insn(Opcode.INVOKE_STATIC, 7, methodRef = recycleData), // the finally cleanup
                Insn(Opcode.THROW, 8, intArrayOf(0)), // rethrow
            ),
            tries = listOf(
                FakeTryBlock(0, 0, FakeCatchHandler(listOf("Ljava/lang/RuntimeException;"), listOf(3), 6)),
                // the RTE catch's cleanup (recycleObtain; throw) is protected by the catch-all — the finally
                // covers the catch body, which is why the catch-all must nest OUTSIDE the RTE catch.
                FakeTryBlock(4, 5, FakeCatchHandler(emptyList(), emptyList(), 6)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.VOID, argTypes = emptyList())
        TestPipeline.structured(method)

        assertEquals(
            true, method[PipelineAttrs.FULLY_STRUCTURED],
            "the TestTryCatchFinally15 shape must structure (only reconstructFaithfulCatchFinally can — the pin)",
        )
        val outer = findTryCatch(method.region)!!
        assertNull(outer.finallyRegion, "the catch-all is a catch (Throwable), NOT a finally (no double-recycle)")
        assertEquals(listOf(IrType.THROWABLE), outer.catches.single().exceptionTypes, "outer catch is catch (Throwable)")
        val inner = findTryCatch(outer.tryRegion)!!
        assertTrue(
            inner.catches.single().exceptionTypes.none { it == IrType.THROWABLE },
            "the RuntimeException catch nests INSIDE catch (Throwable)",
        )

        // Run-exactly-once (place-each-block-once): the finally cleanup `recycleData` lives in EXACTLY TWO placed
        // blocks (the normal terminal tail + the catch-all body) and the RTE-only `recycleObtain` in EXACTLY ONE.
        val occ = ArrayList<BasicBlock>().also { occurrences(method.region, it) }
        assertEquals(occ.size, occ.toSet().size, "no block placed twice (no cleanup doubled)")
        val recycleDataBlocks = occ.count { b -> b.instructions.any { it.callTargetIs(recycleData) } }
        val recycleObtainBlocks = occ.count { b -> b.instructions.any { it.callTargetIs(recycleObtain) } }
        assertEquals(2, recycleDataBlocks, "the finally cleanup is placed once on the normal tail and once in the catch-all — never doubled")
        assertEquals(1, recycleObtainBlocks, "the RTE-specific cleanup is placed exactly once (RTE path only)")
        assertEachBlockPlacedOnce(method)
    }

    private fun com.jadxmp.ir.insn.Instruction.callTargetIs(ref: FakeMethodRef): Boolean {
        val invoke = this as? com.jadxmp.ir.insn.InvokeInstruction ?: return false
        return invoke.methodRef.name == ref.name
    }

    /** All TryCatchRegions reachable in [container] (pre-order). */
    private fun collectTryCatches(container: IrContainer?, out: MutableList<TryCatchRegion>) {
        when (container) {
            is TryCatchRegion -> {
                out.add(container)
                collectTryCatches(container.tryRegion, out)
                container.catches.forEach { collectTryCatches(it.body, out) }
                container.finallyRegion?.let { collectTryCatches(it, out) }
            }
            is SequenceRegion -> container.children.forEach { collectTryCatches(it, out) }
            is IfRegion -> { collectTryCatches(container.thenRegion, out); container.elseRegion?.let { collectTryCatches(it, out) } }
            is LoopRegion -> collectTryCatches(container.body, out)
            is SyncRegion -> collectTryCatches(container.body, out)
            else -> {}
        }
    }
}
