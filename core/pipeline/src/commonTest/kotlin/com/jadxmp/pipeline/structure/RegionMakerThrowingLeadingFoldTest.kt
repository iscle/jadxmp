package com.jadxmp.pipeline.structure

import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.Operand
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A **throwing** leading def (array-get, field-get, call, integer div/rem) folds into a short-circuit
 * `&&`/`||` compound ONLY when the continuation block is reached from a SINGLE clean predecessor — the
 * exact throw-timing proof (see [RegionMaker.isInlinableLeadingDef]): the block, hence the def, runs iff
 * the head takes the arm to it, and short-circuit evaluates the operand — with the def inlined — iff the
 * SAME arm-condition holds, so the def raises on exactly the paths and at the exact point it did in the
 * CFG. This is the last blocker on conditions/TestComplexIf3 (`if (iArr != null && iArr[0] == -100)`).
 *
 * These pin the three rule-4 properties a wrong fold would silently violate:
 *  - **throw-timing** — the throwing op is inlined as a SUB-EXPRESSION of a later short-circuit operand
 *    (never hoisted to run unconditionally, never dropped — it appears exactly once);
 *  - **polarity** — the taken (then) arm stays its true worker, the else stays the other target;
 *  - **multi-predecessor honesty** — a throwing continuation reached on more than one path is NOT hoisted
 *    into the compound (that would raise it where the original skipped it); it structures as a plain if.
 */
class RegionMakerThrowingLeadingFoldTest {

    private val getArr = FakeMethodRef("Lc/C;", "getArr", "[I", emptyList())
    private val condA = FakeMethodRef("Lc/C;", "a", "Z", emptyList())
    private val condB = FakeMethodRef("Lc/C;", "b", "Z", emptyList())
    private val workerFx = FakeMethodRef("Lc/C;", "worker", "V", emptyList())
    private val otherFx = FakeMethodRef("Lc/C;", "other", "V", emptyList())
    private val sharedFx = FakeMethodRef("Lc/C;", "shared", "V", emptyList())
    private val thirdFx = FakeMethodRef("Lc/C;", "third", "V", emptyList())

    private fun childrenOf(region: Region): List<IrContainer> = when (region) {
        is SequenceRegion -> region.children.toList()
        is IfRegion -> listOfNotNull(region.thenRegion, region.elseRegion)
        is LoopRegion -> listOf(region.body)
        is SyncRegion -> listOf(region.body)
        is SwitchRegion -> region.cases.map { it.body } + listOfNotNull(region.defaultCase)
        is TryCatchRegion -> listOf(region.tryRegion) + region.catches.map { it.body } + listOfNotNull(region.finallyRegion)
        else -> emptyList()
    }

    private fun blocksOf(container: IrContainer?): List<BasicBlock> = when (container) {
        null -> emptyList()
        is BasicBlock -> listOf(container)
        is Region -> childrenOf(container).flatMap { blocksOf(it) }
        else -> emptyList()
    }

    private fun countCalls(method: IrMethod, name: String): Int {
        fun inInsn(insn: Instruction): Int {
            var n = if (insn is InvokeInstruction && insn.methodRef.name == name) 1 else 0
            for (k in 0 until insn.argCount) {
                val a = insn.getArg(k)
                if (a is InstructionOperand) n += inInsn(a.instruction)
            }
            return n
        }
        return method.blocks.flatMap { it.instructions }.sumOf { inInsn(it) }
    }

    /** Count [opcode] instructions across the whole method, recursing through wrapped (inlined) operands. */
    private fun countOpcode(method: IrMethod, opcode: IrOpcode): Int {
        fun inInsn(insn: Instruction): Int {
            var n = if (insn.opcode == opcode) 1 else 0
            for (k in 0 until insn.argCount) {
                val a = insn.getArg(k)
                if (a is InstructionOperand) n += inInsn(a.instruction)
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

    /** Every opcode appearing inside a [Condition] tree, recursing through wrapped operand sub-expressions. */
    private fun opcodesIn(cond: Condition): Set<IrOpcode> {
        val ops = HashSet<IrOpcode>()
        fun fromInsn(i: Instruction) {
            ops.add(i.opcode)
            for (k in 0 until i.argCount) {
                val a = i.getArg(k)
                if (a is InstructionOperand) fromInsn(a.instruction)
            }
        }
        fun fromOperand(o: Operand) { if (o is InstructionOperand) fromInsn(o.instruction) }
        fun walk(c: Condition) {
            when (c) {
                is Condition.Compare -> { fromOperand(c.left); fromOperand(c.right) }
                is Condition.BoolTest -> fromOperand(c.operand)
                is Condition.Not -> walk(c.negated)
                is Condition.And -> c.terms.forEach { walk(it) }
                is Condition.Or -> c.terms.forEach { walk(it) }
            }
        }
        walk(cond)
        return ops
    }

    /** Whether any compound (And/Or) condition in the tree wraps an [opcode] operand — an unsafe hoist. */
    private fun anyCompoundContains(region: Region?, opcode: IrOpcode): Boolean {
        fun walk(c: IrContainer?): Boolean = when (c) {
            is IfRegion -> {
                val cond = c.condition
                val hoisted = (cond is Condition.And || cond is Condition.Or) && opcode in opcodesIn(cond)
                hoisted || childrenOf(c).any { walk(it) }
            }
            is Region -> childrenOf(c).any { walk(it) }
            else -> false
        }
        return walk(region)
    }

    /**
     * `arr = getArr(); if (arr == null) goto SHARED; if (arr[0] != 100) goto SHARED; worker(); SHARED: other();`
     * The array-get is the continuation's leading def (single clean predecessor), so it folds into
     * `if (arr == null || arr[0] != 100) other() else worker()` — arr[0] evaluated iff arr != null.
     */
    private fun throwingArrayGetOr(): IrMethod {
        val reader = FakeCodeReader(
            5,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, indexType = IndexType.METHOD_REF, methodRef = getArr),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)), // r0 = arr (int[])
                Insn(Opcode.CONST, 2, intArrayOf(1), literal = 0), // r1 = index 0
                Insn(Opcode.CONST, 3, intArrayOf(2), literal = 100), // r2 = 100
                Insn(Opcode.IF_EQZ, 4, intArrayOf(0), target = 9), // arr == null -> SHARED
                Insn(Opcode.AGET, 5, intArrayOf(3, 0, 1)), // r3 = arr[0]  (THROWING leading def)
                Insn(Opcode.IF_NE, 6, intArrayOf(3, 2), target = 9), // arr[0] != 100 -> SHARED
                Insn(Opcode.INVOKE_STATIC, 7, indexType = IndexType.METHOD_REF, methodRef = workerFx),
                Insn(Opcode.RETURN_VOID, 8),
                Insn(Opcode.INVOKE_STATIC, 9, indexType = IndexType.METHOD_REF, methodRef = otherFx), // SHARED
                Insn(Opcode.RETURN_VOID, 10),
            ),
        )
        return TestPipeline.buildMethod(reader)
    }

    @Test
    fun foldsOrAcrossThrowingArrayGetLeadingDef() {
        val method = throwingArrayGetOr()
        TestPipeline.structured(method)

        val region = method.region
        assertNotNull(region, "the throwing-array-get || shape must structure (no revisit bail)")
        val ifr = firstIf(region)
        assertNotNull(ifr, "an IfRegion must be present")
        val cond = ifr.condition
        assertTrue(cond is Condition.Or, "arr==null || arr[0]!=100 must fold into one Or, got ${cond::class.simpleName}")
        assertEquals(2, cond.terms.size, "exactly two OR operands")
        // Throw-timing: the array-get is inlined as a SUB-EXPRESSION of a short-circuit operand (so it is
        // evaluated iff arr != null), not hoisted to run before the `||`.
        assertTrue(IrOpcode.ARRAY_GET in opcodesIn(cond), "the array-get must be inlined INTO the compound")
    }

    @Test
    fun throwingArrayGetOrPreservesPolarityAndEvaluatesOnce() {
        val method = throwingArrayGetOr()
        TestPipeline.structured(method)

        val ifr = firstIf(method.region)
        assertNotNull(ifr, "an IfRegion must be present")
        // Polarity: the taken (then) arm is the shared `other` (arr==null || arr[0]!=100), the else is the
        // fall-through `worker` (arr != null && arr[0] == 100). A polarity flip would swap these.
        assertEquals(setOf("other"), invokedNames(ifr.thenRegion), "taken arm must stay the shared `other`")
        assertEquals(setOf("worker"), invokedNames(ifr.elseRegion), "else arm must stay `worker`")
        // No silent code loss / no double-throw: the array-get appears EXACTLY once, and each arm once.
        assertEquals(1, countOpcode(method, IrOpcode.ARRAY_GET), "array-get evaluated once (not dropped/duplicated)")
        assertEquals(1, countCalls(method, "worker"), "worker placed exactly once")
        assertEquals(1, countCalls(method, "other"), "other placed exactly once")
    }

    /**
     * `arr = getArr(); if (a()) { if (arr[0] == 100) goto WORKER; } SHARED: other(); WORKER: worker();`
     * compiled as `if (!a()) goto SHARED; if (arr[0] != 100) goto SHARED; worker();`. The array-get is the
     * AND-continuation's leading def (single pred), so it folds into `if (a() && arr[0] == 100) worker()
     * else other()` — arr[0] evaluated iff a() is true (short-circuit `&&`).
     */
    @Test
    fun foldsAndAcrossThrowingArrayGetLeadingDef() {
        val reader = FakeCodeReader(
            5,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, indexType = IndexType.METHOD_REF, methodRef = getArr),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)), // r0 = arr
                Insn(Opcode.CONST, 2, intArrayOf(1), literal = 0), // r1 = index 0
                Insn(Opcode.CONST, 3, intArrayOf(2), literal = 100), // r2 = 100
                Insn(Opcode.INVOKE_STATIC, 4, indexType = IndexType.METHOD_REF, methodRef = condA),
                Insn(Opcode.MOVE_RESULT, 5, intArrayOf(3)), // r3 = a()
                Insn(Opcode.IF_NEZ, 6, intArrayOf(3), target = 9), // a() -> CONT ; else fall to SHARED
                Insn(Opcode.INVOKE_STATIC, 7, indexType = IndexType.METHOD_REF, methodRef = otherFx), // SHARED
                Insn(Opcode.RETURN_VOID, 8),
                Insn(Opcode.AGET, 9, intArrayOf(4, 0, 1)), // CONT: r4 = arr[0]  (THROWING leading def)
                Insn(Opcode.IF_NE, 10, intArrayOf(4, 2), target = 7), // arr[0] != 100 -> SHARED
                Insn(Opcode.INVOKE_STATIC, 11, indexType = IndexType.METHOD_REF, methodRef = workerFx), // WORKER
                Insn(Opcode.RETURN_VOID, 12),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        val region = method.region
        assertNotNull(region, "the throwing-array-get && shape must structure")
        val ifr = firstIf(region)
        assertNotNull(ifr, "an IfRegion must be present")
        val cond = ifr.condition
        assertTrue(cond is Condition.And, "a() && arr[0]==100 must fold into one And, got ${cond::class.simpleName}")
        assertEquals(2, cond.terms.size, "exactly two AND operands")
        assertTrue(IrOpcode.ARRAY_GET in opcodesIn(cond), "the array-get must be inlined INTO the compound (guarded)")
        // Polarity: worker runs iff a() && arr[0]==100 (then), other otherwise (else).
        assertEquals(setOf("worker"), invokedNames(ifr.thenRegion), "taken arm must be `worker`")
        assertEquals(setOf("other"), invokedNames(ifr.elseRegion), "else arm must be `other`")
        assertEquals(1, countOpcode(method, IrOpcode.ARRAY_GET), "array-get evaluated exactly once")
    }

    /**
     * `if (a() || b()) { CONT: if (arr[0] != 100) shared(); else third(); } else other();`
     * CONT (the array-get block) is reached from BOTH `a()`-true and `b()`-true — two clean predecessors,
     * so it is NOT a short-circuit continuation and must NOT fold its throwing array-get into the `a()||b()`
     * compound. Hoisting arr[0] into `a() || b() || arr[0]!=100` would evaluate it when only one of a()/b()
     * holds — a different, EARLIER throw point. The array-get must stay a plain nested `if` (evaluated at
     * the top of CONT, reached iff a()||b()) — exact original throw-timing.
     */
    @Test
    fun doesNotHoistThrowingArrayGetReachedFromMultiplePredecessors() {
        val reader = FakeCodeReader(
            5,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, indexType = IndexType.METHOD_REF, methodRef = getArr),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)), // r0 = arr
                Insn(Opcode.CONST, 2, intArrayOf(1), literal = 0), // r1 = index 0
                Insn(Opcode.CONST, 3, intArrayOf(2), literal = 100), // r2 = 100
                Insn(Opcode.INVOKE_STATIC, 4, indexType = IndexType.METHOD_REF, methodRef = condA),
                Insn(Opcode.MOVE_RESULT, 5, intArrayOf(3)),
                Insn(Opcode.IF_NEZ, 6, intArrayOf(3), target = 12), // a() -> CONT(12) ; else fall to head2(7)
                Insn(Opcode.INVOKE_STATIC, 7, indexType = IndexType.METHOD_REF, methodRef = condB), // head2
                Insn(Opcode.MOVE_RESULT, 8, intArrayOf(3)),
                Insn(Opcode.IF_NEZ, 9, intArrayOf(3), target = 12), // b() -> CONT(12) ; else fall to OTHER(10)
                Insn(Opcode.INVOKE_STATIC, 10, indexType = IndexType.METHOD_REF, methodRef = otherFx), // OTHER
                Insn(Opcode.RETURN_VOID, 11),
                Insn(Opcode.AGET, 12, intArrayOf(4, 0, 1)), // CONT: r4 = arr[0]  (two preds: a-true@6, b-true@9)
                Insn(Opcode.IF_NE, 13, intArrayOf(4, 2), target = 16), // arr[0] != 100 -> SHARED(16) ; else THIRD(14)
                Insn(Opcode.INVOKE_STATIC, 14, indexType = IndexType.METHOD_REF, methodRef = thirdFx), // THIRD
                Insn(Opcode.RETURN_VOID, 15),
                Insn(Opcode.INVOKE_STATIC, 16, indexType = IndexType.METHOD_REF, methodRef = sharedFx), // SHARED
                Insn(Opcode.RETURN_VOID, 17),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.structured(method)

        val region = method.region
        assertNotNull(region, "the (a||b) -> array-get-merge shape must structure")
        val ifr = firstIf(region)
        assertNotNull(ifr, "an IfRegion must be present")
        val cond = ifr.condition
        assertTrue(cond is Condition.Or, "a() || b() must fold, got ${cond::class.simpleName}")
        assertEquals(2, cond.terms.size, "the compound has only a(), b() — the array-get is NOT a third term")
        // The throw-timing guarantee: the array-get is NEVER wrapped inside any compound (And/Or) condition.
        assertFalse(
            anyCompoundContains(region, IrOpcode.ARRAY_GET),
            "a throwing array-get reached from multiple predecessors must NOT be hoisted into a short-circuit compound",
        )
        assertEquals(1, countOpcode(method, IrOpcode.ARRAY_GET), "array-get evaluated exactly once at the merge")
    }
}
