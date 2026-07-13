package com.jadxmp.codegen.java

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class JavaPrecedenceTest {

    private val a = Local(1, IrType.INT, name = "a", isParam = true)
    private val b = Local(2, IrType.INT, name = "b", isParam = true)
    private val c = Local(3, IrType.INT, name = "c", isParam = true)

    /** Build `int expr(int a, int b, int c) { return <value>; }` and return the whole class source. */
    private fun returning(value: Operand): String {
        val cls = irClass("a.Foo")
        cls.method("expr", returnType = IrType.INT, argTypes = listOf(IrType.INT, IrType.INT, IrType.INT)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("a", "b", "c")
            body(ret(value))
        }
        return generate(cls)
    }

    @Test
    fun tighterOperatorNeedsNoParens() {
        // a + b * c
        val e = arith(ArithOp.ADD, a.ref(), expr(arith(ArithOp.MUL, b.ref(), c.ref())))
        assertThatCode(returning(expr(e))).containsOne("return a + b * c;")
    }

    @Test
    fun looserOperatorOnLeftNeedsParens() {
        // (a + b) * c
        val e = arith(ArithOp.MUL, expr(arith(ArithOp.ADD, a.ref(), b.ref())), c.ref())
        assertThatCode(returning(expr(e))).containsOne("return (a + b) * c;")
    }

    @Test
    fun rightAssociativityForcesParensOnSameLevelRight() {
        // a - (b - c) must keep parens; a - b - c must not.
        val rightNested = arith(ArithOp.SUB, a.ref(), expr(arith(ArithOp.SUB, b.ref(), c.ref())))
        assertThatCode(returning(expr(rightNested))).containsOne("return a - (b - c);")

        val leftNested = arith(ArithOp.SUB, expr(arith(ArithOp.SUB, a.ref(), b.ref())), c.ref())
        assertThatCode(returning(expr(leftNested))).containsOne("return a - b - c;")
    }

    @Test
    fun shiftIsLooserThanAddSoNoParens() {
        // (a + b) << c  parses as a + b << c already (+ binds tighter than <<): no parens.
        val e = arith(ArithOp.SHL, expr(arith(ArithOp.ADD, a.ref(), b.ref())), c.ref())
        assertThatCode(returning(expr(e))).containsOne("return a + b << c;")
    }

    @Test
    fun bitwiseOrIsLoosestSoAndKeepsNoParens() {
        // a & b | c parses as (a & b) | c: no parens needed.
        val e = arith(ArithOp.OR, expr(arith(ArithOp.AND, a.ref(), b.ref())), c.ref())
        assertThatCode(returning(expr(e))).containsOne("return a & b | c;")
    }

    @Test
    fun mixedShiftInsideOrGetsParens() {
        // (a | b) << c  requires parens because << binds tighter than |.
        val e = arith(ArithOp.SHL, expr(arith(ArithOp.OR, a.ref(), b.ref())), c.ref())
        assertThatCode(returning(expr(e))).containsOne("return (a | b) << c;")
    }

    @Test
    fun unaryNegationParenthesizesLooserOperand() {
        // -(a + b)
        val e = arith(ArithOp.NEGATE, expr(arith(ArithOp.ADD, a.ref(), b.ref())), a.ref())
        assertThatCode(returning(expr(e))).containsOne("return -(a + b);")
    }

    @Test
    fun nestedUnaryMinusIsParenthesizedNotDoubleMinus() {
        // NEG(NEG(a)) must be -(-a), never --a (which would lex as pre-decrement — a different program).
        val inner = arith(ArithOp.NEGATE, a.ref(), a.ref())
        val outer = arith(ArithOp.NEGATE, expr(inner), a.ref())
        assertThatCode(returning(expr(outer)))
            .containsOne("return -(-a);")
            .doesNotContain("--")
    }

    @Test
    fun unaryMinusOverNegativeLiteralIsParenthesized() {
        // NEG(-5) must be -(-5), never --5 (a compile error).
        val e = arith(ArithOp.NEGATE, lit(-5, IrType.INT), lit(-5, IrType.INT))
        assertThatCode(returning(expr(e)))
            .containsOne("return -(-5);")
            .doesNotContain("--")
    }
}
