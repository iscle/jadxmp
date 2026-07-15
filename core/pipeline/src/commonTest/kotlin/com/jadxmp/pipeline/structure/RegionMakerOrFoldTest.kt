package com.jadxmp.pipeline.structure

import com.jadxmp.input.Opcode
import com.jadxmp.input.IndexType
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrContainer
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.region.Condition
import com.jadxmp.ir.region.IfRegion
import com.jadxmp.ir.region.LoopRegion
import com.jadxmp.ir.region.Region
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.region.SwitchRegion
import com.jadxmp.ir.region.SyncRegion
import com.jadxmp.ir.region.TryCatchRegion
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Short-circuit `||`-fold across a continuation reaching a shared block on the INVERTED arm via a
 * `goto` (jadx: IfRegionMaker.mergeNestedIfNodes with isInversionNeeded + followEmptyPath). This is the
 * TestNestedTryCatch4 / TestComplexIf3 shape: `A || B` where the second condition's arm to the shared
 * target is its fall-through (through an empty goto block), not its taken arm.
 *
 * These pin the two rule-4 properties a wrong fold would silently violate:
 *  - **polarity** — the taken (then) arm stays the shared worker, the else stays the other target;
 *  - **short-circuit honesty** — a continuation carrying a REAL leading side effect is NOT folded
 *    (its statement would be dropped or hoisted out of the short circuit), it structures honestly.
 */
class RegionMakerOrFoldTest {

    private val condA = FakeMethodRef("Lc/C;", "a", "Z", emptyList())
    private val condB = FakeMethodRef("Lc/C;", "b", "Z", emptyList())
    private val workerFx = FakeMethodRef("Lc/C;", "worker", "V", emptyList())
    private val otherFx = FakeMethodRef("Lc/C;", "other", "V", emptyList())
    private val sideFx = FakeMethodRef("Lc/C;", "side", "V", emptyList())

    /** The child containers of a region kind (enough kinds for these synthetic if/sequence shapes). */
    private fun childrenOf(region: Region): List<IrContainer> = when (region) {
        is SequenceRegion -> region.children.toList()
        is IfRegion -> listOfNotNull(region.thenRegion, region.elseRegion)
        is LoopRegion -> listOf(region.body)
        is SyncRegion -> listOf(region.body)
        is SwitchRegion -> region.cases.map { it.body } + listOfNotNull(region.defaultCase)
        is TryCatchRegion -> listOf(region.tryRegion) + region.catches.map { it.body } + listOfNotNull(region.finallyRegion)
        else -> emptyList()
    }

    /** Every BasicBlock reachable in a region subtree. */
    private fun blocksOf(container: IrContainer?): List<BasicBlock> = when (container) {
        null -> emptyList()
        is BasicBlock -> listOf(container)
        is Region -> childrenOf(container).flatMap { blocksOf(it) }
        else -> emptyList()
    }

    /** Count invokes of [name] across the whole method, recursing through wrapped (inlined) operands. */
    private fun countCalls(method: IrMethod, name: String): Int {
        fun inInsn(insn: com.jadxmp.ir.insn.Instruction): Int {
            var n = if (insn is InvokeInstruction && insn.methodRef.name == name) 1 else 0
            for (k in 0 until insn.argCount) {
                val a = insn.getArg(k)
                if (a is com.jadxmp.ir.insn.InstructionOperand) n += inInsn(a.instruction)
            }
            return n
        }
        return method.blocks.flatMap { it.instructions }.sumOf { inInsn(it) }
    }

    private fun invokedNames(container: IrContainer?): Set<String> =
        blocksOf(container)
            .flatMap { it.instructions }
            .filterIsInstance<InvokeInstruction>()
            .map { it.methodRef.name }
            .toSet()

    private fun firstIf(region: Region?): IfRegion? {
        fun walk(c: IrContainer?): IfRegion? = when (c) {
            is IfRegion -> c
            is Region -> childrenOf(c).firstNotNullOfOrNull { walk(it) }
            else -> null
        }
        return walk(region)
    }

    private fun anyOrCondition(region: Region?): Boolean {
        fun walk(c: IrContainer?): Boolean = when (c) {
            is IfRegion -> c.condition is Condition.Or || childrenOf(c).any { walk(it) }
            is Region -> childrenOf(c).any { walk(it) }
            else -> false
        }
        return walk(region)
    }

    /**
     * `if (a()) goto WORKER; if (!b()) goto OTHER; goto WORKER; WORKER: worker(); OTHER: other();`
     * The second condition reaches WORKER on its fall-through arm through an empty `goto` block — the
     * inverted-arm shape. Correct fold: `if (a() || b()) worker(); else other();`.
     */
    private fun invertedGotoOr(): IrMethod {
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, indexType = IndexType.METHOD_REF, methodRef = condA),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)),
                Insn(Opcode.IF_NEZ, 2, intArrayOf(0), target = 7), // a() -> WORKER
                Insn(Opcode.INVOKE_STATIC, 3, indexType = IndexType.METHOD_REF, methodRef = condB),
                Insn(Opcode.MOVE_RESULT, 4, intArrayOf(1)),
                Insn(Opcode.IF_EQZ, 5, intArrayOf(1), target = 9), // !b() -> OTHER
                Insn(Opcode.GOTO, 6, target = 7), // else (b() true) -> WORKER  [inverted arm, empty goto]
                Insn(Opcode.INVOKE_STATIC, 7, indexType = IndexType.METHOD_REF, methodRef = workerFx),
                Insn(Opcode.RETURN_VOID, 8),
                Insn(Opcode.INVOKE_STATIC, 9, indexType = IndexType.METHOD_REF, methodRef = otherFx),
                Insn(Opcode.RETURN_VOID, 10),
            ),
        )
        return TestPipeline.buildMethod(reader)
    }

    @Test
    fun foldsOrAcrossInvertedGotoWithMethodCallOperands() {
        val method = invertedGotoOr()
        TestPipeline.structured(method)

        val region = method.region
        assertNotNull(region, "the inverted-goto A||B shape must structure (no revisit bail)")
        val ifr = firstIf(region)
        assertNotNull(ifr, "an IfRegion must be present")
        val cond = ifr.condition
        assertTrue(cond is Condition.Or, "A||B must fold into a single Or, got ${cond::class.simpleName}")
        assertEquals(2, cond.terms.size, "exactly two OR operands (a() , b())")
    }

    @Test
    fun invertedGotoOrPreservesPolarityAndShortCircuit() {
        val method = invertedGotoOr()
        TestPipeline.structured(method)

        val ifr = firstIf(method.region)
        assertNotNull(ifr, "an IfRegion must be present")
        // Polarity: the TAKEN (then) arm is the shared worker; the else is the other target. A polarity
        // inversion (wrong true/false target) would swap these — the catastrophic silent miscompile.
        assertEquals(setOf("worker"), invokedNames(ifr.thenRegion), "taken arm must stay the worker")
        assertEquals(setOf("other"), invokedNames(ifr.elseRegion), "else arm must stay the other target")
        // Short circuit: worker/other each run once (single placement) and each operand a()/b() appears
        // exactly once — B is not duplicated/re-evaluated. Count recurses through wrapped operands, since
        // ExpressionShaping folds the operand invokes INTO the compound condition (not top-level insns).
        assertEquals(1, countCalls(method, "worker"), "worker placed exactly once")
        assertEquals(1, countCalls(method, "other"), "other placed exactly once")
        assertEquals(1, countCalls(method, "a"), "operand a() evaluated once")
        assertEquals(1, countCalls(method, "b"), "operand b() evaluated once")
    }

    @Test
    fun doesNotFoldWhenContinuationCarriesRealLeadingSideEffect() {
        // Same A||B shape, but the second condition block runs a real side effect (side()) BEFORE its
        // `if`. Folding would drop it or hoist it out of the short circuit, so the fold must decline and
        // the method structures honestly (the aligned worker is a duplicable return tail) with side()
        // preserved and NO compound Or emitted.
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, indexType = IndexType.METHOD_REF, methodRef = condA),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)),
                Insn(Opcode.IF_NEZ, 2, intArrayOf(0), target = 8), // a() -> WORKER
                Insn(Opcode.INVOKE_STATIC, 3, indexType = IndexType.METHOD_REF, methodRef = sideFx), // REAL leading stmt
                Insn(Opcode.INVOKE_STATIC, 4, indexType = IndexType.METHOD_REF, methodRef = condB),
                Insn(Opcode.MOVE_RESULT, 5, intArrayOf(0)),
                Insn(Opcode.IF_NEZ, 6, intArrayOf(0), target = 8), // b() -> WORKER (aligned)
                Insn(Opcode.RETURN_VOID, 7), // else -> return
                Insn(Opcode.INVOKE_STATIC, 8, indexType = IndexType.METHOD_REF, methodRef = workerFx),
                Insn(Opcode.RETURN_VOID, 9),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        val region = method.region
        assertNotNull(region, "must still structure honestly (worker is a duplicable return tail)")
        assertTrue(!anyOrCondition(region), "must NOT fold A||B when the continuation has a real leading side effect")
        // Rule 4: the real leading statement must NOT be dropped by the (declined) fold.
        val sides = method.blocks.flatMap { it.instructions }.filterIsInstance<InvokeInstruction>()
            .count { it.methodRef.name == "side" }
        assertEquals(1, sides, "the leading side effect must be preserved, never dropped")
    }
}
