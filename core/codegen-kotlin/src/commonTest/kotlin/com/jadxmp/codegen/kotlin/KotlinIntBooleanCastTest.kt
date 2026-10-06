package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class KotlinIntBooleanCastTest {
    @Test fun normalizedIntegerToBooleanUsesNonzeroTruth() {
        val value = Local(1, IrType.INT, name = "value", isParam = true)
        val cls = irClass("example.BooleanCast")
        cls.method("test", returnType = IrType.BOOLEAN, argTypes = listOf(IrType.INT)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("value")
            body(ret(expr(cast(IrType.BOOLEAN, value.ref()))))
        }
        assertThatCode(generate(cls)).containsOne("return value != 0").doesNotContain(".toInt()")
    }

    @Test fun integerCallIsEvaluatedOnceBeforeBooleanConversion() {
        val call = staticInvoke(IrType.objectType("example.Source"), "value", IrType.INT, emptyList(), emptyList(), result = reg(2, IrType.INT))
        val cls = irClass("example.BooleanCast")
        cls.method("test", returnType = IrType.BOOLEAN) { body(ret(expr(cast(IrType.BOOLEAN, expr(call))))) }
        assertThatCode(generate(cls)).containsOne("Source.value() != 0").doesNotContain(".toInt()")
    }
}
