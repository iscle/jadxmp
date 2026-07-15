package com.jadxmp.codegen.kotlin

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
 * CLAUDE rule-4 fault isolation for the Kotlin backend — the mirror of `JavaFaultIsolationTest`. One
 * method whose rendering throws must be contained to an honest marker, leaving its siblings and the class
 * intact, never propagating out of `generate()`.
 */
class KotlinFaultIsolationTest {

    /** A `THROW` with no operand → `getArg(0)` on an empty arg list throws (an unguarded getArg site, F3). */
    private fun IrClass.throwingMethod(name: String) = method(name) { body(Instruction(IrOpcode.THROW)) }

    @Test
    fun throwingMethodIsContainedAndSiblingsStillRender() {
        val cls = irClass("p.C")
        // A member BEFORE the throw (its emitted text + metadata must survive the roll-back) and one AFTER
        // (must render uncorrupted once the writer is rolled back to a clean state).
        cls.method("before", IrType.INT) { body(ret(intLit(11))) }
        cls.throwingMethod("boom")
        cls.method("after", IrType.INT) { body(ret(intLit(42))) }

        val code = KotlinCodeGenerator().generate(cls).code // must NOT throw

        assertTrue("// JADXMP ERROR:" in code && "boom" in code, "missing containment marker:\n$code")
        assertTrue(cls.methods[1].contains(AttrFlag.HAS_ERROR), "throwing method must be flagged HAS_ERROR")
        // Prior member survived the roll-back; following member still renders — both at member indent (4 spaces).
        assertTrue("\n    fun before(): Int {" in code, "prior member erased by roll-back:\n$code")
        assertTrue("return 11" in code, "prior member body missing:\n$code")
        assertTrue("\n    fun after(): Int {" in code, "following member not at correct indent:\n$code")
        assertTrue("return 42" in code, "following member body missing:\n$code")
        assertFalse("\n        fun after(): Int" in code, "following member indent corrupted (leaked indent):\n$code")
        assertFalse(cls.methods[0].contains(AttrFlag.HAS_ERROR), "the prior good member must not be flagged")
        assertFalse(cls.methods[2].contains(AttrFlag.HAS_ERROR), "the following good member must not be flagged")
        assertTrue(code.trimEnd().endsWith("\n}"), "class did not close cleanly:\n$code")
    }

    @Test
    fun deepInlineExpressionChainBailsWithoutStackOverflow() {
        val cls = irClass("p.D")
        var e: Operand = intLit(1)
        repeat(DEEP) { e = expr(arith(ArithOp.ADD, intLit(1), e, reg(-1, IrType.INT))) }
        cls.method("deep", IrType.INT) { body(ret(e)) }
        cls.method("good", IrType.INT) { body(ret(intLit(7))) }

        val code = KotlinCodeGenerator().generate(cls).code // must NOT StackOverflow

        assertTrue("// JADXMP ERROR:" in code && "deep" in code, "deep expression must bail to a marker:\n$code")
        assertTrue(cls.methods[0].contains(AttrFlag.HAS_ERROR), "deep-expression method must be flagged")
        assertTrue("return 7" in code, "sibling after the deep-expression method must render:\n$code")
    }

    private companion object {
        const val DEEP = 600
    }
}
