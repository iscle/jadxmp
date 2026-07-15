package com.jadxmp.codegen.java

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CLAUDE rule-4 fault isolation for the Java backend: one method whose *rendering* throws must never
 * discard the whole class. Each test asserts CONTAINMENT — an honest marker for the bad member, the
 * sibling members still rendered at the correct indent, the class closed cleanly, and error accounting
 * flagged — not a crash. Without the per-member backstop these throws escape `generate()` and take the
 * entire class (and, through `decompileAll`, the batch) with them.
 */
class JavaFaultIsolationTest {

    /**
     * A method whose body emission throws: a `THROW` with no operand makes `emitStatementCore` read
     * `getArg(0)` on an empty arg list → `IndexOutOfBounds` (one of the unguarded getArg sites, F3). It
     * stands in for ANY per-method throw the backstop must contain.
     */
    private fun IrClass.throwingMethod(name: String) = method(name) { body(Instruction(IrOpcode.THROW)) }

    @Test
    fun throwingMethodIsContainedAndSiblingsStillRender() {
        val cls = irClass("p.C")
        // A member BEFORE the throw (its already-emitted text + metadata must survive the roll-back) and one
        // AFTER (must render uncorrupted once the writer is rolled back to a clean state).
        cls.method("before", IrType.INT) { body(ret(intLit(11))) }
        cls.throwingMethod("boom")
        cls.method("after", IrType.INT) { body(ret(intLit(42))) }

        val code = JavaCodeGenerator().generate(cls).code // must NOT throw

        // The bad method bails to an honest marker naming it, and is flagged so error accounting counts it.
        assertTrue("// JADXMP ERROR:" in code && "boom" in code, "missing containment marker:\n$code")
        assertTrue(cls.methods[1].contains(AttrFlag.HAS_ERROR), "throwing method must be flagged HAS_ERROR")
        // The prior member survived the roll-back untouched; the following one still renders — both at the
        // correct member indent (4 spaces). A leaked +1 indent would put `after` at 8 spaces or underflow the
        // class-closing decIndent; a too-eager roll-back would erase `before`.
        assertTrue("\n    public int before() {" in code, "prior member erased by roll-back:\n$code")
        assertTrue("return 11;" in code, "prior member body missing:\n$code")
        assertTrue("\n    public int after() {" in code, "following member not at correct indent:\n$code")
        assertTrue("return 42;" in code, "following member body missing:\n$code")
        assertFalse("\n        public int after()" in code, "following member indent corrupted (leaked indent):\n$code")
        assertFalse(cls.methods[0].contains(AttrFlag.HAS_ERROR), "the prior good member must not be flagged")
        assertFalse(cls.methods[2].contains(AttrFlag.HAS_ERROR), "the following good member must not be flagged")
        // The class closes cleanly at column 0 (no leaked indent).
        assertTrue(code.trimEnd().endsWith("\n}"), "class did not close cleanly:\n$code")
    }

    @Test
    fun deepInlineExpressionChainBailsWithoutStackOverflow() {
        val cls = irClass("p.D")
        // A single-use inline chain far longer than the depth cap — exactly what ExpressionShaping produces
        // when it folds a long single-use chain to a fixpoint (`1 + (1 + (1 + …))`).
        var e: Operand = intLit(1)
        repeat(DEEP) { e = expr(arith(ArithOp.ADD, intLit(1), e, reg(-1, IrType.INT))) }
        cls.method("deep", IrType.INT) { body(ret(e)) }
        cls.method("good", IrType.INT) { body(ret(intLit(7))) }

        // Must NOT throw a StackOverflowError: the depth guard trips first and the backstop makes a marker.
        val code = JavaCodeGenerator().generate(cls).code

        assertTrue("// JADXMP ERROR:" in code && "deep" in code, "deep expression must bail to a marker:\n$code")
        assertTrue(cls.methods[0].contains(AttrFlag.HAS_ERROR), "deep-expression method must be flagged")
        assertTrue("return 7;" in code, "sibling after the deep-expression method must render:\n$code")
    }

    private companion object {
        // Comfortably above the backend's internal MAX_EXPR_DEPTH so the guard trips. The guard stops the
        // recursion at that cap (well under any real stack limit), so this never becomes a real overflow.
        const val DEEP = 600
    }
}
