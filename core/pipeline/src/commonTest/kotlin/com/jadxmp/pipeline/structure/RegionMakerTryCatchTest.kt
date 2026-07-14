package com.jadxmp.pipeline.structure

import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.PhiInstruction
import com.jadxmp.ir.node.IrContainer
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.region.IfRegion
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Structuring of try/catch shapes over the **clean** (exception-free) CFG view: exception-edge marking
 * in the CFG builder, and [RegionMaker] building a [TryCatchRegion] around a **branchy** protected body
 * using clean post-dominators (worklist cluster 4). Complements the recompilable-Java oracle (Layer B)
 * with concrete region-tree assertions.
 */
class RegionMakerTryCatchTest {

    private val voidCall = FakeMethodRef("Lcom/example/Foo;", "sink", "V", emptyList())

    private fun assertNoPhi(method: IrMethod) {
        for (b in method.blocks) {
            assertTrue(b.instructions.none { it is PhiInstruction }, "φ must not remain on B${b.id}")
        }
    }

    private fun flatten(container: IrContainer, out: MutableList<Region>) {
        if (container is Region) {
            out.add(container)
            when (container) {
                is SequenceRegion -> container.children.forEach { flatten(it, out) }
                is TryCatchRegion -> {
                    flatten(container.tryRegion, out)
                    container.catches.forEach { flatten(it.body, out) }
                    container.finallyRegion?.let { flatten(it, out) }
                }
                is IfRegion -> {
                    flatten(container.thenRegion, out)
                    container.elseRegion?.let { flatten(it, out) }
                }
                else -> {}
            }
        }
    }

    private fun allRegions(method: IrMethod): List<Region> =
        ArrayList<Region>().also { method.region?.let { r -> flatten(r, it) } }

    private inline fun <reified T : Region> firstRegion(method: IrMethod): T? =
        allRegions(method).filterIsInstance<T>().firstOrNull()

    /** Children of the try body region (a [SequenceRegion]) as a flat list. */
    private fun tryBodyChildren(tc: TryCatchRegion): List<IrContainer> =
        (tc.tryRegion as? SequenceRegion)?.children ?: emptyList()

    // ---- exception-edge marking --------------------------------------------

    @Test
    fun cfgMarksDistinctExceptionEdgesButNotCoincidentOnes() {
        // try { sink(); } catch (Exception e) {}  — the empty catch continues to the SAME block the
        // normal fall-through reaches, so that one edge is BOTH normal and exceptional and must NOT be
        // marked (marking it would drop real control flow when the clean CFG removes it).
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = voidCall), // protected
                Insn(Opcode.RETURN_VOID, 1), // handler target == normal follow (no move-exception)
            ),
            // Handler at offset 1 IS the fall-through follow: the exc edge coincides with a normal edge.
            tries = listOf(FakeTryBlock(0, 0, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(1), -1))),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.cfg(method)
        val edges = method[PipelineAttrs.EXCEPTION_EDGES]
        assertNotNull(edges, "EXCEPTION_EDGES must be populated for a method with a try")
        // The protected block's exception edge coincides with its normal fall-through, so nothing is
        // marked (documents why these swallowing empty-catch shapes still can't be reconstructed).
        assertEquals(0, edges.size, "a purely-coincident exception edge must not be marked")
    }

    @Test
    fun branchyTryBodyStructuresIntoTryCatchWithNestedIf() {
        // try { if (p0 == 0) sink() else sink(); } catch (Exception e) { return; } return;
        // The protected body is a full diamond — only structurable with clean post-dominators, since the
        // raw post-dominator of the condition is pulled toward the handler by the exception edges.
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(0), target = 3), // protected: if (p0==0) goto else
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), methodRef = voidCall), // then
                Insn(Opcode.GOTO, 2, target = 4), // goto follow
                Insn(Opcode.INVOKE_STATIC, 3, intArrayOf(), methodRef = voidCall), // else (falls to 4)
                Insn(Opcode.RETURN_VOID, 4), // follow (outside try)
                Insn(Opcode.MOVE_EXCEPTION, 5, intArrayOf(0)), // handler
                Insn(Opcode.RETURN_VOID, 6),
            ),
            tries = listOf(FakeTryBlock(0, 3, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(5), -1))),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)
        // Three protected blocks (the if, the then, the else) each get a marked exception edge.
        assertEquals(3, method[PipelineAttrs.EXCEPTION_EDGES]?.size, "each protected block → handler is marked")
        assertNoPhi(method)
        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "branchy try must be fully structured")

        val tc = firstRegion<TryCatchRegion>(method)
        assertNotNull(tc, "a TryCatchRegion must be produced")
        assertEquals(1, tc.catches.size, "exactly one catch clause")
        assertTrue(tc.catches[0].exceptionTypes.isNotEmpty(), "the catch binds a concrete exception type")
        // The try body itself contains the reconstructed if/else — proof the branchy body structured.
        val nestedIf = tryBodyChildren(tc).filterIsInstance<IfRegion>().firstOrNull()
        assertNotNull(nestedIf, "the protected body's if/else must be reconstructed inside the try")
        assertNotNull(nestedIf.elseRegion, "both arms present ⇒ a real diamond inside the try")
    }

    @Test
    fun straightLineTryCatchStillStructures() {
        // Regression guard for the pre-existing straight-line shape:
        // try { sink(); } catch (Exception e) { return; } return;   (distinct catch target)
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = voidCall), // protected
                Insn(Opcode.RETURN_VOID, 1), // follow
                Insn(Opcode.MOVE_EXCEPTION, 2, intArrayOf(0)), // handler
                Insn(Opcode.RETURN_VOID, 3),
            ),
            tries = listOf(FakeTryBlock(0, 0, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(2), -1))),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        val tc = firstRegion<TryCatchRegion>(method)
        assertNotNull(tc, "a straight-line try/catch must still structure")
        assertEquals(1, tc.catches.size)
    }

    @Test
    fun emptyCatchThatContinuesReconstructsViaMembership() {
        // try { sink(); } catch (Exception e) {} other();   — the catch entry IS the follow (its exception
        // edge coincides with the normal fall-through, so it is NOT marked). Only try *membership* makes
        // this recoverable; without it the try/catch would be silently dropped (rule 4).
        val other = FakeMethodRef("Lcom/example/Foo;", "other", "V", emptyList())
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = voidCall), // protected
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), methodRef = other), // catch target == follow
                Insn(Opcode.RETURN_VOID, 2),
            ),
            tries = listOf(FakeTryBlock(0, 0, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(1), -1))),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "the coincident empty-catch must structure")
        assertEquals(0, method[PipelineAttrs.EXCEPTION_EDGES]?.size, "the coincident edge is not marked")
        val tc = firstRegion<TryCatchRegion>(method)
        assertNotNull(tc, "the try/catch must be reconstructed, not dropped")
        assertEquals(1, tc.catches.size)
        // The empty catch body carries no statements (it just swallows and continues to the follow).
        val catchBody = tc.catches[0].body
        assertTrue(
            catchBody is SequenceRegion && catchBody.children.isEmpty(),
            "the swallowing catch body must be empty",
        )
        // The follow block (holding `other()`) is emitted OUTSIDE the try body — the try/catch did not
        // swallow the code after it.
        val tryBodyBlocks = (tc.tryRegion as? SequenceRegion)?.children
            ?.filterIsInstance<com.jadxmp.ir.node.BasicBlock>()?.toSet() ?: emptySet()
        val topLevelBlocks = (method.region as? SequenceRegion)?.children
            ?.filterIsInstance<com.jadxmp.ir.node.BasicBlock>() ?: emptyList()
        assertTrue(
            topLevelBlocks.any { b -> b !in tryBodyBlocks && b.instructions.any { it.opcode == com.jadxmp.ir.insn.IrOpcode.INVOKE } },
            "the follow (other()) must be emitted after the try/catch, not inside it",
        )
    }

    private fun blockOccurrences(container: IrContainer?, out: MutableList<com.jadxmp.ir.node.BasicBlock>) {
        when (container) {
            is com.jadxmp.ir.node.BasicBlock -> out.add(container)
            is SequenceRegion -> container.children.forEach { blockOccurrences(it, out) }
            is TryCatchRegion -> {
                blockOccurrences(container.tryRegion, out)
                container.catches.forEach { blockOccurrences(it.body, out) }
                container.finallyRegion?.let { blockOccurrences(it, out) }
            }
            is IfRegion -> { blockOccurrences(container.thenRegion, out); container.elseRegion?.let { blockOccurrences(it, out) } }
            else -> {}
        }
    }

    @Test
    fun pureProtectedSharedTailDuplicatesAcrossArms() {
        // try { if (a) x = a; else if (b) x = a; ...; } catch (Exception e) { … }   return x;
        // The shared tail `x = a` (a pure MOVE) is reached from TWO arms but is INSIDE the try. A protected
        // block that cannot throw has a vacuous exception edge, so duplicating it is safe — it must NOT bail
        // ("revisit of placed") just because it sits in the try range.
        val reader = FakeCodeReader(
            4, // v0 = a, v1 = b, v2 = x, v3 = exc
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(0), target = 3), // B0: if (a==0) -> B1 else -> M (fall)
                Insn(Opcode.MOVE, 1, intArrayOf(2, 0)), // M: x = a  (pure, protected, shared)
                Insn(Opcode.GOTO, 2, target = 6), // M -> follow
                Insn(Opcode.IF_EQZ, 3, intArrayOf(1), target = 5), // B1: if (b==0) -> follow else -> M
                Insn(Opcode.GOTO, 4, target = 1), // B1-else -> M
                Insn(Opcode.GOTO, 5, target = 6), // B1-then -> follow
                Insn(Opcode.RETURN, 6, intArrayOf(2)), // follow: return x
                Insn(Opcode.MOVE_EXCEPTION, 7, intArrayOf(3)),
                Insn(Opcode.RETURN, 8, intArrayOf(2)), // catch: return x
            ),
            tries = listOf(FakeTryBlock(0, 5, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(7), -1))),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT, argTypes = listOf(IrType.INT, IrType.INT))
        TestPipeline.structured(method)
        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "a pure protected shared tail must structure (via duplication)")
        val occ = ArrayList<com.jadxmp.ir.node.BasicBlock>().also { blockOccurrences(method.region, it) }
        val moveBlocks = occ.filter { b -> b.instructions.any { it.opcode == com.jadxmp.ir.insn.IrOpcode.MOVE } }
        assertTrue(
            moveBlocks.groupingBy { it.id }.eachCount().values.any { it > 1 },
            "the pure protected shared tail is duplicated across the arms",
        )
    }

    @Test
    fun throwingProtectedSharedTailDoesNotDuplicateAndBails() {
        // Same shape, but the shared tail is `sink()` (an INVOKE — may throw). A THROWING protected block
        // must NOT be duplicated: a copy's exception could escape the try's protection. Bail honestly.
        val reader = FakeCodeReader(
            3, // v0 = a, v1 = b, v2 = exc
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(0), target = 3),
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), methodRef = voidCall), // M: sink() (may throw), protected, shared
                Insn(Opcode.GOTO, 2, target = 6),
                Insn(Opcode.IF_EQZ, 3, intArrayOf(1), target = 5),
                Insn(Opcode.GOTO, 4, target = 1),
                Insn(Opcode.GOTO, 5, target = 6),
                Insn(Opcode.RETURN_VOID, 6),
                Insn(Opcode.MOVE_EXCEPTION, 7, intArrayOf(2)),
                Insn(Opcode.RETURN_VOID, 8),
            ),
            tries = listOf(FakeTryBlock(0, 5, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(7), -1))),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.INT, IrType.INT))
        TestPipeline.structured(method)
        assertNull(method.region, "a throwing protected shared tail must NOT duplicate — honest bail")
    }

    @Test
    fun protectedSharedTailWrappingAThrowingDivDoesNotDuplicate() {
        // The reviewer's repro: the shared tail is `t = a / b; return t`. ExpressionShaping folds the
        // single-use DIV into the RETURN → `return (a / b)`, a whitelisted RETURN opcode WRAPPING a throwing
        // DIV. A top-level-opcode-only check would deem it non-throwing and duplicate this protected block —
        // but integer division may throw ArithmeticException, so a copy's exception could escape the try.
        // mayThrow must recurse through the wrapped operand and refuse; the method bails honestly.
        val reader = FakeCodeReader(
            4, // v0 = t (div result, fresh), v1 = exc; a = v2 (param), b = v3 (param)
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(2), target = 4), // B0: if (a==0) -> B1 else -> M (fall)
                Insn(Opcode.DIV_INT, 1, intArrayOf(0, 2, 3)), // M: t = a / b   (fresh dest, single-use → folds into RETURN)
                Insn(Opcode.RETURN, 2, intArrayOf(0)), // M: return t   → after shaping: return (a / b)
                Insn(Opcode.NOP, 3),
                Insn(Opcode.IF_EQZ, 4, intArrayOf(3), target = 6), // B1: if (b==0) -> follow else -> M
                Insn(Opcode.GOTO, 5, target = 1), // B1-else -> M (off1)
                Insn(Opcode.RETURN, 6, intArrayOf(2)), // follow (B1-then): return a
                Insn(Opcode.MOVE_EXCEPTION, 7, intArrayOf(1)),
                Insn(Opcode.RETURN, 8, intArrayOf(2)), // catch: return a
            ),
            tries = listOf(FakeTryBlock(0, 5, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(7), -1))),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT, argTypes = listOf(IrType.INT, IrType.INT))
        TestPipeline.structured(method)
        // The DIV-wrapping tail must NOT be duplicated: either the block appears at most once, or the method bails.
        val occ = ArrayList<com.jadxmp.ir.node.BasicBlock>().also { blockOccurrences(method.region, it) }
        val divBlocks = occ.filter { b -> b.instructions.any { insn -> mentionsDiv(insn) } }
        assertTrue(
            divBlocks.groupingBy { it.id }.eachCount().values.none { it > 1 },
            "a protected tail wrapping a throwing DIV must never be duplicated",
        )
    }

    /** Whether [insn] contains an integer div/rem anywhere (top-level or wrapped) — the throwing op under test. */
    private fun mentionsDiv(insn: com.jadxmp.ir.insn.Instruction): Boolean {
        val ai = insn as? com.jadxmp.ir.insn.ArithInstruction
        if (ai != null && (ai.op == com.jadxmp.ir.insn.ArithOp.DIV || ai.op == com.jadxmp.ir.insn.ArithOp.REM)) return true
        for (k in 0 until insn.argCount) {
            val a = insn.getArg(k)
            if (a is com.jadxmp.ir.insn.InstructionOperand && mentionsDiv(a.instruction)) return true
        }
        return false
    }

    @Test
    fun chainedCoincidentEmptyCatchesStructure() {
        // try { sink(); } catch (Exception e) {}
        // try { sink(); } catch (Exception e) {}   — the FIRST catch entry coincides with the follow, which
        // return;                                     is ALSO the SECOND try's body (a `try{}catch{}` chain).
        // The first coincident handler is itself protected by the second try. That used to bail ("nested try
        // in handler"); a coincident empty catch whose follow is the next try's body must instead structure —
        // the enclosing chain re-enters the follow as an ordinary block and opens the next try.
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = voidCall), // try1 body
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), methodRef = voidCall), // try1's coincident handler == try2 body
                Insn(Opcode.RETURN_VOID, 2), // try2's coincident handler == follow
            ),
            tries = listOf(
                FakeTryBlock(0, 0, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(1), -1)),
                FakeTryBlock(1, 1, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(2), -1)),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "the chained empty-catch tries must structure")
        assertNoPhi(method)
        val tries = allRegions(method).filterIsInstance<TryCatchRegion>()
        assertEquals(2, tries.size, "both try/catch blocks are reconstructed, not dropped")
        for (tc in tries) {
            assertEquals(1, tc.catches.size, "each is a single empty catch")
            val body = tc.catches[0].body
            assertTrue(body is SequenceRegion && body.children.isEmpty(), "each swallowing catch body is empty")
        }
        // Direct no-code-loss guard: the inter-try follow (`sink()` at offset 1, which is the FIRST catch's
        // coincident follow AND the SECOND try's body) must survive — emitted inside the SECOND try body, not
        // dropped. Both `sink()` calls and the terminal return must all appear in the region tree.
        val emitted = ArrayList<com.jadxmp.ir.node.BasicBlock>()
        fun collect(c: IrContainer?) {
            when (c) {
                is com.jadxmp.ir.node.BasicBlock -> emitted.add(c)
                is SequenceRegion -> c.children.forEach { collect(it) }
                is TryCatchRegion -> { collect(c.tryRegion); c.catches.forEach { collect(it.body) } }
                else -> {}
            }
        }
        collect(method.region)
        val invokeCount = emitted.sumOf { b -> b.instructions.count { it.opcode == com.jadxmp.ir.insn.IrOpcode.INVOKE } }
        assertEquals(2, invokeCount, "both sink() calls survive (no inter-try follow dropped)")
        // The second try's body carries the follow `sink()` — proof it was emitted inside a try, not lost.
        val secondTryBodyBlocks = ArrayList<com.jadxmp.ir.node.BasicBlock>().also { collectBlocks(tries[1].tryRegion, it) }
        assertTrue(
            secondTryBodyBlocks.any { b -> b.instructions.any { it.opcode == com.jadxmp.ir.insn.IrOpcode.INVOKE } },
            "the inter-try follow (second try body) is emitted, not swallowed",
        )
    }

    private fun collectBlocks(c: IrContainer?, out: MutableList<com.jadxmp.ir.node.BasicBlock>) {
        when (c) {
            is com.jadxmp.ir.node.BasicBlock -> out.add(c)
            is SequenceRegion -> c.children.forEach { collectBlocks(it, out) }
            is TryCatchRegion -> { collectBlocks(c.tryRegion, out); c.catches.forEach { collectBlocks(it.body, out) } }
            else -> {}
        }
    }

    @Test
    fun finallyReconstructsFromInlinedCleanupCopies() {
        // try { sink(); } finally { cleanup(); } return;
        // javac inlines `cleanup()` twice: on the normal path ([cleanup(); return]) and in the synthetic
        // catch-all ([move-exception; cleanup(); throw]). The two identical copies must merge into ONE
        // `finally { cleanup(); }`; the cleanup must survive exactly once, never dropped.
        val cleanup = FakeMethodRef("Lcom/example/Foo;", "cleanup", "V", emptyList())
        val reader = FakeCodeReader(
            1, // v0 = exception var
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = voidCall), // try body: sink() (protected)
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), methodRef = cleanup), // normal-path cleanup copy
                Insn(Opcode.RETURN_VOID, 2), // the transfer after cleanup
                Insn(Opcode.MOVE_EXCEPTION, 3, intArrayOf(0)), // catch-all handler
                Insn(Opcode.INVOKE_STATIC, 4, intArrayOf(), methodRef = cleanup), // handler cleanup copy
                Insn(Opcode.THROW, 5, intArrayOf(0)), // re-throw
            ),
            tries = listOf(FakeTryBlock(0, 0, FakeCatchHandler(emptyList(), emptyList(), 3))), // catch-all at off3
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "the try/finally must structure")
        assertNoPhi(method)
        val tc = firstRegion<TryCatchRegion>(method)
        assertNotNull(tc, "a try/catch/finally region must be built")
        assertTrue(tc.catches.isEmpty(), "a pure finally has no catch clauses")
        assertNotNull(tc.finallyRegion, "the cleanup is hoisted into a finally region")
        // The cleanup survives EXACTLY once, in the finally, and is NOT DONT_GENERATE'd there.
        val finallyBlocks = ArrayList<com.jadxmp.ir.node.BasicBlock>().also { collectBlocks(tc.finallyRegion, it) }
        val liveCleanups = finallyBlocks.sumOf { b ->
            b.instructions.count { it.opcode == com.jadxmp.ir.insn.IrOpcode.INVOKE && !it.contains(AttrFlag.DONT_GENERATE) }
        }
        assertEquals(1, liveCleanups, "the cleanup is emitted once in the finally")
        // Across the WHOLE method, exactly one cleanup INVOKE is live (the other copy + the re-throw are hidden).
        val allLiveCleanups = method.blocks.sumOf { b ->
            b.instructions.count { insn ->
                insn is com.jadxmp.ir.insn.InvokeInstruction && insn.methodRef.name == "cleanup" &&
                    !insn.contains(AttrFlag.DONT_GENERATE)
            }
        }
        assertEquals(1, allLiveCleanups, "no cleanup copy is dropped and none is duplicated — exactly one remains")
        // The synthetic re-throw and move-exception are consumed (hidden), not emitted as a real catch.
        val throwHidden = method.blocks.flatMap { it.instructions }
            .single { it.opcode == com.jadxmp.ir.insn.IrOpcode.THROW }.contains(AttrFlag.DONT_GENERATE)
        assertTrue(throwHidden, "the synthetic re-throw is hidden by the finally")
    }

    @Test
    fun inBodyReturnCarryingItsOwnCleanupCopyRendersWithoutDoubleRun() {
        // try { sink(); if (c) { cleanup(); return; } } finally { cleanup(); } return;
        // javac inlines the cleanup on EVERY exit — the in-body early return, the normal exit, and the
        // handler (three copies). The explicit-catch rendering does NOT factor a finally, so it keeps each
        // copy exactly where it is: `try { sink(); if (c!=0) { cleanup(); return; } } catch (Throwable e)
        // { cleanup(); throw e; } cleanup(); return;`. Each path runs cleanup EXACTLY ONCE — no
        // double-execution (the very hazard that finally-factoring would have introduced) and no drop.
        // (This is why we do not factor: the transcription is faithful where factoring is unsafe.)
        val cleanup = FakeMethodRef("Lcom/example/Foo;", "cleanup", "V", emptyList())
        val reader = FakeCodeReader(
            2, // v0 = exception var, v1 = c (param)
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = voidCall), // try body: sink()
                Insn(Opcode.IF_EQZ, 1, intArrayOf(1), target = 4), // if (c == 0) -> normal exit else early exit
                Insn(Opcode.INVOKE_STATIC, 2, intArrayOf(), methodRef = cleanup), // IN-BODY early-return cleanup copy
                Insn(Opcode.RETURN_VOID, 3), // in-body return
                Insn(Opcode.INVOKE_STATIC, 4, intArrayOf(), methodRef = cleanup), // normal-path cleanup copy
                Insn(Opcode.RETURN_VOID, 5),
                Insn(Opcode.MOVE_EXCEPTION, 6, intArrayOf(0)), // catch-all handler
                Insn(Opcode.INVOKE_STATIC, 7, intArrayOf(), methodRef = cleanup), // handler cleanup copy
                Insn(Opcode.THROW, 8, intArrayOf(0)),
            ),
            // Protect the body AND the in-body early-return block (offsets 0..3); catch-all at offset 6.
            tries = listOf(FakeTryBlock(0, 3, FakeCatchHandler(emptyList(), emptyList(), 6))),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.INT))
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "the in-body-return shape renders (no bail)")
        val tc = firstRegion<TryCatchRegion>(method)
        assertNotNull(tc)
        assertNull(tc.finallyRegion, "no finally is factored (that would double-run the in-body copy)")
        // Exactly the original count of calls survives: sink + the THREE cleanup copies (in-body, normal,
        // handler). A factored finally would have ADDED a fourth copy → this asserts NO double-run.
        assertEquals(4, method.blocks.flatMap { it.instructions }.count { it.opcode == IrOpcode.INVOKE })
    }

    @Test
    fun differingCleanupCopiesAreNotMergedButKeptOnTheirPaths() {
        // The normal-path cleanup (cleanupA) differs from the catch-all's cleanup (cleanupB). They must
        // NOT be merged into one finally. The explicit-catch rendering keeps each on ITS OWN path —
        // `try { sink(); } catch (Throwable e) { cleanupB(); throw e; } cleanupA(); return;` — which is
        // exactly the bytecode: cleanupA on normal exit, cleanupB on the exceptional path. Faithful; no
        // statement dropped or misplaced (the very risk the old merge would have caused).
        val cleanupA = FakeMethodRef("Lcom/example/Foo;", "cleanupA", "V", emptyList())
        val cleanupB = FakeMethodRef("Lcom/example/Foo;", "cleanupB", "V", emptyList())
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = voidCall),
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), methodRef = cleanupA), // normal path: cleanupA()
                Insn(Opcode.RETURN_VOID, 2),
                Insn(Opcode.MOVE_EXCEPTION, 3, intArrayOf(0)),
                Insn(Opcode.INVOKE_STATIC, 4, intArrayOf(), methodRef = cleanupB), // handler: cleanupB() (DIFFERENT)
                Insn(Opcode.THROW, 5, intArrayOf(0)),
            ),
            tries = listOf(FakeTryBlock(0, 0, FakeCatchHandler(emptyList(), emptyList(), 3))),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "differing cleanups render (no merge, no bail)")
        val tc = firstRegion<TryCatchRegion>(method)
        assertNotNull(tc)
        assertNull(tc.finallyRegion, "the differing copies must NOT be merged into a finally")
        // All three calls survive (sink + cleanupA on normal + cleanupB in catch) — nothing merged/dropped.
        assertEquals(3, method.blocks.flatMap { it.instructions }.count { it.opcode == IrOpcode.INVOKE })
    }

    @Test
    fun tryDefUsedInsideViaInlinedExpressionDoesNotFalselyEscape() {
        // try { v0 = compute(); sink(v0 + 1); sink(v0); } catch (Exception e) {} return;
        // v0 is used only INSIDE the try, but one use is inlined into `sink(v0 + 1)` (a wrapped operand).
        // The escape check must see that wrapped read (recursively) — otherwise it falsely concludes v0
        // escapes and bails. It must structure.
        val compute = FakeMethodRef("Lc/F;", "compute", "I", emptyList())
        val sink = FakeMethodRef("Lc/F;", "sink", "V", listOf("I"))
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = compute), // try_start
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)), // v0 = compute()
                Insn(Opcode.ADD_INT_LIT, 2, intArrayOf(1, 0), literal = 1), // v1 = v0 + 1  (inlines into next)
                Insn(Opcode.INVOKE_STATIC, 3, intArrayOf(1), methodRef = sink), // sink(v0 + 1) — wrapped use of v0
                Insn(Opcode.INVOKE_STATIC, 4, intArrayOf(0), methodRef = sink), // sink(v0)  — keeps v0 multi-use
                Insn(Opcode.RETURN_VOID, 5), // follow (after try)
                Insn(Opcode.MOVE_EXCEPTION, 6, intArrayOf(0)), // handler
                Insn(Opcode.GOTO, 7, target = 5), // catch continues to the follow
            ),
            tries = listOf(FakeTryBlock(0, 4, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(6), -1))),
        )
        val method = TestPipeline.buildMethod(reader, methodName = "m")
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "a value used only inside the try must not be treated as escaping")
        assertNotNull(firstRegion<TryCatchRegion>(method), "the try/catch must structure")
    }

    @Test
    fun tryWhoseBodyAllReturnsStructuresWithNoFollow() {
        // try { sink(); return; } catch (Exception e) { return; }   — the try body's only exit is a return,
        // so the try/catch has NO normal follow (nothing runs after). It must still structure.
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = voidCall), // protected
                Insn(Opcode.RETURN_VOID, 1), // try body returns (protected)
                Insn(Opcode.MOVE_EXCEPTION, 2, intArrayOf(0)), // handler
                Insn(Opcode.RETURN_VOID, 3), // catch returns
            ),
            tries = listOf(FakeTryBlock(0, 1, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(2), -1))),
        )
        val method = TestPipeline.buildMethod(reader, methodName = "m")
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "a return-only try body must structure (no follow)")
        val tc = firstRegion<TryCatchRegion>(method)
        assertNotNull(tc, "a TryCatchRegion must be produced")
        assertEquals(1, tc.catches.size)
    }

    @Test
    fun methodWithoutExceptionsIsUnaffected() {
        // A plain if/else with no try: EXCEPTION_EDGES stays absent and structuring is unchanged.
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(0), target = 3),
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), methodRef = voidCall),
                Insn(Opcode.GOTO, 2, target = 4),
                Insn(Opcode.INVOKE_STATIC, 3, intArrayOf(), methodRef = voidCall),
                Insn(Opcode.RETURN_VOID, 4),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)
        assertTrue(method[PipelineAttrs.EXCEPTION_EDGES] == null, "no try ⇒ no exception-edge attribute")
        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED])
        assertNotNull(firstRegion<IfRegion>(method), "the ordinary if/else must still structure")
    }

    @Test
    fun unfactorableRethrowingCatchAllRendersAsExplicitCatch() {
        // try { sink(); } catchall { cleanup(); throw e; }  — the NORMAL path has no cleanup, so the
        // finally cannot be factored (reconstructFinally returns null). Instead of bailing, the catch-all
        // is rendered FAITHFULLY as `catch (Throwable e) { cleanup(); throw e; }` — a 1:1 transcription of
        // the bytecode handler (jadx does the same when it cannot extract the finally). Nothing is dropped
        // or doubled: the cleanup stays exactly where the handler had it, and the rethrow is preserved.
        val cleanup = FakeMethodRef("Lcom/example/Foo;", "cleanup", "V", emptyList())
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, methodRef = voidCall), // protected try body
                Insn(Opcode.RETURN_VOID, 1), // follow — the normal path, with NO cleanup
                Insn(Opcode.MOVE_EXCEPTION, 2, intArrayOf(0)), // catch-all handler entry
                Insn(Opcode.INVOKE_STATIC, 3, methodRef = cleanup), // the cleanup
                Insn(Opcode.THROW, 4, intArrayOf(0)), // rethrow the caught exception
            ),
            tries = listOf(FakeTryBlock(0, 0, FakeCatchHandler(emptyList(), emptyList(), 2))),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "the rethrowing catch-all structures (not bail)")
        val tc = firstRegion<TryCatchRegion>(method)
        assertNotNull(tc, "a TryCatchRegion must be produced")
        assertNull(tc.finallyRegion, "not factored into a finally (the normal path has no cleanup)")
        assertEquals(1, tc.catches.size, "one catch clause")
        assertEquals(listOf(IrType.THROWABLE), tc.catches[0].exceptionTypes, "a catch-all renders as catch (Throwable)")
        // The cleanup call and the rethrow are BOTH kept (nothing dropped); the rethrow is emitted (a real
        // `throw e`, not hidden the way a factored finally would hide it).
        val insns = method.blocks.flatMap { it.instructions }
        assertEquals(2, insns.count { it.opcode == IrOpcode.INVOKE }, "both the body call and the cleanup call remain")
        val throwInsn = insns.firstOrNull { it.opcode == IrOpcode.THROW }
        assertNotNull(throwInsn, "the rethrow is preserved")
        assertFalse(throwInsn.contains(AttrFlag.DONT_GENERATE), "the rethrow is emitted (not hidden as in a finally)")
    }

    @Test
    fun catchAllHandlerWithNamedTypesCollapsesToThrowable() {
        // A DEX handler that lists named alternatives AND a `.catchall` (all → the SAME block) catches
        // every throwable, so the illegal `A | B | Exception` multi-catch (subclasses of one another)
        // collapses to `Throwable` alone — matching jadx, and sound with no class hierarchy.
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = voidCall), // protected
                Insn(Opcode.RETURN_VOID, 1), // follow
                Insn(Opcode.MOVE_EXCEPTION, 2, intArrayOf(0)), // handler
                Insn(Opcode.RETURN_VOID, 3),
            ),
            tries = listOf(
                FakeTryBlock(
                    0, 0,
                    FakeCatchHandler(
                        listOf(
                            "Ljava/lang/ClassNotFoundException;",
                            "Ljava/lang/NoSuchMethodException;",
                            "Ljava/lang/Exception;",
                        ),
                        listOf(2, 2, 2),
                        2, // catch-all → the same handler block
                    ),
                ),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        val tc = firstRegion<TryCatchRegion>(method)
        assertNotNull(tc, "the catch-all try/catch structures")
        assertEquals(1, tc.catches.size, "one handler ⇒ one catch clause")
        assertEquals(
            listOf(IrType.THROWABLE),
            tc.catches[0].exceptionTypes,
            "a catch-all handler's redundant named alternatives collapse to Throwable",
        )
    }

    @Test
    fun multiCatchWithoutCatchAllKeepsItsAlternatives() {
        // Two unrelated named alternatives, NO catch-all → a genuine `A | B` multi-catch, preserved as-is
        // (never collapsed): the catch-all collapse must not touch a real multi-catch.
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = voidCall), // protected
                Insn(Opcode.RETURN_VOID, 1), // follow
                Insn(Opcode.MOVE_EXCEPTION, 2, intArrayOf(0)), // handler
                Insn(Opcode.RETURN_VOID, 3),
            ),
            tries = listOf(
                FakeTryBlock(
                    0, 0,
                    FakeCatchHandler(
                        listOf("Ljava/io/IOException;", "Ljava/lang/RuntimeException;"),
                        listOf(2, 2),
                        -1, // no catch-all
                    ),
                ),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        val tc = firstRegion<TryCatchRegion>(method)
        assertNotNull(tc, "the multi-catch try/catch structures")
        assertEquals(1, tc.catches.size)
        assertEquals(2, tc.catches[0].exceptionTypes.size, "a real A|B multi-catch is preserved, not collapsed")
    }
}
