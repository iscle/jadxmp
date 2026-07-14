package com.jadxmp.pipeline.structure

import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.IfInstruction
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.PhiInstruction
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrContainer
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.region.Condition
import com.jadxmp.ir.region.IfRegion
import com.jadxmp.ir.region.LoopKind
import com.jadxmp.ir.region.LoopRegion
import com.jadxmp.ir.region.Region
import com.jadxmp.ir.region.SequenceRegion
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
 * Phase-3 structuring tests: out-of-SSA (φ removal + coalescing), region construction (if/else,
 * loops, short-circuit), and the non-crashing irreducible fallback.
 *
 * These assert on the *pipeline* contract — the region tree shape and the φ-free/de-SSA invariant —
 * which is what codegen and core:api's renderability guard consume. Recompilable-Java parity is
 * measured separately by the JVM oracle (Layer B).
 */
class StructuringTest {

    private fun noPhiRemains(method: IrMethod) {
        for (block in method.blocks) {
            assertTrue(
                block.instructions.none { it is PhiInstruction },
                "φ must not remain after out-of-SSA on B${block.id}",
            )
            assertNull(block[PipelineAttrs.PHI_LIST], "PHI_LIST must be cleared on B${block.id}")
        }
    }

    /** Invariant enforced with codegen/core:api: a method with a region is fully de-SSA'd. */
    private fun assertStructuredInvariant(method: IrMethod) {
        if (method.region != null) {
            noPhiRemains(method)
            assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "regioned method must be flagged structured")
        }
    }

    private fun children(region: Region?): List<Any> =
        (region as? SequenceRegion)?.children?.toList() ?: emptyList()

    private fun firstOfType(region: Region?): IfRegion? =
        children(region).filterIsInstance<IfRegion>().firstOrNull()

    private fun firstLoop(region: Region?): LoopRegion? =
        children(region).filterIsInstance<LoopRegion>().firstOrNull()

    // ---- if / else ----------------------------------------------------------

    @Test
    fun ifElseDiamondBuildsIfRegionAndCoalescesJoin() {
        // v0 = 0; if (v0 == 0) v0 = 20 else v0 = 10; return v0
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
                Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4),
                Insn(Opcode.CONST, 2, intArrayOf(0), literal = 10),
                Insn(Opcode.GOTO, 3, target = 5),
                Insn(Opcode.CONST, 4, intArrayOf(0), literal = 20),
                Insn(Opcode.RETURN, 5, intArrayOf(0)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        val region = method.region
        assertNotNull(region, "reducible if/else must be structured")
        val ifr = firstOfType(region)
        assertNotNull(ifr, "an IfRegion must be present")
        assertNotNull(ifr.elseRegion, "both arms present ⇒ if/else")
        assertTrue(ifr.condition is Condition.Compare)

        // The join value coalesced: the two branch consts + the return read one LocalVar.
        val returnValue = method.blocks
            .flatMap { it.instructions }
            .first { it.opcode == com.jadxmp.ir.insn.IrOpcode.RETURN }
            .getArg(0) as com.jadxmp.ir.insn.RegisterOperand
        val joinLocal = returnValue.ssaValue?.localVar
        assertNotNull(joinLocal, "the merged variable must have a LocalVar")
        assertTrue(joinLocal.ssaValues.size >= 3, "both defs + the φ result collapse into one local")
    }

    @Test
    fun ifReturnWithoutElseArm() {
        // if (v0 == 0) return 1; return 2   (both arms return ⇒ no post-merge join)
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
                Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4),
                Insn(Opcode.CONST, 2, intArrayOf(1), literal = 2),
                Insn(Opcode.RETURN, 3, intArrayOf(1)),
                Insn(Opcode.CONST, 4, intArrayOf(1), literal = 1),
                Insn(Opcode.RETURN, 5, intArrayOf(1)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        assertNotNull(method.region)
        val ifr = firstOfType(method.region)
        assertNotNull(ifr, "an IfRegion must be present")
    }

    // ---- loops --------------------------------------------------------------

    @Test
    fun whileLoopBuildsWhileRegion() {
        // v0 = 0; while (v0 != 10) v0 = v0 + 1; return   (test at header)
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0), // preheader
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = 10),
                Insn(Opcode.IF_EQ, 2, intArrayOf(0, 1), target = 6), // header: if v0==10 exit
                Insn(Opcode.ADD_INT_LIT, 3, intArrayOf(0, 0), literal = 1), // body: v0 = v0 + 1
                Insn(Opcode.GOTO, 4, target = 2), // back edge to header
                Insn(Opcode.NOP, 5),
                Insn(Opcode.RETURN_VOID, 6),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        assertNotNull(method.region, "reducible while loop must be structured")
        val loop = firstLoop(method.region)
        assertNotNull(loop, "a LoopRegion must be present")
        assertEquals(LoopKind.WHILE, loop.kind)
        assertNotNull(loop.condition)
    }

    @Test
    fun doWhileLoopBuildsDoWhileRegion() {
        // v0 = 0; do { v0 = v0 + 1; } while (v0 != 10); return  (test at latch)
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0), // preheader
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = 10),
                Insn(Opcode.ADD_INT_LIT, 2, intArrayOf(0, 0), literal = 1), // header/body: v0 = v0 + 1
                Insn(Opcode.IF_NE, 3, intArrayOf(0, 1), target = 2), // latch: if v0 != 10 loop
                Insn(Opcode.RETURN_VOID, 4),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        assertNotNull(method.region, "reducible do/while loop must be structured")
        val loop = firstLoop(method.region)
        assertNotNull(loop, "a LoopRegion must be present")
        assertEquals(LoopKind.DO_WHILE, loop.kind)
    }

    @Test
    fun countingForLoopStructuresAsLoop() {
        // for (i = 0; i < n; i++) sum += i;   (n and sum passed via registers; rendered as a while today)
        // static void m(int n): v0 = 0 (i); loop: if (v0 >= v1) exit; ...; v0 = v0 + 1; goto loop
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0), // i = 0
                Insn(Opcode.IF_GE, 1, intArrayOf(0, 1), target = 5), // header: if i >= n exit
                Insn(Opcode.ADD_INT_LIT, 2, intArrayOf(0, 0), literal = 1), // i = i + 1
                Insn(Opcode.GOTO, 3, target = 1),
                Insn(Opcode.NOP, 4),
                Insn(Opcode.RETURN_VOID, 5),
            ),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.INT))
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        assertNotNull(method.region)
        val loop = firstLoop(method.region)
        assertNotNull(loop)
        assertTrue(loop.kind == LoopKind.WHILE || loop.kind == LoopKind.FOR)
    }

    @Test
    fun nestedLoopsStructureRecursively() {
        // outer: for(;;) { inner: while(v0 != 3) v0++; if (v1 != 0) break-ish via exit } — simplified:
        // v0 loop nested inside v1 loop, both count to a bound and exit.
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(2), literal = 0), // v2 = 0 outer counter
                Insn(Opcode.IF_EQZ, 1, intArrayOf(2), target = 8), // outer header: if v2==0 exit (runs once here)
                Insn(Opcode.CONST, 2, intArrayOf(0), literal = 0), // v0 = 0 inner counter
                Insn(Opcode.IF_NEZ, 3, intArrayOf(0), target = 6), // inner header: if v0!=0 exit inner
                Insn(Opcode.ADD_INT_LIT, 4, intArrayOf(0, 0), literal = 1), // v0++
                Insn(Opcode.GOTO, 5, target = 3), // inner back edge
                Insn(Opcode.ADD_INT_LIT, 6, intArrayOf(2, 2), literal = -1), // v2--
                Insn(Opcode.GOTO, 7, target = 1), // outer back edge
                Insn(Opcode.RETURN_VOID, 8),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        // Either fully structured (nested loops) or safely bailed — never wrong.
        val region = method.region
        if (region != null) {
            val outer = firstLoop(region)
            assertNotNull(outer, "outer loop present")
            // an inner LoopRegion must appear somewhere in the outer body
            assertTrue(containsLoop(outer.body), "inner loop must nest inside the outer loop")
        }
    }

    private fun containsLoop(region: Region): Boolean = when (region) {
        is LoopRegion -> true
        is SequenceRegion -> region.children.any { it is LoopRegion || (it is Region && containsLoop(it)) }
        is IfRegion -> containsLoop(region.thenRegion) || (region.elseRegion?.let { containsLoop(it) } ?: false)
        else -> false
    }

    @Test
    fun infiniteLoopBuildsInfiniteRegionWithoutRecursing() {
        // v0 = 0; while (true) { v0 = v0 + 1; }   (no conditional exit — body starts at the header)
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0), // preheader
                Insn(Opcode.ADD_INT_LIT, 1, intArrayOf(0, 0), literal = 1), // header/body: v0 = v0 + 1
                Insn(Opcode.GOTO, 2, target = 1), // back edge
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method) // must terminate (no infinite recursion) and not crash

        assertStructuredInvariant(method)
        assertNotNull(method.region, "infinite loop must structure as while(true)")
        val loop = firstLoop(method.region)
        assertNotNull(loop)
        assertEquals(LoopKind.INFINITE, loop.kind)
        assertNull(loop.condition, "while(true) has no condition")
    }

    // ---- short-circuit ------------------------------------------------------

    @Test
    fun shortCircuitFoldsIntoJunctionCondition() {
        // Source `if (v0 != 0 && v1 != 0) return 1; return 0`. javac emits the guard as
        //   if v0==0 goto else ; if v1==0 goto else ; then
        // Both condition blocks share the `else` target on their branch-taken side, so structuring
        // recovers ONE folded boolean expression (a `||` of the two `==0` tests, whose negation is the
        // source `&&`) instead of two nested ifs — the point of short-circuit recovery.
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(0), target = 5), // if v0==0 goto else
                Insn(Opcode.IF_EQZ, 1, intArrayOf(1), target = 5), // if v1==0 goto else
                Insn(Opcode.CONST, 2, intArrayOf(0), literal = 1), // then: r0 = 1
                Insn(Opcode.RETURN, 3, intArrayOf(0)),
                Insn(Opcode.NOP, 4),
                Insn(Opcode.CONST, 5, intArrayOf(0), literal = 0), // else: r0 = 0
                Insn(Opcode.RETURN, 6, intArrayOf(0)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        val region = method.region
        assertNotNull(region, "short-circuit if must structure")
        val ifr = firstOfType(region)
        assertNotNull(ifr, "an IfRegion must be present")
        // A single folded junction of the two comparisons — not a nested if.
        val terms = when (val c = ifr.condition) {
            is Condition.And -> c.terms
            is Condition.Or -> c.terms
            else -> null
        }
        assertNotNull(terms, "two chained conditions fold into one && / || junction: ${ifr.condition}")
        assertEquals(2, terms.size, "exactly two comparison terms")
        assertTrue(terms.all { it is Condition.Compare }, "each term is a relational comparison")
    }

    // ---- dominance-frontier out-block (findOutBlock / isGenuineMerge) --------

    @Test
    fun earlyReturnDiamondReconvergesViaDominanceFrontierOutBlock() {
        // A diamond whose THEN-arm has an early `return` sub-path, so the two arms' reconvergence M is
        // invisible to post-dominance (ipostdom(cond) collapses to the method exit). The merge is recovered
        // by findOutBlock — the DOMINANCE-FRONTIER INTERSECTION of the arms — and proven a genuine single
        // merge (every non-terminal path reaches it, no external predecessor) by isGenuineMerge. M is placed
        // ONCE as the follow (it is itself a further branch, so non-duplicable) and the method FULLY structures.
        //
        //   B0: if (a==0) goto B2 else B1        (cond; ipostdom == exit because B3 returns early)
        //   B1: if (b==0) goto B3 else B4        (then-arm: nested if with an early-return sub-path)
        //   B4: goto M                           (then-arm continues to the merge)
        //   B3: return                           (early terminal return — hides the merge from post-dom)
        //   B2: goto M                           (else-arm reaches the same merge)
        //   M : if (c==0) ... (return | return)  (the genuine merge; a branch, so NOT a duplicable tail)
        val reader = FakeCodeReader(
            3, // v0 = a (p0), v1 = b (p1), v2 = c (p2)
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(0), target = 4), // B0: a==0 -> B2(off4); fall -> B1(off1)
                Insn(Opcode.IF_EQZ, 1, intArrayOf(1), target = 3), // B1: b==0 -> B3(off3); fall -> B4(off2)
                Insn(Opcode.GOTO, 2, target = 5), // B4: -> M(off5)
                Insn(Opcode.RETURN_VOID, 3), // B3: early return
                Insn(Opcode.GOTO, 4, target = 5), // B2: -> M(off5)
                Insn(Opcode.IF_EQZ, 5, intArrayOf(2), target = 7), // M: c==0 -> B6(off7); fall -> B5(off6)
                Insn(Opcode.RETURN_VOID, 6), // B5
                Insn(Opcode.RETURN_VOID, 7), // B6
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        val region = method.region
        assertNotNull(region, "the early-return diamond must structure via the DF out-block merge")
        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "every path reaches the recovered merge")
        // The merge M and both branches (B0, B1) are IfRegions — three in total, none dropped or duplicated.
        assertTrue(countIfRegions(region) >= 3, "outer if, nested if, and the recovered merge if all present")
        // No un-consumed branch may leak as a bare statement (the rule-4 net).
        for (insn in leaves(region)) {
            if (insn is IfInstruction && !insn.contains(AttrFlag.DONT_GENERATE)) {
                error("an un-consumed IF leaked as a bare statement")
            }
        }
        // All three returns (the early return + the merge's two arms) survive.
        assertTrue(
            leaves(region).count { it.opcode == IrOpcode.RETURN } >= 3,
            "the early return and both merge-arm returns are all preserved",
        )
    }

    @Test
    fun ambiguousDoubleMergeDiamondHasNoUniqueOutBlockAndBailsHonestly() {
        // A diamond whose two arms EACH fan out to the SAME pair of downstream branches M and N, so the
        // dominance-frontier intersection holds TWO genuine merge candidates (M and N both satisfy every
        // clause of isGenuineMerge). There is no UNIQUE genuine merge, so findOutBlock must return null
        // rather than guess one — and with no enclosing follow to rescue it the second arm revisits an
        // already-placed, non-duplicable branch, so structuring bails honestly (region == null) instead of
        // dropping the other reconvergence. This proves the "unique-or-bail" gate: picking either M or N as
        // the follow would misplace the code the other merge dominates (rule 4).
        //
        //   B0: if -> L | R
        //   L : if -> M | N        R : if -> M | N        (both arms reach BOTH merges)
        //   M : if -> P | Q        N : if -> P | Q        (M, N are branches ⇒ non-duplicable)
        //   P : return             Q : return
        // (trampoline GOTO blocks bridge the non-adjacent edges the two-way IF fall-through can't express.)
        val reader = FakeCodeReader(
            5, // v0=B0, v1=L, v2=R, v3=M, v4=N conditions (all read-only args ⇒ no φ)
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(0), target = 3), // B0: ==0 -> R(off3); fall -> L(off1)
                Insn(Opcode.IF_EQZ, 1, intArrayOf(1), target = 8), // L : ==0 -> N(off8); fall -> Lm(off2)
                Insn(Opcode.GOTO, 2, target = 6), // Lm: L's fall path -> M(off6)
                Insn(Opcode.IF_EQZ, 3, intArrayOf(2), target = 5), // R : ==0 -> Rn(off5); fall -> Rm(off4)
                Insn(Opcode.GOTO, 4, target = 6), // Rm: R's fall path -> M(off6)
                Insn(Opcode.GOTO, 5, target = 8), // Rn: R's ==0 path -> N(off8)
                Insn(Opcode.IF_EQZ, 6, intArrayOf(3), target = 11), // M : ==0 -> Q(off11); fall -> Mp(off7)
                Insn(Opcode.GOTO, 7, target = 10), // Mp: M's fall path -> P(off10)
                Insn(Opcode.IF_EQZ, 8, intArrayOf(4), target = 11), // N : ==0 -> Q(off11); fall -> Np(off9)
                Insn(Opcode.GOTO, 9, target = 10), // Np: N's fall path -> P(off10)
                Insn(Opcode.RETURN_VOID, 10), // P
                Insn(Opcode.RETURN_VOID, 11), // Q
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            argTypes = listOf(IrType.INT, IrType.INT, IrType.INT, IrType.INT, IrType.INT),
        )
        TestPipeline.structured(method)

        // Two genuine out-block candidates ⇒ no unique merge ⇒ findOutBlock null ⇒ honest bail.
        assertNull(method.region, "an ambiguous double-merge diamond has no unique out-block and must bail")
        assertNull(method[PipelineAttrs.FULLY_STRUCTURED], "a bailed method is not flagged structured")
    }

    // ---- irreducible fallback ----------------------------------------------

    @Test
    fun irreducibleGraphIsFlaggedNotCrashed() {
        // Two entries into a two-node loop (multi-entry / irreducible):
        //   B0: if (v0==0) goto L2 else fallthrough L1
        //   L1: ... goto L2
        //   L2: ... goto L1     (L1<->L2 with entries from both B0 arms)
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(0), target = 3), // B0 -> L2 (off3) or fall to L1 (off1)
                Insn(Opcode.ADD_INT_LIT, 1, intArrayOf(0, 0), literal = 1), // L1
                Insn(Opcode.GOTO, 2, target = 3), // L1 -> L2
                Insn(Opcode.ADD_INT_LIT, 3, intArrayOf(0, 0), literal = 1), // L2
                Insn(Opcode.IF_EQZ, 4, intArrayOf(0), target = 1), // L2 -> L1 (back) or exit
                Insn(Opcode.RETURN_VOID, 5),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        // Must not throw.
        TestPipeline.structured(method)
        // Irreducible ⇒ left unstructured; the guard/invariant holds trivially.
        assertNull(method.region, "irreducible graph must be left unstructured (region == null)")
        assertNull(method[PipelineAttrs.FULLY_STRUCTURED], "not flagged structured")
    }

    // ---- straight-line still structures ------------------------------------

    @Test
    fun straightLineMethodStillGetsRegionAndNoPhi() {
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 2),
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = 3),
                Insn(Opcode.ADD_INT, 2, intArrayOf(0, 0, 1)),
                Insn(Opcode.RETURN, 3, intArrayOf(0)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.structured(method)
        assertStructuredInvariant(method)
        assertNotNull(method.region)
    }

    // ---- break / continue (the must-fix: these edges must NOT vanish) -------

    @Test
    fun whileLoopWithMidBodyBreak() {
        // i = 0; while (i != 3) { if (i == 1) break; i = i + 1; } return;
        // Original terminates (breaks at i==1). A dropped break edge would infinite-loop.
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = 3),
                Insn(Opcode.IF_EQ, 2, intArrayOf(0, 1), target = 8), // header: if i==3 exit
                Insn(Opcode.CONST, 3, intArrayOf(2), literal = 1),
                Insn(Opcode.IF_EQ, 4, intArrayOf(0, 2), target = 8), // if i==1 break (to follow)
                Insn(Opcode.ADD_INT_LIT, 5, intArrayOf(0, 0), literal = 1), // i = i + 1
                Insn(Opcode.GOTO, 6, target = 2), // back edge
                Insn(Opcode.NOP, 7),
                Insn(Opcode.RETURN_VOID, 8),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        val region = method.region
        assertNotNull(region, "while+break must structure (the break edge is a break, not dropped)")
        val loop = firstLoop(region)
        assertNotNull(loop)
        assertEquals(LoopKind.WHILE, loop.kind)
        assertTrue(hasOpcode(loop.body, IrOpcode.BREAK), "the break edge must be emitted as break;")
        assertFalse(hasOpcode(loop.body, IrOpcode.CONTINUE))
    }

    @Test
    fun whileLoopWithMidBodyContinue() {
        // i = 0; while (i != 5) { i = i + 1; if (i == 3) continue; i = i + 1; } return;
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = 5),
                Insn(Opcode.IF_EQ, 2, intArrayOf(0, 1), target = 9), // header: if i==5 exit
                Insn(Opcode.ADD_INT_LIT, 3, intArrayOf(0, 0), literal = 1), // i = i + 1
                Insn(Opcode.CONST, 4, intArrayOf(2), literal = 3),
                Insn(Opcode.IF_EQ, 5, intArrayOf(0, 2), target = 2), // if i==3 continue (to header)
                Insn(Opcode.ADD_INT_LIT, 6, intArrayOf(0, 0), literal = 1), // i = i + 1 (skipped by continue)
                Insn(Opcode.GOTO, 7, target = 2),
                Insn(Opcode.NOP, 8),
                Insn(Opcode.RETURN_VOID, 9),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        val region = method.region
        assertNotNull(region, "while+continue must structure")
        val loop = firstLoop(region)
        assertNotNull(loop)
        assertEquals(LoopKind.WHILE, loop.kind)
        assertTrue(hasOpcode(loop.body, IrOpcode.CONTINUE), "the continue edge must be emitted as continue;")
    }

    @Test
    fun doWhileLoopWithBreak() {
        // i = 0; do { if (i == 1) break; i = i + 1; } while (i != 3); return;
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = 3),
                Insn(Opcode.CONST, 2, intArrayOf(2), literal = 1), // header (body start)
                Insn(Opcode.IF_EQ, 3, intArrayOf(0, 2), target = 6), // if i==1 break (to follow off6)
                Insn(Opcode.ADD_INT_LIT, 4, intArrayOf(0, 0), literal = 1), // i = i + 1
                Insn(Opcode.IF_NE, 5, intArrayOf(0, 1), target = 2), // latch: if i!=3 loop, else fall to off6
                Insn(Opcode.RETURN_VOID, 6),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        val region = method.region
        assertNotNull(region, "do-while+break must structure")
        val loop = firstLoop(region)
        assertNotNull(loop)
        // A break atop a do-while body may legitimately rotate to an equivalent `while` header; either
        // shape is correct so long as the break is emitted (the loop terminates, not diverges).
        assertTrue(loop.kind == LoopKind.DO_WHILE || loop.kind == LoopKind.WHILE)
        assertTrue(hasOpcode(loop.body, IrOpcode.BREAK), "the break edge must be emitted as break;")
    }

    @Test
    fun nestedLoopWithInnerBreak() {
        // outer while over v2, inner while over v0 with an inner break.
        val reader = FakeCodeReader(
            4,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(2), literal = 0), // v2 = 0 (outer)
                Insn(Opcode.CONST, 1, intArrayOf(3), literal = 2), // v3 = 2 (outer bound)
                Insn(Opcode.IF_EQ, 2, intArrayOf(2, 3), target = 11), // outer header: if v2==2 exit
                Insn(Opcode.CONST, 3, intArrayOf(0), literal = 0), // v0 = 0 (inner)
                Insn(Opcode.CONST, 4, intArrayOf(1), literal = 5), // v1 = 5 (inner bound)
                Insn(Opcode.IF_EQ, 5, intArrayOf(0, 1), target = 9), // inner header: if v0==5 exit inner
                Insn(Opcode.IF_EQ, 6, intArrayOf(0, 3), target = 9), // if v0==2 break inner (to inner follow off9)
                Insn(Opcode.ADD_INT_LIT, 7, intArrayOf(0, 0), literal = 1), // v0 = v0 + 1
                Insn(Opcode.GOTO, 8, target = 5), // inner back edge
                Insn(Opcode.ADD_INT_LIT, 9, intArrayOf(2, 2), literal = 1), // v2 = v2 + 1 (inner follow)
                Insn(Opcode.GOTO, 10, target = 2), // outer back edge
                Insn(Opcode.RETURN_VOID, 11),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        val region = method.region
        if (region != null) {
            val outer = firstLoop(region)
            assertNotNull(outer, "outer loop present")
            assertTrue(containsLoop(outer.body), "inner loop nests inside the outer loop")
            assertTrue(hasOpcode(outer.body, IrOpcode.BREAK), "inner break must be emitted as break;")
        }
    }

    @Test
    fun multiExitLoopWithTwoReturnsStructuresInPlace() {
        // A loop that exits via the header condition (i==5 -> return) AND an in-loop `if (i==0) return;`
        // has two exit edges to two RETURN blocks. Both are TERMINAL returns, so multi-exit collapse pulls
        // the in-loop return in-place and keeps the header-exit return as the single follow — structuring
        // exactly like jadx: `while (i != 5) { if (i == 0) return; i++; } return;`. (This shape used to bail;
        // it now structures because both exits are terminal — a genuinely-divergent second follow still
        // bails via the loopBody soundness check, see multiExitLoopWithDivergentFollowBails.)
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = 5),
                Insn(Opcode.IF_EQ, 2, intArrayOf(0, 1), target = 7), // header: i==5 -> return (off7)
                Insn(Opcode.IF_EQZ, 3, intArrayOf(0), target = 8), // in-loop: i==0 -> return (off8)
                Insn(Opcode.ADD_INT_LIT, 4, intArrayOf(0, 0), literal = 1),
                Insn(Opcode.GOTO, 5, target = 2),
                Insn(Opcode.NOP, 6),
                Insn(Opcode.RETURN_VOID, 7), // the follow (after the loop)
                Insn(Opcode.RETURN_VOID, 8), // the in-loop return (pulled in-place)
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)
        assertStructuredInvariant(method)
        val region = method.region
        assertNotNull(region, "two TERMINAL return exits collapse to a single follow — this structures")
        val loop = firstLoop(region)
        assertNotNull(loop, "the while loop is reconstructed")
        // Both returns render (one in-loop, one as the follow); no un-consumed branch leaks.
        assertTrue(leaves(region).count { it.opcode == IrOpcode.RETURN } >= 2, "both returns are preserved")
        for (insn in leaves(region)) {
            if (insn is IfInstruction && !insn.contains(AttrFlag.DONT_GENERATE)) {
                error("an un-consumed IF leaked as a bare statement")
            }
        }
    }

    @Test
    fun multiExitLoopWithSecondDivergentExitFailsSoundnessAndBails() {
        // A `throw` side-exit makes ipostdom(header) the method exit, so multiExitFollow IS consulted and
        // returns the header's exit edge as the follow (driving the multi-exit path). But there is ALSO a
        // second exit to a divergent, non-converging block `B` (an infinite self-loop that never reaches the
        // follow or the method exit), so after tail-inclusion the body still has TWO exit targets. The
        // loopBody single-follow SOUNDNESS check (`exits == {follow}`) therefore rejects the collapse via its
        // `else base` branch, and the method bails honestly (region null) rather than mis-structure — rule 4.
        val barRef = FakeMethodRef("Lcom/example/Foo;", "bar", "V", emptyList())
        val reader = FakeCodeReader(
            3, // v0 = i, v1 = n (p0), v2 = e (p1)
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0), // i = 0
                Insn(Opcode.IF_GE, 1, intArrayOf(0, 1), target = 6), // header: if (i >= n) -> follow (off6)
                Insn(Opcode.IF_EQZ, 2, intArrayOf(0), target = 7), // if (i == 0) -> throw (off7)
                Insn(Opcode.IF_LTZ, 3, intArrayOf(0), target = 8), // if (i < 0) -> B (off8, divergent)
                Insn(Opcode.ADD_INT_LIT, 4, intArrayOf(0, 0), literal = 1), // i++
                Insn(Opcode.GOTO, 5, target = 1), // back edge
                Insn(Opcode.RETURN_VOID, 6), // follow (header exit)
                Insn(Opcode.THROW, 7, intArrayOf(2)), // throw e (terminal side-exit → pulled in-place)
                Insn(Opcode.INVOKE_STATIC, 8, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = barRef),
                Insn(Opcode.GOTO, 9, target = 8), // B loops on itself (never reaches follow/exit)
            ),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.INT, IrType.objectType("java.lang.RuntimeException")))
        TestPipeline.structured(method)
        assertNull(method.region, "a second divergent exit fails the single-follow soundness check ⇒ honest bail")
        assertNull(method[PipelineAttrs.FULLY_STRUCTURED])
    }

    // ---- adversarial cases flagged by review -------------------------------

    @Test
    fun sideEffectingShortCircuitContinuationNotFolded() {
        // `if (a) { if (foo()) ... }` — the second condition block has a side-effecting call before its
        // `if`, so it must NOT fold into `a && foo()` (that would reorder/duplicate the effect). Expect a
        // plain first condition with a nested if, not a junction.
        val fooRef = FakeMethodRef("Lcom/example/Foo;", "foo", "Z", emptyList())
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(0), target = 8), // B0: if a==0 goto off8 (return 3)
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = fooRef),
                Insn(Opcode.MOVE_RESULT, 2, intArrayOf(1)),
                Insn(Opcode.IF_EQZ, 3, intArrayOf(1), target = 6), // B1: if foo()==0 goto off6 (return 2)
                Insn(Opcode.CONST, 4, intArrayOf(2), literal = 1), // then-then
                Insn(Opcode.RETURN, 5, intArrayOf(2)),
                Insn(Opcode.CONST, 6, intArrayOf(2), literal = 2), // then-else (off6)
                Insn(Opcode.RETURN, 7, intArrayOf(2)),
                Insn(Opcode.CONST, 8, intArrayOf(2), literal = 3), // B0-else (off8)
                Insn(Opcode.RETURN, 9, intArrayOf(2)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        val region = method.region
        assertNotNull(region)
        val outerIf = firstOfType(region)
        assertNotNull(outerIf, "top-level if present")
        assertTrue(
            outerIf.condition is Condition.Compare,
            "must NOT fold a side-effecting continuation into &&/||: ${outerIf.condition}",
        )
        assertTrue(countIfRegions(region) >= 2, "the side-effecting second test stays a nested if")
    }

    @Test
    fun swapLoopCoalescesNonLossily() {
        // Parallel-swap in a loop: t = a; a = b; b = t. SSA from register code stays conventional
        // (interference-free), so out-of-SSA coalesces it WITHOUT dropping any move and WITHOUT a wrong
        // merge. Assert φ-free and every MOVE preserved (non-lossy) — the swap semantics are intact.
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 1), // a = 1
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = 2), // b = 2
                Insn(Opcode.CONST, 2, intArrayOf(2), literal = 0), // counter seed reuse (v2)
                Insn(Opcode.MOVE, 3, intArrayOf(2, 0)), // (H) t = a
                Insn(Opcode.MOVE, 4, intArrayOf(0, 1)), // a = b
                Insn(Opcode.MOVE, 5, intArrayOf(1, 2)), // b = t
                Insn(Opcode.IF_EQZ, 6, intArrayOf(0), target = 3), // loop back to H (offset 3) or exit
                Insn(Opcode.RETURN_VOID, 7),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        // No φ may remain regardless of coalesce/copy path.
        for (block in method.blocks) {
            assertTrue(block.instructions.none { it is PhiInstruction }, "φ removed on B${block.id}")
        }
        // All three moves survive (no silent code loss during SSA destruction).
        val moves = method.blocks.flatMap { it.instructions }.count { it.opcode == IrOpcode.MOVE }
        assertTrue(moves >= 3, "the three swap moves must be preserved (non-lossy), found $moves")
    }

    @Test
    fun whileLoopWithWorkBeforeBreak() {
        // j = 0; i = 0; while (i != 3) { if (i == 1) { j = 99; break; } i = i + 1; } return j;
        // The `j = 99` work-block sits between the if and the break and falls outside the natural loop;
        // break-tail inclusion must pull it into the body so this structures (instead of bailing). `j` is
        // RETURNED (live) so the work store is not dead-code-eliminated — the block genuinely carries work.
        val reader = FakeCodeReader(
            4,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(3), literal = 0), // j = 0
                Insn(Opcode.CONST, 1, intArrayOf(0), literal = 0), // i = 0
                Insn(Opcode.CONST, 2, intArrayOf(1), literal = 3),
                Insn(Opcode.IF_EQ, 3, intArrayOf(0, 1), target = 10), // header: if i==3 exit
                Insn(Opcode.CONST, 4, intArrayOf(2), literal = 1),
                Insn(Opcode.IF_NE, 5, intArrayOf(0, 2), target = 8), // if i!=1 skip the work+break
                Insn(Opcode.CONST, 6, intArrayOf(3), literal = 99), // work: j = 99
                Insn(Opcode.GOTO, 7, target = 10), // break -> follow
                Insn(Opcode.ADD_INT_LIT, 8, intArrayOf(0, 0), literal = 1), // i = i + 1
                Insn(Opcode.GOTO, 9, target = 3), // back edge
                Insn(Opcode.RETURN, 10, intArrayOf(3)), // return j (keeps the work store live)
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        val region = method.region
        assertNotNull(region, "work-before-break must structure via break-tail inclusion")
        val loop = firstLoop(region)
        assertNotNull(loop)
        assertTrue(hasOpcode(loop.body, IrOpcode.BREAK), "break emitted")
        // The live work store `j = 99` must be inside the loop body, not dropped or bailed. Assert the
        // SPECIFIC literal (99) so another CONST (the `if` literal 1) cannot vacuously satisfy this.
        assertTrue(
            leaves(loop.body).any { insn ->
                insn.opcode == IrOpcode.CONST && insn.argCount > 0 &&
                    insn.getArg(0).let { a -> a is com.jadxmp.ir.insn.LiteralOperand && a.value == 99L }
            },
            "the live work statement (j = 99) before the break is preserved in the body",
        )
    }

    @Test
    fun multiExitLoopCollapsesToSingleFollowWithInPlaceThrowAndBreak() {
        // void f(int n, int m, RuntimeException e) {
        //   int i = 0;
        //   while (i < n) { if (i == m) throw e; if (i > m) break; i = i + 1; }
        //   return;                       // the single follow (reached by header-exit AND break)
        // }
        // The loop has THREE exit targets — the normal follow (return), a `break`, and a terminal `throw` —
        // so ipostdom(header) is the method exit and the old single-follow rule bailed ("multi-exit loop").
        // Multi-exit collapse must place ONE follow, render the throw in-place and the break as `break`.
        val reader = FakeCodeReader(
            4, // v0 = i, v1 = n (p0), v2 = m (p1), v3 = e (p2)
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0), // i = 0
                Insn(Opcode.IF_GE, 1, intArrayOf(0, 1), target = 8), // header: if (i >= n) -> follow
                Insn(Opcode.IF_EQ, 2, intArrayOf(0, 2), target = 7), // if (i == m) -> throw
                Insn(Opcode.IF_GT, 3, intArrayOf(0, 2), target = 6), // if (i > m) -> break-tail
                Insn(Opcode.ADD_INT_LIT, 4, intArrayOf(0, 0), literal = 1), // i = i + 1
                Insn(Opcode.GOTO, 5, target = 1), // back edge
                Insn(Opcode.GOTO, 6, target = 8), // break-tail -> follow
                Insn(Opcode.THROW, 7, intArrayOf(3)), // throw e
                Insn(Opcode.RETURN_VOID, 8), // follow
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            argTypes = listOf(IrType.INT, IrType.INT, IrType.objectType("java.lang.RuntimeException")),
        )
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        val region = method.region
        assertNotNull(region, "a multi-exit loop (break + throw + normal follow) must structure")
        val loop = firstLoop(region)
        assertNotNull(loop, "the while loop must be reconstructed")
        assertTrue(hasOpcode(loop.body, IrOpcode.THROW), "the terminal throw renders in-place inside the loop")
        assertTrue(hasOpcode(loop.body, IrOpcode.BREAK), "the break renders as `break`")
        // No un-consumed branch may leak (rule-4 net): a placed block ending in an `if` must be consumed.
        val placedInsns = leaves(region)
        for (insn in placedInsns) {
            if (insn is IfInstruction && !insn.contains(AttrFlag.DONT_GENERATE)) {
                error("an un-consumed IF leaked as a bare statement")
            }
        }
    }

    // ---- switch -------------------------------------------------------------

    @Test
    fun intSwitchBuildsSwitchRegionWithBreaks() {
        // switch (v0) { case 0: r=10; break; case 1: r=20; break; default: r=30; } return r;
        val payload = com.jadxmp.input.SwitchPayload(keys = intArrayOf(0, 1), targets = intArrayOf(3, 5))
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.PACKED_SWITCH, 0, intArrayOf(0), target = 50), // switch v0, payload@50; default=off1
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = 30), // default: r1 = 30
                Insn(Opcode.GOTO, 2, target = 7),
                Insn(Opcode.CONST, 3, intArrayOf(1), literal = 10), // case 0: r1 = 10
                Insn(Opcode.GOTO, 4, target = 7),
                Insn(Opcode.CONST, 5, intArrayOf(1), literal = 20), // case 1: r1 = 20
                Insn(Opcode.GOTO, 6, target = 7),
                Insn(Opcode.RETURN, 7, intArrayOf(1)), // follow
                Insn(Opcode.PACKED_SWITCH_PAYLOAD, 50, payload = payload),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        val region = method.region
        assertNotNull(region, "int switch must structure")
        val sw = firstSwitch(region)
        assertNotNull(sw, "a SwitchRegion must be present")
        assertEquals(2, sw.cases.size, "two explicit cases")
        assertEquals(setOf(0L, 1L), sw.cases.flatMap { it.keys }.toSet())
        assertNotNull(sw.defaultCase, "default present")
        assertTrue(hasOpcode(sw, IrOpcode.BREAK), "case breaks emitted so cases don't fall through")
    }

    @Test
    fun switchWithMultipleKeysPerCase() {
        // switch (v0) { case 0: case 1: r=10; break; default: r=30; }  (two keys share one body)
        val payload = com.jadxmp.input.SwitchPayload(keys = intArrayOf(0, 1), targets = intArrayOf(3, 3))
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.PACKED_SWITCH, 0, intArrayOf(0), target = 50),
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = 30), // default
                Insn(Opcode.GOTO, 2, target = 5),
                Insn(Opcode.CONST, 3, intArrayOf(1), literal = 10), // shared case body (keys 0 and 1)
                Insn(Opcode.GOTO, 4, target = 5),
                Insn(Opcode.RETURN, 5, intArrayOf(1)),
                Insn(Opcode.PACKED_SWITCH_PAYLOAD, 50, payload = payload),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        val sw = firstSwitch(method.region)
        assertNotNull(sw)
        assertEquals(1, sw.cases.size, "keys 0 and 1 collapse into one case")
        assertEquals(setOf(0L, 1L), sw.cases.single().keys.toSet())
    }

    @Test
    fun switchNestedInIfWhoseMergeEqualsSwitchMergeStillEmitsCaseBreaks() {
        // The active-exit-stack precedence guard (Phase-1 review must-fix). A switch nested in an outer `if`
        // whose merge is the SAME block as the switch merge M. The outer if pushes M as an active exit; each
        // case body's `goto M` reaches M as a CHAIN-level break (a switch break-target), not a direct if-arm.
        // If the `block in exitStack` stop were checked BEFORE the loopCtx.follow break, it would fire first
        // and stop plain — dropping every `break` so the cases fall through (recompiles but runs wrong: the
        // nets can't see it — edges recorded as fall-through, every block placed once). The break/continue
        // checks MUST precede the active-exit stop. This asserts all case breaks are still emitted.
        val sink = FakeMethodRef("Lcom/example/Foo;", "sink", "V", emptyList())
        // CONTROL: the bare switch (no if wrapper) — establishes the correct break count.
        val controlPayload = com.jadxmp.input.SwitchPayload(keys = intArrayOf(0, 1), targets = intArrayOf(3, 5))
        val control = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.PACKED_SWITCH, 0, intArrayOf(0), target = 40), // switch(p0); default = fall to 1
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), methodRef = sink), // default body
                Insn(Opcode.GOTO, 2, target = 7),
                Insn(Opcode.INVOKE_STATIC, 3, intArrayOf(), methodRef = sink), // case 0 body
                Insn(Opcode.GOTO, 4, target = 7),
                Insn(Opcode.INVOKE_STATIC, 5, intArrayOf(), methodRef = sink), // case 1 body
                Insn(Opcode.GOTO, 6, target = 7),
                Insn(Opcode.RETURN_VOID, 7), // switch merge
                Insn(Opcode.PACKED_SWITCH_PAYLOAD, 40, payload = controlPayload),
            ),
        )
        val controlMethod = TestPipeline.buildMethod(control, argTypes = listOf(IrType.INT))
        TestPipeline.structured(controlMethod)
        val controlBreaks = leaves(controlMethod.region!!).count { it.opcode == IrOpcode.BREAK }
        assertTrue(controlBreaks > 0, "sanity: the bare switch emits case breaks")

        // BUG SHAPE: the same switch wrapped in `if (p1 != 0) { switch(p0) … }` where the outer if merge
        // == the switch merge M (offset 10). M is on the active-exit stack while the cases build.
        // Packed-switch targets are RELATIVE to the switch offset (3): case bodies at absolute 6 and 8.
        val payload = com.jadxmp.input.SwitchPayload(keys = intArrayOf(0, 1), targets = intArrayOf(3, 5))
        val reader = FakeCodeReader(
            3, // p0 = v1 (switch selector), p1 = v2 (if condition)
            listOf(
                Insn(Opcode.IF_NEZ, 0, intArrayOf(2), target = 3), // if (p1 != 0) goto switch; else fall
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), methodRef = sink), // else body
                Insn(Opcode.GOTO, 2, target = 10), // else -> M
                Insn(Opcode.PACKED_SWITCH, 3, intArrayOf(1), target = 40), // switch(p0); default = fall to 4
                Insn(Opcode.INVOKE_STATIC, 4, intArrayOf(), methodRef = sink), // default body
                Insn(Opcode.GOTO, 5, target = 10), // default -> M
                Insn(Opcode.INVOKE_STATIC, 6, intArrayOf(), methodRef = sink), // case 0 body
                Insn(Opcode.GOTO, 7, target = 10), // case 0 -> M
                Insn(Opcode.INVOKE_STATIC, 8, intArrayOf(), methodRef = sink), // case 1 body
                Insn(Opcode.GOTO, 9, target = 10), // case 1 -> M
                Insn(Opcode.RETURN_VOID, 10), // M — switch merge == outer if merge
                Insn(Opcode.PACKED_SWITCH_PAYLOAD, 40, payload = payload),
            ),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.INT, IrType.INT))
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "the switch-in-if must fully structure")
        val breaks = leaves(method.region!!).count { it.opcode == IrOpcode.BREAK }
        assertEquals(
            controlBreaks,
            breaks,
            "case breaks must NOT be dropped when the switch merge is also an enclosing active exit " +
                "(got $breaks, control $controlBreaks — 0 would mean fall-through)",
        )
    }

    // ---- try / catch --------------------------------------------------------

    @Test
    fun simpleTryCatchBuildsTryCatchRegion() {
        // String v1 = "result"; try { v1 = call(); } catch (Exception v4) { v1 = v4; } return v1;
        val callRef = FakeMethodRef("Ltest/C;", "call", "Ljava/lang/String;", emptyList())
        val reader = FakeCodeReader(
            5,
            listOf(
                Insn(Opcode.CONST_STRING, 0, intArrayOf(1), stringValue = "result"),
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = callRef),
                Insn(Opcode.MOVE_RESULT, 2, intArrayOf(1)), // v1 = call()
                Insn(Opcode.RETURN, 3, intArrayOf(1)), // return v1 (follow)
                Insn(Opcode.MOVE_EXCEPTION, 4, intArrayOf(4)), // catch: v4 = exception
                Insn(Opcode.MOVE_OBJECT, 5, intArrayOf(1, 4)), // v1 = v4
                Insn(Opcode.GOTO, 6, target = 3),
            ),
            tries = listOf(
                FakeTryBlock(1, 2, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(4), -1)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.objectType("java.lang.String"))
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        val region = method.region
        assertNotNull(region, "a simple try/catch must structure")
        val tryRegion = firstTry(region)
        assertNotNull(tryRegion, "a TryCatchRegion must be present")
        assertEquals(1, tryRegion.catches.size, "one catch clause")
        assertEquals(
            listOf(IrType.objectType("java.lang.Exception")),
            tryRegion.catches.single().exceptionTypes,
        )
        assertNotNull(tryRegion.catches.single().exceptionVar, "the caught exception is bound to a var")
        assertNull(tryRegion.finallyRegion, "no finally")
    }

    @Test
    fun multiCatchBuildsTwoClauses() {
        // try { call(); } catch (IllegalStateException e) { } catch (IOException e) { }
        val callRef = FakeMethodRef("Ltest/C;", "call", "V", emptyList())
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = callRef),
                Insn(Opcode.RETURN_VOID, 1), // follow
                Insn(Opcode.MOVE_EXCEPTION, 2, intArrayOf(2)), // handler A
                Insn(Opcode.GOTO, 3, target = 1),
                Insn(Opcode.MOVE_EXCEPTION, 4, intArrayOf(2)), // handler B
                Insn(Opcode.GOTO, 5, target = 1),
            ),
            tries = listOf(
                FakeTryBlock(
                    0, 0,
                    FakeCatchHandler(
                        listOf("Ljava/lang/IllegalStateException;", "Ljava/io/IOException;"),
                        listOf(2, 4),
                        -1,
                    ),
                ),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        assertStructuredInvariant(method)
        val tryRegion = firstTry(method.region)
        assertNotNull(tryRegion, "multi-catch must structure")
        assertEquals(2, tryRegion.catches.size, "two catch clauses")
    }

    @Test
    fun tryWithRethrowingCatchAllRendersAsExplicitCatch() {
        // A catch-all handler that re-throws (`move-exception; throw`) that cannot be factored into a
        // `finally {}` renders FAITHFULLY as `catch (Throwable e) { throw e; }` — a 1:1 transcription of
        // the bytecode handler (jadx does the same when it cannot extract the finally). It structures
        // (not a bail) and preserves the rethrow.
        val callRef = FakeMethodRef("Ltest/C;", "call", "V", emptyList())
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = callRef),
                Insn(Opcode.RETURN_VOID, 1),
                Insn(Opcode.MOVE_EXCEPTION, 2, intArrayOf(1)), // catch-all
                Insn(Opcode.THROW, 3, intArrayOf(1)), // re-throw
            ),
            tries = listOf(
                FakeTryBlock(0, 0, FakeCatchHandler(emptyList(), emptyList(), 2)),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "re-throwing catch-all structures")
        assertNotNull(method.region)
        assertNotNull(
            method.blocks.flatMap { it.instructions }.firstOrNull { it.opcode == IrOpcode.THROW },
            "the rethrow is emitted (not hidden as a finally would)",
        )
    }

    // ---- helpers ------------------------------------------------------------

    private fun firstSwitch(region: Region?): com.jadxmp.ir.region.SwitchRegion? =
        children(region).filterIsInstance<com.jadxmp.ir.region.SwitchRegion>().firstOrNull()

    private fun firstTry(region: Region?): com.jadxmp.ir.region.TryCatchRegion? =
        children(region).filterIsInstance<com.jadxmp.ir.region.TryCatchRegion>().firstOrNull()

    private fun leaves(region: Region): List<Instruction> {
        val out = ArrayList<Instruction>()
        fun walk(c: IrContainer) {
            when (c) {
                is BasicBlock -> out.addAll(c.instructions)
                is IfRegion -> { walk(c.thenRegion); c.elseRegion?.let { walk(it) } }
                is LoopRegion -> walk(c.body)
                is SequenceRegion -> c.children.forEach { walk(it) }
                is com.jadxmp.ir.region.SwitchRegion -> {
                    c.cases.forEach { walk(it.body) }
                    c.defaultCase?.let { walk(it) }
                }
                is com.jadxmp.ir.region.TryCatchRegion -> {
                    walk(c.tryRegion)
                    c.catches.forEach { walk(it.body) }
                    c.finallyRegion?.let { walk(it) }
                }
                else -> {}
            }
        }
        walk(region)
        return out
    }

    private fun hasOpcode(region: Region, opcode: IrOpcode): Boolean =
        leaves(region).any { it.opcode == opcode }

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

    @Suppress("unused")
    private fun ifInsnOf(block: BasicBlock): IfInstruction? =
        block.instructions.lastOrNull() as? IfInstruction
}
