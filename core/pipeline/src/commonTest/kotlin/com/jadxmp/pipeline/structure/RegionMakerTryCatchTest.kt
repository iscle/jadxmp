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

    /** Every [BasicBlock] anywhere under [c], recursing through ALL region kinds (including if-arms). */
    private fun deepBlocks(c: IrContainer?): List<com.jadxmp.ir.node.BasicBlock> =
        ArrayList<com.jadxmp.ir.node.BasicBlock>().also { deepBlocksInto(c, it) }

    private fun deepBlocksInto(c: IrContainer?, out: MutableList<com.jadxmp.ir.node.BasicBlock>) {
        when (c) {
            is com.jadxmp.ir.node.BasicBlock -> out.add(c)
            is SequenceRegion -> c.children.forEach { deepBlocksInto(it, out) }
            is TryCatchRegion -> {
                deepBlocksInto(c.tryRegion, out)
                c.catches.forEach { deepBlocksInto(it.body, out) }
                c.finallyRegion?.let { deepBlocksInto(it, out) }
            }
            is IfRegion -> {
                deepBlocksInto(c.thenRegion, out)
                c.elseRegion?.let { deepBlocksInto(it, out) }
            }
            else -> {}
        }
    }

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
    fun catchReachingOuterMergePastTheTryFollowIsPlacedOnceViaActiveExitStack() {
        // if (p0 != 0) { try { sink(); } catch (Exception e) {} sink(); } else { sink(); } ; MERGE: return
        // The catch handler jumps DIRECTLY to the outer if's merge (offset 8), PAST the try's own follow
        // (offset 7, the `sink()` after the try inside the then-arm). So the catch reaches an ENCLOSING
        // region's follow that is NOT its immediate chain-follow — the single `chainFollow` could not see
        // it, so the catch re-placed MERGE and the enclosing chain then revisited it → bail (the
        // TestOutBlock shape). With the active-exit stack, MERGE is an active exit (pushed by the outer if),
        // so the catch stops there and MERGE is placed EXACTLY ONCE. This shape genuinely requires the
        // stack (it bails with only the immediate chain-follow check).
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.IF_NEZ, 0, intArrayOf(0), target = 3), // outer if: if (p0!=0) goto then; else fall
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), methodRef = voidCall), // else-arm
                Insn(Opcode.GOTO, 2, target = 8), // else -> MERGE(8)
                Insn(Opcode.INVOKE_STATIC, 3, intArrayOf(), methodRef = voidCall), // try body (try_start = 3)
                Insn(Opcode.GOTO, 4, target = 7), // try body -> the try's follow F_try(7)  (try_end after this)
                Insn(Opcode.MOVE_EXCEPTION, 5, intArrayOf(0)), // catch handler
                Insn(Opcode.GOTO, 6, target = 8), // catch -> MERGE(8) DIRECTLY, past F_try(7)
                Insn(Opcode.INVOKE_STATIC, 7, intArrayOf(), methodRef = voidCall), // F_try: after the try, then-arm
                Insn(Opcode.RETURN_VOID, 8), // MERGE — the outer if's follow, reached by then-arm and else-arm
            ),
            tries = listOf(FakeTryBlock(3, 4, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(5), -1))),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        assertNoPhi(method)
        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "the catch-to-outer-merge shape must fully structure")
        assertFalse(method.contains(AttrFlag.HAS_ERROR), "correct structuring ⇒ no error flag")

        // The outer merge (offset 8) appears EXACTLY ONCE in the emitted tree — the partition property.
        val occ = HashMap<Int, Int>()
        fun walk(c: IrContainer?) {
            when (c) {
                is com.jadxmp.ir.node.BasicBlock -> occ[c.id] = (occ[c.id] ?: 0) + 1
                is SequenceRegion -> c.children.forEach { walk(it) }
                is TryCatchRegion -> { walk(c.tryRegion); c.catches.forEach { walk(it.body) }; c.finallyRegion?.let { walk(it) } }
                is IfRegion -> { walk(c.thenRegion); c.elseRegion?.let { walk(it) } }
                else -> {}
            }
        }
        walk(method.region)
        val mergeBlock = method.blocks.first { b -> b.instructions.any { it.offset == 8 } }
        assertEquals(1, occ[mergeBlock.id], "the outer merge must be placed exactly once (partitioned, not re-placed)")

        val tc = firstRegion<TryCatchRegion>(method)
        assertNotNull(tc, "a TryCatchRegion is produced inside the if arm")
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

    // ---- multi-exit try/finally (TestFinally3 / TestTypeResolver17 family) ----

    private val mCleanup = FakeMethodRef("Lcom/example/Foo;", "cleanup", "V", emptyList())

    /**
     * The essential single-try-range multi-exit try/finally shape: one protected body ending in an `if`
     * whose TWO arms each fall to an unprotected `[cleanup(); return]` copy, plus the catch-all
     * `[move-exception; cleanup(); throw]`. javac inlined the cleanup on all THREE exit paths (two normal
     * returns + the exceptional re-throw). The three identical copies must collapse into ONE
     * `finally { cleanup(); }` with the two returns kept inside the `try {}` — cleanup runs exactly once per
     * path, never dropped, never doubled.
     */
    @Test
    fun multiExitFinallyReconstructsFromInlinedCleanupCopies() {
        val reader = FakeCodeReader(
            2, // v0 = cond/param, v1 = exception var
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = voidCall), // T0 try body: sink()
                Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4), // T0 end: if v0==0 -> exit2(off4) else exit1
                Insn(Opcode.INVOKE_STATIC, 2, intArrayOf(), methodRef = mCleanup), // exit1: cleanup copy
                Insn(Opcode.RETURN_VOID, 3), // exit1 return
                Insn(Opcode.INVOKE_STATIC, 4, intArrayOf(), methodRef = mCleanup), // exit2: cleanup copy
                Insn(Opcode.RETURN_VOID, 5), // exit2 return
                Insn(Opcode.MOVE_EXCEPTION, 6, intArrayOf(1)), // catch-all handler
                Insn(Opcode.INVOKE_STATIC, 7, intArrayOf(), methodRef = mCleanup), // handler cleanup copy
                Insn(Opcode.THROW, 8, intArrayOf(1)), // re-throw
            ),
            tries = listOf(FakeTryBlock(0, 1, FakeCatchHandler(emptyList(), emptyList(), 6))), // catch-all over T0
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.INT))
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "the multi-exit try/finally must structure")
        assertNoPhi(method)
        val tc = firstRegion<TryCatchRegion>(method)
        assertNotNull(tc, "a try/finally region must be built")
        assertTrue(tc.catches.isEmpty(), "a pure finally has no catch clauses")
        assertNotNull(tc.finallyRegion, "the cleanup is hoisted into a finally region")
        // The two normal returns stay inside the try body (each ends its arm), reached via the branch.
        assertNotNull(firstRegion<IfRegion>(method), "the branch to the two returns is kept in the try body")
        // Exactly ONE cleanup INVOKE survives across the whole method — the two inlined copies and the
        // handler copy collapse to the single finally copy. Never dropped (>=1), never doubled (==1).
        val liveCleanups = method.blocks.sumOf { b ->
            b.instructions.count { insn ->
                insn is com.jadxmp.ir.insn.InvokeInstruction && insn.methodRef.name == "cleanup" &&
                    !insn.contains(AttrFlag.DONT_GENERATE)
            }
        }
        assertEquals(1, liveCleanups, "cleanup emitted exactly once (in the finally) on every path")
        // The surviving cleanup lives in the finally region.
        val finallyLive = ArrayList<com.jadxmp.ir.node.BasicBlock>().also { collectBlocks(tc.finallyRegion, it) }
            .sumOf { b -> b.instructions.count { it.opcode == IrOpcode.INVOKE && !it.contains(AttrFlag.DONT_GENERATE) } }
        assertEquals(1, finallyLive, "the one surviving cleanup is the finally's")
        // Both normal `return`s survive (nothing dropped), plus the hidden synthetic re-throw.
        assertEquals(2, method.blocks.flatMap { it.instructions }.count { it.opcode == IrOpcode.RETURN })
        val throwHidden = method.blocks.flatMap { it.instructions }
            .single { it.opcode == IrOpcode.THROW }.contains(AttrFlag.DONT_GENERATE)
        assertTrue(throwHidden, "the synthetic re-throw is hidden by the finally")
    }

    /**
     * Rule-4 drop guard: if ONE normal exit is missing its inlined cleanup, the copies must NOT be factored
     * (factoring would emit cleanup on that path where the bytecode did not — changing behavior). The
     * method must bail honestly (region==null), never a wrong finally.
     */
    @Test
    fun multiExitFinallyWithOneExitMissingCleanupBails() {
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = voidCall),
                Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4),
                Insn(Opcode.INVOKE_STATIC, 2, intArrayOf(), methodRef = mCleanup), // exit1: HAS cleanup
                Insn(Opcode.RETURN_VOID, 3),
                Insn(Opcode.RETURN_VOID, 4), // exit2: NO cleanup (bare return) — must prevent factoring
                Insn(Opcode.MOVE_EXCEPTION, 5, intArrayOf(1)),
                Insn(Opcode.INVOKE_STATIC, 6, intArrayOf(), methodRef = mCleanup),
                Insn(Opcode.THROW, 7, intArrayOf(1)),
            ),
            tries = listOf(FakeTryBlock(0, 1, FakeCatchHandler(emptyList(), emptyList(), 5))),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.INT))
        TestPipeline.structured(method)
        assertNull(method.region, "an exit lacking the cleanup must bail, never factor a finally that adds it")
        assertTrue(method[PipelineAttrs.FULLY_STRUCTURED] != true)
    }

    /**
     * A catch-all whose cleanup READS the caught exception is a real `catch`, not a finally — collapsing it
     * into a `finally {}` (which has no exception in scope) would be wrong. Even with the multi-exit
     * inlined-copy shape, such a handler must NOT be treated as a finally: the method bails honestly.
     */
    @Test
    fun multiExitCatchallThatUsesTheExceptionIsNotFinally() {
        val logExc = FakeMethodRef("Lcom/example/Foo;", "log", "V", listOf("Ljava/lang/Throwable;"))
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = voidCall),
                Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4),
                Insn(Opcode.INVOKE_STATIC, 2, intArrayOf(), methodRef = mCleanup), // exit1 cleanup copy
                Insn(Opcode.RETURN_VOID, 3),
                Insn(Opcode.INVOKE_STATIC, 4, intArrayOf(), methodRef = mCleanup), // exit2 cleanup copy
                Insn(Opcode.RETURN_VOID, 5),
                Insn(Opcode.MOVE_EXCEPTION, 6, intArrayOf(1)), // catch-all binds e
                Insn(Opcode.INVOKE_STATIC, 7, intArrayOf(1), methodRef = logExc), // log(e) — USES the exception
                Insn(Opcode.THROW, 8, intArrayOf(1)),
            ),
            tries = listOf(FakeTryBlock(0, 1, FakeCatchHandler(emptyList(), emptyList(), 6))),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.INT))
        TestPipeline.structured(method)
        assertNull(method.region, "a handler that reads the caught exception must not be collapsed to a finally")
        assertTrue(method[PipelineAttrs.FULLY_STRUCTURED] != true)
    }

    /**
     * A catch-all whose cleanup BRANCHES (a multi-block handler) is outside the provable single-block
     * envelope: hoisting a branching cleanup into `finally {}` is not proven safe, so the method bails.
     */
    @Test
    fun multiExitFinallyWithBranchingCleanupBails() {
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = voidCall),
                Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4),
                Insn(Opcode.INVOKE_STATIC, 2, intArrayOf(), methodRef = mCleanup),
                Insn(Opcode.RETURN_VOID, 3),
                Insn(Opcode.INVOKE_STATIC, 4, intArrayOf(), methodRef = mCleanup),
                Insn(Opcode.RETURN_VOID, 5),
                Insn(Opcode.MOVE_EXCEPTION, 6, intArrayOf(1)), // catch-all entry
                Insn(Opcode.IF_EQZ, 7, intArrayOf(0), target = 9), // cleanup BRANCHES ⇒ multi-block handler
                Insn(Opcode.INVOKE_STATIC, 8, intArrayOf(), methodRef = mCleanup),
                Insn(Opcode.THROW, 9, intArrayOf(1)),
            ),
            tries = listOf(FakeTryBlock(0, 1, FakeCatchHandler(emptyList(), emptyList(), 6))),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.INT))
        TestPipeline.structured(method)
        assertNull(method.region, "a branching (multi-block) cleanup handler must bail, not factor a finally")
        assertTrue(method[PipelineAttrs.FULLY_STRUCTURED] != true)
    }

    /**
     * The full **split-range** finally shape of `TestFinally3`: a source `try { … } finally { close(); }`
     * that javac split into TWO try-ranges sharing one catch-all, with an OUTER `if` whose taken arm jumps
     * straight to the shared post-code (a protected merge), whose fall arm holds an INNER `if` that
     * early-returns with an inlined cleanup, and a second range flowing into that same merge which ends in a
     * second inlined `cleanup(); return`. The structurer must grow the whole finally body across the two
     * ranges + the non-throwing between-code, recognize BOTH inlined-cleanup exits, and factor ONE
     * `try { … } finally { cleanup(); }` — cleanup emitted exactly once per path, both returns inside the try.
     */
    @Test
    fun splitRangeFinallyWithInnerEarlyReturnReconstructs() {
        val reader = FakeCodeReader(
            3, // v0 = cond1 (param), v1 = cond2 (param), v2 = exception var
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = voidCall), // A (try1): protected work
                Insn(Opcode.IF_NEZ, 1, intArrayOf(0), target = 6), // A end: if cond1 -> J(off6, merge) else C
                Insn(Opcode.IF_NEZ, 2, intArrayOf(1), target = 5), // C (unprot between): if cond2 -> E else D
                Insn(Opcode.INVOKE_STATIC, 3, intArrayOf(), methodRef = mCleanup), // D exit: inlined cleanup copy
                Insn(Opcode.RETURN_VOID, 4), // D exit: early return
                Insn(Opcode.INVOKE_STATIC, 5, intArrayOf(), methodRef = voidCall), // E (try2): protected work → J
                Insn(Opcode.INVOKE_STATIC, 6, intArrayOf(), methodRef = voidCall), // J (try2): shared merge → G
                Insn(Opcode.INVOKE_STATIC, 7, intArrayOf(), methodRef = mCleanup), // G exit: inlined cleanup copy
                Insn(Opcode.RETURN_VOID, 8), // G exit: return
                Insn(Opcode.MOVE_EXCEPTION, 9, intArrayOf(2)), // H: catch-all for BOTH ranges (finally)
                Insn(Opcode.INVOKE_STATIC, 10, intArrayOf(), methodRef = mCleanup), // H cleanup copy
                Insn(Opcode.THROW, 11, intArrayOf(2)),
            ),
            tries = listOf(
                FakeTryBlock(0, 1, FakeCatchHandler(emptyList(), emptyList(), 9)), // try1: A
                FakeTryBlock(5, 6, FakeCatchHandler(emptyList(), emptyList(), 9)), // try2: E, J
            ),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.INT, IrType.INT))
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "the split-range try/finally must structure")
        assertNoPhi(method)
        val tc = firstRegion<TryCatchRegion>(method)
        assertNotNull(tc, "a single try/finally spanning both ranges must be built")
        assertTrue(tc.catches.isEmpty(), "a pure finally has no catch clauses")
        assertNotNull(tc.finallyRegion, "the cleanup is hoisted into a finally region")
        assertNotNull(firstRegion<IfRegion>(method), "the branchy body (outer + inner if) is kept in the try")
        // Exactly ONE cleanup INVOKE survives across the whole method — both inlined copies + the handler copy
        // collapse to the single finally copy. Never dropped (>=1), never doubled (==1).
        val liveCleanups = method.blocks.sumOf { b ->
            b.instructions.count { insn ->
                insn is com.jadxmp.ir.insn.InvokeInstruction && insn.methodRef.name == "cleanup" &&
                    !insn.contains(AttrFlag.DONT_GENERATE)
            }
        }
        assertEquals(1, liveCleanups, "cleanup emitted exactly once (in the finally) on every path")
        // Both inlined-cleanup returns survive (nothing dropped) — one per normal exit path.
        assertEquals(2, method.blocks.flatMap { it.instructions }.count { it.opcode == IrOpcode.RETURN },
            "both returns survive, one per normal exit path")
        // They live INSIDE the try body (the region is one try/finally with no follow — nothing after it), and
        // the finally holds ONLY the cleanup (no return leaked into it). Walk the whole region tree.
        val tryReturns = deepBlocks(tc.tryRegion).sumOf { b -> b.instructions.count { it.opcode == IrOpcode.RETURN } }
        assertEquals(2, tryReturns, "both returns are pulled inside the try (finally runs cleanup on each)")
        val finallyReturns = deepBlocks(tc.finallyRegion!!).sumOf { b -> b.instructions.count { it.opcode == IrOpcode.RETURN } }
        assertEquals(0, finallyReturns, "no return leaked into the finally")
        val throwHidden = method.blocks.flatMap { it.instructions }
            .single { it.opcode == IrOpcode.THROW }.contains(AttrFlag.DONT_GENERATE)
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
