package com.jadxmp.pipeline.structure

import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.region.CatchClause
import com.jadxmp.ir.region.Region
import com.jadxmp.ir.region.SequenceRegion
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier-2 `try { … } catch (E e) { … } finally { cleanup }` reconstruction — jadx's factoring of javac's
 * inlined-cleanup shape when the protected body carries BOTH an explicit `catch` AND the synthetic
 * catch-all `finally`, and the explicit catch body is ITSELF protected by that catch-all (so the finally
 * covers it — the semantic core that a sibling `catch(Throwable)` would NOT reproduce).
 *
 * This is the `types/TestTypeResolver17` corpus shape: a two-normal-exit try body, an explicit
 * `catch(Exception)` whose body is catchall-protected, and a MULTI-BLOCK catchall cleanup
 * (`[move-exc; goto] -> [cleanup; throw]`). The positive test proves the factoring (cleanup emitted
 * exactly once in the finally, every normal return preserved, catch present + covered). The bail tests
 * pin rule-4 correctness: any deviation (non-identical cleanup, branching catchall cleanup, catch body
 * NOT catchall-protected) must leave `region==null` (honest), never a wrong tree.
 */
class RegionMakerTryCatchFinallyTest {

    private val sink = FakeMethodRef("Lc/F;", "sink", "V", emptyList())
    private val use = FakeMethodRef("Lc/F;", "use", "V", listOf("Ljava/lang/Object;"))
    private val cleanup = FakeMethodRef("Lc/F;", "cleanup", "V", emptyList())
    private val other = FakeMethodRef("Lc/F;", "other", "V", emptyList())

    /** v0=cond(int), v1=retA(obj), v2=retB(obj), v3=exception var. */
    private val argTypes = listOf(IrType.INT, IrType.OBJECT, IrType.OBJECT)

    /**
     * The full TestTypeResolver17 essence.
     *  - body [0..1]: work then `if (v0) goto exit2 else exit1` — two inlined-cleanup returns.
     *  - catch(Exception) entry off6 (move-exc), body off7 (protected by catchall) uses `e`, exit off8.
     *  - catchall (finally): MULTI-BLOCK `[move-exc; goto] -> [cleanup; throw]`.
     */
    private fun tr17Reader(
        exit1Cleanup: FakeMethodRef = cleanup,
        catchBodyProtected: Boolean = true,
        branchingCatchall: Boolean = false,
    ): FakeCodeReader {
        val insns = ArrayList<Insn>()
        insns.add(Insn(Opcode.INVOKE_STATIC, 0, methodRef = sink)) // body work (protected)
        insns.add(Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4)) // body end: -> exit2(off4) else exit1(off2)
        insns.add(Insn(Opcode.INVOKE_STATIC, 2, methodRef = exit1Cleanup)) // exit1: inlined cleanup copy
        insns.add(Insn(Opcode.RETURN, 3, intArrayOf(1)))
        insns.add(Insn(Opcode.INVOKE_STATIC, 4, methodRef = cleanup)) // exit2: inlined cleanup copy
        insns.add(Insn(Opcode.RETURN, 5, intArrayOf(2)))
        insns.add(Insn(Opcode.MOVE_EXCEPTION, 6, intArrayOf(3))) // catch(Exception) entry (unprotected)
        insns.add(Insn(Opcode.INVOKE_STATIC, 7, intArrayOf(3), methodRef = use)) // catch body (catchall-protected)
        insns.add(Insn(Opcode.INVOKE_STATIC, 8, methodRef = cleanup)) // catch exit: inlined cleanup copy
        insns.add(Insn(Opcode.RETURN, 9, intArrayOf(2)))
        if (branchingCatchall) {
            // catchall cleanup BRANCHES (not straight-line): move-exc; if(v0) -> throw2 else throw1.
            insns.add(Insn(Opcode.MOVE_EXCEPTION, 10, intArrayOf(3)))
            insns.add(Insn(Opcode.IF_EQZ, 11, intArrayOf(0), target = 14))
            insns.add(Insn(Opcode.INVOKE_STATIC, 12, methodRef = cleanup))
            insns.add(Insn(Opcode.THROW, 13, intArrayOf(3)))
            insns.add(Insn(Opcode.INVOKE_STATIC, 14, methodRef = cleanup))
            insns.add(Insn(Opcode.THROW, 15, intArrayOf(3)))
        } else {
            // catchall (finally): MULTI-BLOCK `[move-exc; goto] -> [cleanup; throw]`.
            insns.add(Insn(Opcode.MOVE_EXCEPTION, 10, intArrayOf(3)))
            insns.add(Insn(Opcode.GOTO, 11, target = 12))
            insns.add(Insn(Opcode.INVOKE_STATIC, 12, methodRef = cleanup))
            insns.add(Insn(Opcode.THROW, 13, intArrayOf(3)))
        }
        val tries = ArrayList<FakeTryBlock>()
        // body [0..1]: Exception -> off6, catchall -> off10.
        tries.add(FakeTryBlock(0, 1, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(6), 10)))
        // catch body [7..7]: catchall -> off10 (the finally covers the catch body).
        if (catchBodyProtected) {
            tries.add(FakeTryBlock(7, 7, FakeCatchHandler(emptyList(), emptyList(), 10)))
        }
        return FakeCodeReader(4, insns, tries)
    }

    private fun liveCleanups(m: com.jadxmp.ir.node.IrMethod): Int =
        m.blocks.sumOf { b ->
            b.instructions.count { insn ->
                insn is InvokeInstruction && insn.methodRef.name == "cleanup" &&
                    !insn.contains(AttrFlag.DONT_GENERATE)
            }
        }

    private fun findTryCatch(region: Region?): TryCatchRegion? {
        if (region is TryCatchRegion) return region
        if (region is SequenceRegion) {
            for (c in region.children) {
                if (c is TryCatchRegion) return c
                if (c is Region) findTryCatch(c)?.let { return it }
            }
        }
        return null
    }

    // ---- POSITIVE: the try/catch/finally factors, cleanup runs exactly once per path ----
    @Test
    fun tr17_tryCatchFinallyStructures() {
        val m = TestPipeline.buildMethod(tr17Reader(), returnType = IrType.OBJECT, argTypes = argTypes)
        TestPipeline.structured(m)

        assertNotNull(m.region, "try/catch/finally must structure (region != null)")
        assertEquals(true, m[PipelineAttrs.FULLY_STRUCTURED])

        // Cleanup emitted EXACTLY once (in the finally): the 3 inlined copies + the handler copy collapse.
        assertEquals(1, liveCleanups(m), "cleanup runs exactly once per path (finally), never dropped or doubled")
        // All three normal returns survive (fall-through, cond exit, catch exit) — nothing dropped.
        assertEquals(3, m.blocks.flatMap { it.instructions }.count { it.opcode == IrOpcode.RETURN })
        // The synthetic re-throw is hidden (folded into the finally).
        val throwHidden = m.blocks.flatMap { it.instructions }
            .single { it.opcode == IrOpcode.THROW }.contains(AttrFlag.DONT_GENERATE)
        assertTrue(throwHidden, "the synthetic re-throw is hidden (folded into the finally)")

        // The region is a try/catch/finally: one explicit catch + a non-empty finally.
        val tc = assertNotNull(findTryCatch(m.region), "a TryCatchRegion must be present")
        assertEquals(1, tc.catches.size, "exactly one explicit catch(Exception)")
        assertNotNull(tc.finallyRegion, "a finally region must be present")
        val catch: CatchClause = tc.catches.single()
        assertTrue(
            catch.exceptionTypes.isNotEmpty(),
            "the explicit catch keeps its named type (Exception), not a collapsed catch-all",
        )
        assertNotNull(catch.exceptionVar, "the caught exception binds a catch parameter")
    }

    // ---- BAIL (a): a cleanup copy that is NOT instruction-identical across an exit ----
    @Test
    fun tr17_nonIdenticalCleanupBails() {
        val m = TestPipeline.buildMethod(
            tr17Reader(exit1Cleanup = other), returnType = IrType.OBJECT, argTypes = argTypes,
        )
        TestPipeline.structured(m)
        assertNull(m.region, "an exit whose cleanup differs must bail honestly, never mis-factor a finally")
        assertTrue(m[PipelineAttrs.FULLY_STRUCTURED] != true)
    }

    // ---- BAIL (b): a catchall cleanup that BRANCHES (not straight-line) ----
    @Test
    fun tr17_branchingCatchallCleanupBails() {
        val m = TestPipeline.buildMethod(
            tr17Reader(branchingCatchall = true), returnType = IrType.OBJECT, argTypes = argTypes,
        )
        TestPipeline.structured(m)
        assertNull(m.region, "a branching (non-straight-line) catchall cleanup must bail honestly")
        assertTrue(m[PipelineAttrs.FULLY_STRUCTURED] != true)
    }

    // ---- BAIL (c): the explicit catch body is NOT catchall-protected ----
    // Without the catch-body try-range, a `finally` that also covered the catch body would be INVENTED
    // (the bytecode does not run cleanup if the catch body throws) — so reconstruction must decline.
    @Test
    fun tr17_catchBodyNotProtectedBails() {
        val m = TestPipeline.buildMethod(
            tr17Reader(catchBodyProtected = false), returnType = IrType.OBJECT, argTypes = argTypes,
        )
        TestPipeline.structured(m)
        assertNull(m.region, "a catch body not covered by the catchall must bail (sibling catch would drop cleanup)")
        assertTrue(m[PipelineAttrs.FULLY_STRUCTURED] != true)
    }
}
