package com.jadxmp.pipeline.structure

import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeFieldRef
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ExpressionShaping inlines an effect-sensitive single-use def — a field read, a boolean-returning call,
 * a cast — into its single use so conditions read `this.a == null` / `a.equals(b)` instead of dangling
 * statements. Because these observe/mutate memory or throw, the sink is ONLY performed when it is
 * order-preserving: every instruction it crosses must be inert. These tests pin the positive inlines and
 * the order-preservation refusals (multi-use, an intervening write, an intervening call).
 */
class ExpressionShapingTest {

    private val fieldF = FakeFieldRef("Lc/C;", "f", "I")
    private val fieldG = FakeFieldRef("Lc/C;", "g", "I")
    private val use = FakeMethodRef("Lc/C;", "use", "V", listOf("I"))
    private val sideEffect = FakeMethodRef("Lc/C;", "fx", "V", emptyList())
    private val boolTest = FakeMethodRef("Lc/C;", "test", "Z", emptyList())

    /** Drive analysis through out-of-SSA and expression shaping (stop before structuring). */
    private fun shaped(method: IrMethod) {
        TestPipeline.full(method)
        OutOfSsa(method).run()
        ExpressionShaping(method).run()
    }

    private fun allInsns(m: IrMethod) = m.blocks.flatMap { it.instructions }

    @Test
    fun singleUseStaticFieldReadIsInlinedIntoUse() {
        // v0 = C.f; use(v0)  → use(C.f)  (single-use read, empty cross-set)
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.SGET, 0, intArrayOf(0), indexType = IndexType.FIELD_REF, fieldRef = fieldF),
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = use),
                Insn(Opcode.RETURN_VOID, 2),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        shaped(method)

        assertTrue(
            allInsns(method).none { it.opcode == IrOpcode.STATIC_GET },
            "the single-use field read has no residual statement",
        )
        val invoke = allInsns(method).first { it.opcode == IrOpcode.INVOKE }
        assertTrue(
            invoke.args.any { it is InstructionOperand && it.instruction.opcode == IrOpcode.STATIC_GET },
            "the field read is wrapped into the call argument",
        )
    }

    @Test
    fun singleUseBooleanCallIsInlinedIntoCondition() {
        // v0 = test(); if (v0 == 0) …  → if (test() == 0) …
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, indexType = IndexType.METHOD_REF, methodRef = boolTest),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)),
                Insn(Opcode.IF_EQZ, 2, intArrayOf(0), target = 4),
                Insn(Opcode.RETURN_VOID, 3),
                Insn(Opcode.RETURN_VOID, 4),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        shaped(method)

        assertTrue(
            allInsns(method).none { it.opcode == IrOpcode.INVOKE },
            "the single-use boolean call has no residual statement",
        )
        val ifInsn = allInsns(method).first { it.opcode == IrOpcode.IF }
        assertTrue(
            ifInsn.args.any { it is InstructionOperand && it.instruction.opcode == IrOpcode.INVOKE },
            "the boolean call is wrapped into the if condition",
        )
    }

    @Test
    fun multiUseFieldReadIsNotInlined() {
        // v0 = C.f; use(v0); use(v0)  — two readers ⇒ inlining would duplicate the read; keep the statement.
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.SGET, 0, intArrayOf(0), indexType = IndexType.FIELD_REF, fieldRef = fieldF),
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = use),
                Insn(Opcode.INVOKE_STATIC, 2, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = use),
                Insn(Opcode.RETURN_VOID, 3),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        shaped(method)

        assertEquals(
            1,
            allInsns(method).count { it.opcode == IrOpcode.STATIC_GET },
            "a multi-use field read stays a statement (no duplication)",
        )
    }

    @Test
    fun fieldReadAcrossAnInterveningWriteIsNotInlined() {
        // v0 = C.f; C.g = 5; use(v0)  — a write sits between def and use, so the read is NOT order-
        // preserving (moving it past the write could observe a different value): keep the statement.
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.SGET, 0, intArrayOf(0), indexType = IndexType.FIELD_REF, fieldRef = fieldF),
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = 5),
                Insn(Opcode.SPUT, 2, intArrayOf(1), indexType = IndexType.FIELD_REF, fieldRef = fieldG),
                Insn(Opcode.INVOKE_STATIC, 3, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = use),
                Insn(Opcode.RETURN_VOID, 4),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        shaped(method)

        assertEquals(
            1,
            allInsns(method).count { it.opcode == IrOpcode.STATIC_GET },
            "a read across a write must stay a statement",
        )
    }

    @Test
    fun callAcrossAnInterveningSideEffectIsNotInlined() {
        // v0 = test(); fx(); if (v0 == 0) …  — a side-effecting call sits between the boolean call and
        // its use, so inlining would reorder the two calls: keep the boolean call's statement.
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, indexType = IndexType.METHOD_REF, methodRef = boolTest),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)),
                Insn(Opcode.INVOKE_STATIC, 2, indexType = IndexType.METHOD_REF, methodRef = sideEffect),
                Insn(Opcode.IF_EQZ, 3, intArrayOf(0), target = 5),
                Insn(Opcode.RETURN_VOID, 4),
                Insn(Opcode.RETURN_VOID, 5),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        shaped(method)

        assertEquals(
            2,
            allInsns(method).count { it.opcode == IrOpcode.INVOKE },
            "the boolean call is not inlined across another call (both calls remain statements)",
        )
    }
}
