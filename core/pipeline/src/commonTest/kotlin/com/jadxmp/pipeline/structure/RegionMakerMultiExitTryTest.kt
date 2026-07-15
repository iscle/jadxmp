package com.jadxmp.pipeline.structure

import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrContainer
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
 * Multi-exit try-BODY reconstruction via **tail-bridge absorption** (worklist: trycatch/TestNestedTryCatch4).
 *
 * A protected body can have >1 clean exit not because it has two real follows, but because the compiler
 * leaves an unprotected forwarding block — the bare `goto` just past `try_end` — OUTSIDE the try range even
 * though it sits on the normal path from a body block to the try's single shared follow (`return`). jadx
 * duplicates the trivial shared return into every arm so the body's exits all look terminal; jadxmp keeps
 * the shared return as ONE block, so the body has two clean exits (the return, reached directly from a
 * returning arm, AND the goto). [RegionMaker] now absorbs such an unprotected, exception-free tail
 * bridge — a pure single-successor FORWARDER to a real block, reached only from the body — into the body
 * (exception- and flow-neutral inside `try {}`), collapsing the exits to that single follow.
 *
 * Deliberately NARROW: only a forwarder to a real block is absorbed, never a TERMINAL exit (a `return`/
 * `throw`, whose successor is the method exit). Pulling a terminal exit into the body would disturb the
 * finally-factoring paths (which key off exactly which normal exits carry an inlined cleanup), so a body
 * whose extra exits are terminal — or a genuine multi-follow with distinct/throwing post-try code — bails
 * honestly (rule 4) rather than risk a wrong finally or a stranded follow.
 *
 * The absorption runs ONLY when the body already has >1 exit — a shape that bailed before this landed — so
 * a try that structures today (0/1 exit) is byte-for-byte unchanged.
 */
class RegionMakerMultiExitTryTest {

    private val voidCall = FakeMethodRef("Lcom/example/Foo;", "sink", "V", emptyList())

    private fun flatten(container: IrContainer?, out: MutableList<Region>) {
        if (container is Region) {
            out.add(container)
            when (container) {
                is SequenceRegion -> container.children.forEach { flatten(it, out) }
                is TryCatchRegion -> {
                    flatten(container.tryRegion, out)
                    container.catches.forEach { flatten(it.body, out) }
                    container.finallyRegion?.let { flatten(it, out) }
                }
                is IfRegion -> { flatten(container.thenRegion, out); container.elseRegion?.let { flatten(it, out) } }
                else -> {}
            }
        }
    }

    private inline fun <reified T : Region> firstRegion(method: com.jadxmp.ir.node.IrMethod): T? =
        ArrayList<Region>().also { method.region?.let { r -> flatten(r, it) } }.filterIsInstance<T>().firstOrNull()

    /** Every emitted block occurrence (a duplicated block appears more than once). */
    private fun blockOccurrences(c: IrContainer?, out: MutableList<BasicBlock>) {
        when (c) {
            is BasicBlock -> out.add(c)
            is SequenceRegion -> c.children.forEach { blockOccurrences(it, out) }
            is TryCatchRegion -> {
                blockOccurrences(c.tryRegion, out)
                c.catches.forEach { blockOccurrences(it.body, out) }
                c.finallyRegion?.let { blockOccurrences(it, out) }
            }
            is IfRegion -> { blockOccurrences(c.thenRegion, out); c.elseRegion?.let { blockOccurrences(it, out) } }
            else -> {}
        }
    }

    private fun emitted(method: com.jadxmp.ir.node.IrMethod): List<BasicBlock> =
        ArrayList<BasicBlock>().also { blockOccurrences(method.region, it) }

    /** Blocks anywhere under [c] (recurses through the try body + catch + finally). */
    private fun deepBlocks(c: IrContainer?): List<BasicBlock> = ArrayList<BasicBlock>().also { blockOccurrences(c, it) }

    private fun liveOpcodeCount(method: com.jadxmp.ir.node.IrMethod, op: IrOpcode): Int =
        emitted(method).sumOf { b -> b.instructions.count { it.opcode == op && !it.contains(AttrFlag.DONT_GENERATE) } }

    /**
     * The deliberate narrowness (rule 4): a protected body whose branch reaches TWO unprotected `return`
     * blocks, each reached ONLY from the body, with a typed catch. These are TERMINAL exits (successor is
     * the method exit), NOT forwarders, so tail-bridge absorption does NOT fire and the method bails
     * honestly. jadx would duplicate the trivial return into each arm; we conservatively decline rather than
     * absorb a terminal exit — which would disturb the finally-factoring paths (see
     * multiExitFinallyWithOneExitMissingCleanupBails). A goto FORWARDER to a shared return IS absorbed (the
     * real corpus shape — see [bridgeToSharedFollowIsAbsorbedLeavingASingleFollow]); a bare terminal return
     * is not.
     */
    @Test
    fun bodyWithTwoTerminalReturnExitsBailsHonestly() {
        val reader = FakeCodeReader(
            2, // v0 = cond (param), v1 = exception var
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = voidCall), // [TRY START] head: sink()
                Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 3), // head: if (c==0) -> ret2 else -> ret1  [TRY END]
                Insn(Opcode.RETURN_VOID, 2), // ret1 (unprotected TERMINAL exit, reached ONLY from the body)
                Insn(Opcode.RETURN_VOID, 3), // ret2 (unprotected TERMINAL exit, reached ONLY from the body)
                Insn(Opcode.MOVE_EXCEPTION, 4, intArrayOf(1)), // typed catch handler
                Insn(Opcode.RETURN_VOID, 5), // catch body: return
            ),
            tries = listOf(FakeTryBlock(0, 1, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(4), -1))),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.INT))
        TestPipeline.structured(method)

        assertNull(method.region, "two terminal-return exits (not forwarders) must bail — absorption is forwarders-only")
        assertTrue(method[PipelineAttrs.FULLY_STRUCTURED] != true, "the bailed method must not be flagged structured")
    }

    /**
     * The exact TestNestedTryCatch4 inner-JSON-try shape: the body branches; one arm reaches the shared
     * `return` (the follow) DIRECTLY, the other reaches it through an unprotected `goto` bridge just past
     * `try_end`. The shared return is ALSO reached from OUTSIDE the try (a pre-check), so it is NOT absorbed
     * — only the goto bridge is, collapsing the body to the single follow. The try structures with the
     * shared return placed exactly once after it.
     */
    @Test
    fun bridgeToSharedFollowIsAbsorbedLeavingASingleFollow() {
        val reader = FakeCodeReader(
            3, // v0, v1 = params (conditions), v2 = exception var
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = voidCall), // entry prefix
                Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 7), // if (p0==0) -> Bpre(off7) else -> try head(off2)
                Insn(Opcode.IF_EQZ, 2, intArrayOf(1), target = 5), // [TRY START] head: if (p1==0) -> bridgeArm else -> directArm
                Insn(Opcode.INVOKE_STATIC, 3, intArrayOf(), methodRef = voidCall), // directArm (protected)
                Insn(Opcode.GOTO, 4, target = 8), // directArm -> R(off8) DIRECTLY (protected)
                Insn(Opcode.INVOKE_STATIC, 5, intArrayOf(), methodRef = voidCall), // bridgeArm (protected)  [TRY END]
                Insn(Opcode.GOTO, 6, target = 8), // G: UNPROTECTED bridge -> R(off8)
                Insn(Opcode.INVOKE_STATIC, 7, intArrayOf(), methodRef = voidCall), // Bpre (external) -> R(off8)
                Insn(Opcode.RETURN_VOID, 8), // R: shared return (reached from directArm, G, and Bpre)
                Insn(Opcode.MOVE_EXCEPTION, 9, intArrayOf(2)), // typed catch handler
                Insn(Opcode.RETURN_VOID, 10), // catch body
            ),
            tries = listOf(FakeTryBlock(2, 5, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(9), -1))),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.INT, IrType.INT))
        TestPipeline.structured(method)

        assertEquals(true, method[PipelineAttrs.FULLY_STRUCTURED], "the bridge-to-shared-follow try must structure")
        assertFalse(method.contains(AttrFlag.HAS_ERROR), "correct structuring ⇒ no error flag")
        val tc = firstRegion<TryCatchRegion>(method)
        assertNotNull(tc, "a TryCatchRegion must be produced")
        assertEquals(1, tc.catches.size)
        // Every block placed exactly once — the shared return is NOT duplicated, it is the single follow.
        val occ = emitted(method).groupingBy { it.id }.eachCount()
        assertTrue(occ.values.all { it == 1 }, "no block placed twice — the shared return is the single follow")
        // The unprotected goto bridge was absorbed INTO the try body (a goto renders as nothing, but the
        // block is placed inside the try region — proof the fix pulled it in rather than bailing).
        val bridgeBlock = method.blocks.first { b -> b.instructions.any { it.offset == 6 && it.opcode == IrOpcode.GOTO } }
        assertTrue(bridgeBlock in deepBlocks(tc.tryRegion), "the tail-bridge goto is absorbed into the try body")
        // Nothing dropped: all four sink() calls survive, and exactly two returns (the shared follow + catch).
        assertEquals(4, liveOpcodeCount(method, IrOpcode.INVOKE), "all four sink() calls survive")
        assertEquals(2, liveOpcodeCount(method, IrOpcode.RETURN), "the shared return (once) + the catch return")
    }

    /**
     * Rule-4 bail guard: a genuine multi-follow. The body branches to two UNPROTECTED blocks that each do
     * real (throwing) work before merging — distinct post-try code that runs OUTSIDE the try's protection.
     * Neither is an exception-free bridge, so neither can be absorbed (pulling a throwing block into `try {}`
     * would change its exception coverage). The body keeps two exits ⇒ the method must bail honestly
     * (region == null), never mis-structure a second follow.
     */
    @Test
    fun genuineMultiFollowWithThrowingSecondExitBails() {
        val reader = FakeCodeReader(
            2, // v0 = cond (param), v1 = exception var
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), methodRef = voidCall), // [TRY START] head: sink()
                Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 4), // head: if (c==0) -> B else -> A  [TRY END]
                Insn(Opcode.INVOKE_STATIC, 2, intArrayOf(), methodRef = voidCall), // A: UNPROTECTED throwing work
                Insn(Opcode.GOTO, 3, target = 6), // A -> M
                Insn(Opcode.INVOKE_STATIC, 4, intArrayOf(), methodRef = voidCall), // B: UNPROTECTED throwing work
                Insn(Opcode.GOTO, 5, target = 6), // B -> M
                Insn(Opcode.RETURN_VOID, 6), // M: shared merge
                Insn(Opcode.MOVE_EXCEPTION, 7, intArrayOf(1)), // handler
                Insn(Opcode.RETURN_VOID, 8),
            ),
            tries = listOf(FakeTryBlock(0, 1, FakeCatchHandler(listOf("Ljava/lang/Exception;"), listOf(7), -1))),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.INT))
        TestPipeline.structured(method)

        assertNull(method.region, "a genuine multi-follow (throwing second exit) must bail, never mis-structure")
        assertTrue(method[PipelineAttrs.FULLY_STRUCTURED] != true, "the unstructurable try must not be flagged structured")
    }
}
