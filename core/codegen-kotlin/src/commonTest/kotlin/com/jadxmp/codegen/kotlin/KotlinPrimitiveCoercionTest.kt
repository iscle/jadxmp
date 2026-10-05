package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.insn.ConditionOp
import com.jadxmp.ir.insn.IfInstruction
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class KotlinPrimitiveCoercionTest {
    @Test
    fun numericComparisonUsesCoalescedBooleanAsOneOrZero() {
        val cls = irClass("a.C")
        val value = Local(1, IrType.INT, name = "value", isParam = true)
        val flag = Local(2, IrType.BOOLEAN, name = "flag", isParam = true)
        cls.method("m", returnType = IrType.BOOLEAN, argTypes = listOf(IrType.INT, IrType.BOOLEAN)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("value", "flag")
            body(ret(expr(IfInstruction(ConditionOp.NE, listOf(value.ref(), flag.ref())))))
        }
        assertThatCode(generate(cls)).containsOne("return value != (if (flag) 1 else 0)")
    }

    @Test
    fun booleanPassedToNumericParameterUsesOneOrZero() {
        val cls = irClass("a.C")
        val value = Local(1, IrType.BOOLEAN, name = "value", isParam = true)
        cls.method("m", argTypes = listOf(IrType.BOOLEAN)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("value")
            body(staticInvoke(IrType.objectType("a.C"), "consume", IrType.VOID, listOf(IrType.INT), listOf(value.ref())), ret())
        }
        assertThatCode(generate(cls)).containsOne("consume(if (value) 1 else 0)")
    }

    @Test
    fun booleanReturnedAsLongUsesLongLiterals() {
        val cls = irClass("a.C")
        val value = Local(1, IrType.BOOLEAN, name = "value", isParam = true)
        cls.method("m", returnType = IrType.LONG, argTypes = listOf(IrType.BOOLEAN)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("value")
            body(ret(value.ref()))
        }
        assertThatCode(generate(cls)).containsOne("return if (value) 1L else 0L")
    }

    @Test
    fun booleanXorCoercesIntegerMaskToBoolean() {
        val cls = irClass("a.C")
        val value = Local(1, IrType.BOOLEAN, name = "value", isParam = true)
        cls.method("m", returnType = IrType.BOOLEAN, argTypes = listOf(IrType.BOOLEAN)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("value")
            body(ret(expr(arith(ArithOp.XOR, value.ref(), intLit(1), reg(-1, IrType.BOOLEAN)))))
        }
        assertThatCode(generate(cls)).containsOne("return value xor true")
    }

    @Test
    fun numericXorKeepsIntegerResultAndMask() {
        val cls = irClass("a.C")
        val value = Local(1, IrType.BOOLEAN, name = "value", isParam = true)
        cls.method("m", returnType = IrType.INT, argTypes = listOf(IrType.BOOLEAN)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("value")
            body(ret(expr(arith(ArithOp.XOR, value.ref(), intLit(2), reg(-1, IrType.INT)))))
        }
        assertThatCode(generate(cls)).containsOne("return (if (value) 1 else 0) xor 2")
    }

    @Test
    fun booleanCastHasConditionalPrecedenceInArithmetic() {
        val cls = irClass("a.C")
        val value = Local(1, IrType.BOOLEAN, name = "value", isParam = true)
        cls.method("m", returnType = IrType.LONG, argTypes = listOf(IrType.BOOLEAN)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("value")
            body(ret(expr(arith(ArithOp.ADD, expr(cast(IrType.LONG, value.ref())), lit(2, IrType.LONG)))))
        }
        assertThatCode(generate(cls)).containsOne("return (if (value) 1L else 0L) + 2L")
    }
}
