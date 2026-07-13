package com.jadxmp.pipeline.structure

import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Out-of-SSA re-materializes a **shared polymorphic zero** at each move that copies it, so two source
 * variables of incompatible type never end up aliasing the zero's holder (the `str = list` miscompile).
 * The rewrite is gated hard on rule 4: only a PROVEN compile-time `const 0` (transitively through pure
 * moves) is duplicated, and only when the source is genuinely shared — a computed/loaded value is never
 * re-materialized (that would clone a side effect).
 */
class OutOfSsaZeroConstTest {

    private val LIST = "Ljava/util/List;"
    private val STRING = "Ljava/lang/String;"

    private fun listRef() = FakeMethodRef("Lcom/example/Foo;", "list", LIST, emptyList())
    private fun useRef() = FakeMethodRef("Lcom/example/Foo;", "use", "V", listOf(LIST, STRING))

    private fun insnAt(method: IrMethod, offset: Int) =
        method.blocks.flatMap { it.instructions }.first { it.offset == offset }

    /** Drive analysis through SSA + type inference, then destruct SSA (the pass under test). */
    private fun deSsa(method: IrMethod) {
        TestPipeline.full(method)
        OutOfSsa(method).run()
    }

    @Test
    fun sharedZeroMoveIsRematerializedAsIndependentConst() {
        // if (p0) { v0 = list(); v1 = "1"; } else { v2 = 0; v0 = v2; v1 = v0; } use(v0, v1)
        // The else `v1 = v0` reads a zero that also feeds v0's φ (a List local). Coalescing would make it
        // print `str = list` (List → String). The zero must be re-materialized so `v1` gets its own const.
        val reader = FakeCodeReader(
            4, // v0, v1, v2, v3 = boolean param
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(3), target = 6),
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = listRef()),
                Insn(Opcode.MOVE_RESULT, 2, intArrayOf(0)), // v0 = list() : List
                // v1 = "1"
                Insn(Opcode.CONST_STRING, 3, intArrayOf(1), indexType = IndexType.STRING_REF, stringValue = "1"),
                Insn(Opcode.GOTO, 4, target = 9),
                // else (offset 6):
                Insn(Opcode.CONST, 6, intArrayOf(2), literal = 0), // v2 = 0
                Insn(Opcode.MOVE, 7, intArrayOf(0, 2)), // v0 = v2  (source used once → NOT shared)
                Insn(Opcode.MOVE, 8, intArrayOf(1, 0)), // v1 = v0  (source is the shared zero)
                // merge (offset 9):
                Insn(Opcode.INVOKE_STATIC, 9, intArrayOf(0, 1), indexType = IndexType.METHOD_REF, methodRef = useRef()),
                Insn(Opcode.RETURN_VOID, 10),
            ),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.BOOLEAN))
        deSsa(method)

        // The shared-zero copy `v1 = v0` became an independent `const 0`, breaking the cross-type alias.
        val rewritten = insnAt(method, 8)
        assertEquals(IrOpcode.CONST, rewritten.opcode, "shared zero move must re-materialize as its own const")
        val lit = rewritten.args.firstOrNull() as? LiteralOperand
        assertNotNull(lit, "the re-materialized const carries a literal")
        assertEquals(0L, lit.value, "the re-materialized const is zero (renders null for a reference local)")

        // The unshared copy `v0 = v2` (its source has a single use) is left exactly as it was.
        assertEquals(IrOpcode.MOVE, insnAt(method, 7).opcode, "an unshared zero move is a plain rename — untouched")
    }

    @Test
    fun sharedMoveOfComputedValueIsNotRematerialized() {
        // Same shape, but the else `v0` is an INVOKE result (a side-effecting computed value), not a zero.
        // Re-materializing it would duplicate the call, so `v1 = v0` MUST stay a move (rule 4).
        val reader = FakeCodeReader(
            4,
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(3), target = 6),
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = listRef()),
                Insn(Opcode.MOVE_RESULT, 2, intArrayOf(0)),
                Insn(Opcode.CONST_STRING, 3, intArrayOf(1), indexType = IndexType.STRING_REF, stringValue = "1"),
                Insn(Opcode.GOTO, 4, target = 9),
                // else (offset 6): v0 = list() (computed, side-effecting), v1 = v0
                Insn(Opcode.INVOKE_STATIC, 6, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = listRef()),
                Insn(Opcode.MOVE_RESULT, 7, intArrayOf(0)), // v0 = list() : computed
                Insn(Opcode.MOVE, 8, intArrayOf(1, 0)), // v1 = v0  (shared, but NOT a proven zero)
                Insn(Opcode.INVOKE_STATIC, 9, intArrayOf(0, 1), indexType = IndexType.METHOD_REF, methodRef = useRef()),
                Insn(Opcode.RETURN_VOID, 10),
            ),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.BOOLEAN))
        deSsa(method)

        assertEquals(
            IrOpcode.MOVE,
            insnAt(method, 8).opcode,
            "a shared move of a computed value must never be re-materialized — that would clone the call",
        )
        // And no fresh const-0 was fabricated from the computed value.
        assertTrue(
            method.blocks.flatMap { it.instructions }.none {
                it.offset == 8 && it.opcode == IrOpcode.CONST
            },
            "no const materialized from a non-zero source",
        )
    }
}
