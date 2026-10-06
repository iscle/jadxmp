package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.type.IrType
import com.jadxmp.ir.insn.LiteralOperand
import kotlin.test.assertEquals
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class KotlinBoxedReferenceTest {
    @Test
    fun boxedReferenceTypesStaySeparateFromPrimitiveTypes() {
        val cls = irClass("a.Box")
        val boxed = IrType.objectType("java.lang.Boolean")
        val input = Local(0, boxed, "input", isParam = true)
        cls.method("echo", boxed, listOf(boxed)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("input")
            body(ret(input.ref()))
        }
        val primitive = Local(0, IrType.BOOLEAN, "input", isParam = true)
        cls.method("primitive", IrType.BOOLEAN, listOf(IrType.BOOLEAN)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("input")
            body(ret(primitive.ref()))
        }
        assertThatCode(generate(cls))
            .containsOne("import java.lang.Boolean as JvmBoolean")
            .containsOne("fun echo(input: JvmBoolean?): JvmBoolean?")
            .containsOne("fun primitive(input: Boolean): Boolean")
    }

    @Test
    fun constructorKeepsJvmAllocationAndReferenceReturnType() {
        val cls = irClass("a.Box")
        val boxed = IrType.objectType("java.lang.Boolean")
        cls.method("allocate", boxed) { body(ret(expr(constructor(boxed, listOf(IrType.BOOLEAN), listOf(lit(1, IrType.BOOLEAN)))))) }
        assertThatCode(generate(cls)).containsOne("fun allocate(): JvmBoolean").containsOne("return JvmBoolean(true)")
    }
    @Test
    fun importedPrimitiveAliasesAlsoNameFloatingAndLongLiteralOwners() {
        val alias: (String) -> String = { "Alias$it" }
        assertEquals("AliasLong.MIN_VALUE", KotlinLiterals.format(LiteralOperand(Long.MIN_VALUE, IrType.LONG), alias))
        assertEquals("AliasFloat.fromBits(2143289345)", KotlinLiterals.format(LiteralOperand(0x7fc00001L, IrType.FLOAT), alias))
        assertEquals("AliasDouble.fromBits(AliasLong.MIN_VALUE)", KotlinLiterals.format(LiteralOperand(Long.MIN_VALUE, IrType.DOUBLE), alias))
    }

}
