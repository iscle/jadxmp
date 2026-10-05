package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class KotlinJvmStaticInvocationTest {
    @Test
    fun staticJavaOwnersDoNotResolveToKotlinBuiltIns() {
        for (owner in listOf("String", "Boolean", "Byte", "Short", "Long", "Float", "Double")) {
            val cls = irClass("a.C")
            cls.method("m") {
                body(staticInvoke(IrType.objectType("java.lang.$owner"), "probe", IrType.VOID, emptyList(), emptyList()))
            }
            assertThatCode(generate(cls)).containsOne("import java.lang.$owner as Jvm$owner").containsOne("Jvm$owner.probe()")
        }
    }

    @Test
    fun stringValueOfObjectPreservesArrayOverloadSelection() {
        val cls = irClass("a.C")
        val chars = Local(0, IrType.array(IrType.CHAR), "chars", isParam = true)
        cls.method("m", IrType.STRING, listOf(chars.type)) {
            body(ret(expr(staticInvoke(IrType.STRING, "valueOf", IrType.STRING, listOf(IrType.OBJECT), listOf(chars.ref())))))
        }
        assertThatCode(generate(cls)).containsOne("JvmString.valueOf(chars as Any?)")
    }

    @Test
    fun staticImportAliasesAvoidSourceDeclarations() {
        val cls = irClass("a.JvmString")
        cls.fields.add(IrField(cls, "JvmString2", IrType.INT, Flags.PUBLIC))
        val value = Local(0, IrType.INT, "java", isParam = true)
        val local = Local(2, IrType.INT, "JvmString4")
        cls.method("m", IrType.STRING, listOf(IrType.INT, IrType.INT)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("java", "JvmString3")
            body(
                Instruction(IrOpcode.CONST, local.ref(), listOf(intLit(7))),
                ret(expr(staticInvoke(IrType.STRING, "valueOf", IrType.STRING, listOf(IrType.INT), listOf(value.ref())))),
            )
        }
        assertThatCode(generate(cls))
            .containsOne("import java.lang.String as JvmString5")
            .containsOne("return JvmString5.valueOf(java)")
            .containsOne("var JvmString4: Int = 7")
    }

    @Test
    fun staticAliasesAvoidDefaultPackageReferencedTypes() {
        val cls = irClass("C")
        cls.fields.add(IrField(cls, "dependency", IrType.objectType("JvmString"), Flags.PUBLIC))
        cls.method("m", IrType.STRING) {
            body(ret(expr(staticInvoke(IrType.STRING, "valueOf", IrType.STRING, listOf(IrType.INT), listOf(intLit(73))))))
        }
        assertThatCode(generate(cls))
            .containsOne("import java.lang.String as JvmString2")
            .containsOne("dependency: JvmString")
            .containsOne("return JvmString2.valueOf(73)")
    }

    @Test
    fun unrelatedStaticOwnerKeepsNormalRendering() {
        val cls = irClass("a.C")
        cls.method("m") {
            body(staticInvoke(IrType.objectType("a.String"), "valueOf", IrType.STRING, listOf(IrType.INT), listOf(intLit(42))))
        }
        assertThatCode(generate(cls)).containsOne("String.valueOf(42)").doesNotContain("java.lang.String.valueOf")
    }
}
