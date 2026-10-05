package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class KotlinJvmInvocationProjectionTest {
    @Test
    fun objectClassUsesJavaClassProperty() {
        assertThatCode(call(IrType.OBJECT, "getClass", IrType.CLASS)).containsOne("return (value as kotlin.Any).javaClass")
    }

    @Test
    fun stringAndCharSequenceLengthUseProperty() {
        for (owner in listOf(IrType.STRING, IrType.objectType("java.lang.CharSequence"))) {
            assertThatCode(call(owner, "length", IrType.INT))
                .containsOne("return value!!.length")
                .doesNotContain("value.length()")
        }
    }

    @Test
    fun stringCharAtUsesIndexing() {
        assertThatCode(call(IrType.STRING, "charAt", IrType.CHAR, index = 1)).containsOne("val index = 1").containsOne("receiver!![index]")
    }

    @Test
    fun unrelatedLengthMethodKeepsCallSyntax() {
        assertThatCode(call(IrType.objectType("a.Custom"), "length", IrType.INT)).containsOne("receiver!!.length()")
    }

    @Test
    fun charBitwiseOperandPromotesToIntegerCode() {
        val cls = irClass("a.C")
        val value = Local(1, IrType.CHAR, name = "value", isParam = true)
        cls.method("m", returnType = IrType.INT, argTypes = listOf(IrType.CHAR)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("value")
            body(ret(expr(arith(ArithOp.AND, value.ref(), intLit(255), reg(-1, IrType.INT)))))
        }
        assertThatCode(generate(cls)).containsOne("return value.code and 255")
    }

    private fun call(owner: IrType, name: String, result: IrType, index: Int? = null): String {
        val cls = irClass("a.C")
        val value = Local(1, owner, name = "value", isParam = true)
        cls.method("m", returnType = result, argTypes = listOf(owner)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("value")
            val args = index?.let { listOf(intLit(it)) }.orEmpty()
            body(ret(expr(virtualInvoke(value.ref(), owner, name, result, args.map { it.type }, args, reg(-1, result)))))
        }
        return generate(cls)
    }
}
