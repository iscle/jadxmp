package com.jadxmp.codegen.java

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.ConditionOp
import com.jadxmp.ir.insn.IfInstruction
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class JavaComparisonCoercionTest {
    @Test
    fun mixedBooleanIntegerEqualityUsesNumericBooleanValue() {
        val cls = irClass("a.C")
        val flag = Local(1, IrType.BOOLEAN, name = "flag", isParam = true)
        val value = Local(2, IrType.INT, name = "value", isParam = true)
        cls.method("m", IrType.BOOLEAN, listOf(IrType.BOOLEAN, IrType.INT)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("flag", "value")
            body(ret(expr(IfInstruction(ConditionOp.EQ, listOf(flag.ref(), value.ref())))))
        }
        assertThatCode(generate(cls)).containsOne("return (flag ? 1 : 0) == value;")
    }

    @Test
    fun booleanOrderingUsesNumericValues() {
        val cls = irClass("a.C")
        val a = Local(1, IrType.BOOLEAN, name = "a", isParam = true)
        val b = Local(2, IrType.BOOLEAN, name = "b", isParam = true)
        cls.method("m", IrType.BOOLEAN, listOf(IrType.BOOLEAN, IrType.BOOLEAN)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("a", "b")
            body(ret(expr(IfInstruction(ConditionOp.LT, listOf(a.ref(), b.ref())))))
        }
        assertThatCode(generate(cls)).containsOne("return (a ? 1 : 0) < (b ? 1 : 0);")
    }
}
