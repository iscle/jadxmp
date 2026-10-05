package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class KotlinReferenceNullabilityTest {
    @Test
    fun objectParametersAndIdentityReturnsDoNotAddEntryChecks() {
        val cls = irClass("a.C")
        val value = Local(0, IrType.OBJECT, "value", isParam = true)
        cls.method("identity", IrType.OBJECT, listOf(IrType.OBJECT)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("value")
            body(ret(value.ref()))
        }
        assertThatCode(generate(cls)).containsOne("fun identity(value: Any?): Any?")
    }

    @Test
    fun mutableReferenceFieldsHaveJvmNullDefaults() {
        val cls = irClass("a.C")
        cls.fields.add(IrField(cls, "value", IrType.STRING, Flags.PUBLIC))
        assertThatCode(generate(cls)).containsOne("var value: String? = null").doesNotContain("lateinit")
    }

    @Test
    fun checkCastPreservesNullableParameter() {
        val cls = irClass("a.C")
        val value = Local(0, IrType.OBJECT, "value", isParam = true)
        cls.method("cast", IrType.STRING, listOf(IrType.OBJECT)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("value")
            body(ret(expr(checkCast(IrType.STRING, value.ref()))))
        }
        assertThatCode(generate(cls)).containsOne("return value as String?")
    }

    @Test
    fun nullableThrowableParameterFailsOnlyAtThrow() {
        val cls = irClass("a.C")
        val value = Local(0, IrType.THROWABLE, "value", isParam = true)
        cls.method("fail", argTypes = listOf(IrType.THROWABLE)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("value")
            body(Instruction(IrOpcode.THROW, args = listOf(value.ref())))
        }
        assertThatCode(generate(cls)).containsOne("value: Throwable?").containsOne("throw value!!")
    }
    @Test
    fun unknownJvmResultsStayNullableInExplicitLocals() {
        val cls = irClass("a.C")
        val value = Local(0, IrType.OBJECT, "value")
        cls.method("get", IrType.OBJECT) {
            body(staticInvoke(IrType.objectType("a.External"), "get", IrType.OBJECT, emptyList(), emptyList(), value.ref()), ret(value.ref()))
        }
        assertThatCode(generate(cls)).containsOne("var value: Any? = External.get()").containsOne("fun get(): Any?")
    }

    @Test
    fun abstractAndNativeReferenceReturnContractsAcceptNull() {
        val cls = irClass("a.C", accessFlags = Flags.PUBLIC or Flags.ABSTRACT)
        cls.method("abstractValue", IrType.STRING, accessFlags = Flags.PUBLIC or Flags.ABSTRACT)
        cls.method("nativeValue", IrType.STRING, accessFlags = Flags.PUBLIC or Flags.STATIC or 0x100)
        assertThatCode(generate(cls)).containsOne("abstract fun abstractValue(): String?").containsOne("fun nativeValue(): String?")
    }

    @Test
    fun nullableAnyToStringOverrideIsDiagnosedInsteadOfAsserted() {
        val cls = irClass("a.C")
        cls.method("toString", IrType.STRING) { body(ret(lit(0, IrType.STRING))) }
        assertThatCode(generate(cls))
            .containsOne("// JADXMP ERROR: nullable JVM return cannot implement Kotlin Any.toString contract")
            .containsOne("return null")
            .doesNotContain("!!")
    }

}
