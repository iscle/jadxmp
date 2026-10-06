package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class KotlinArrayInstanceOfTest {
    @Test fun referenceArrayChecksUseExactClassAfterEvaluatingOperandAndCheckingNull() {
        val cls = irClass("Example")
        val value = Local(0, IrType.OBJECT, "arrayInstance", isParam = true)
        cls.method("test", IrType.BOOLEAN, listOf(IrType.OBJECT)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("arrayInstance")
            body(ret(expr(instanceOf(IrType.array(IrType.STRING), value.ref()))))
        }
        assertThatCode(generate(cls))
            .containsOne("when (val arrayInstance2 = arrayInstance) { null -> false; else -> Array<String?>::class.java.isInstance(arrayInstance2) }")
            .doesNotContain(" is Array<")
    }

    @Test fun wrappedOperandIsCapturedOnceBeforeResolvingArrayClass() {
        val cls = irClass("Example")
        cls.method("test", IrType.BOOLEAN) {
            val effect = staticInvoke(IrType.objectType("SideEffects"), "value", IrType.OBJECT, emptyList(), emptyList())
            body(ret(expr(instanceOf(IrType.array(IrType.STRING), expr(effect)))))
        }
        assertThatCode(generate(cls)).containsOne("SideEffects.value()")
            .containsOne("when (val arrayInstance = SideEffects.value()) { null -> false; else -> Array<String?>::class.java.isInstance(arrayInstance) }")
    }

    @Test fun nestedPrimitiveArraysNeedExactClassButOneDimensionalPrimitiveArraysUseIs() {
        val cls = irClass("Example")
        val value = Local(0, IrType.OBJECT, "value", isParam = true)
        for ((name, type) in listOf("nested" to IrType.array(IrType.array(IrType.INT)), "primitive" to IrType.array(IrType.INT))) {
            cls.method(name, IrType.BOOLEAN, listOf(IrType.OBJECT)) {
                this[CodegenKeys.PARAM_NAMES] = listOf("value")
                body(ret(expr(instanceOf(type, value.ref()))))
            }
        }
        assertThatCode(generate(cls)).containsOne("Array<IntArray?>::class.java.isInstance(")
            .containsOne("value is IntArray")
    }
}
