package com.jadxmp.codegen.java

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class JavaModifierAndLogicTest {

    @Test
    fun topLevelClassStripsIllegalMemberModifiers() {
        // A '$'-named class that could NOT be nested (no outer in the model) stays top-level: it keeps its
        // full simple name (`Cls$Inner`, not `Inner`) so `class Cls$Inner` matches file `Cls$Inner.java`,
        // and the file-scope-illegal `private`/`static` are stripped (only `final` is legal here).
        val cls = irClass("a.Cls\$Inner", accessFlags = Flags.PRIVATE or Flags.STATIC or Flags.FINAL)
        assertThatCode(generate(cls))
            .containsOne("final class Cls\$Inner {")
            .doesNotContain("private")
            .doesNotContain("static")
    }

    private fun booleanXorClass(op: ArithOp, literal: Int, name: String): String {
        val z = Local(1, IrType.BOOLEAN, name = "z", isParam = true)
        val cls = irClass("a.Foo")
        cls.method(name, returnType = IrType.BOOLEAN, argTypes = listOf(IrType.BOOLEAN)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("z")
            body(ret(expr(arith(op, z.ref(), intLit(literal)))))
        }
        return generate(cls)
    }

    @Test
    fun booleanXorWithIntLiteralRendersBoolean() {
        // `z ^ 1` must be `z ^ true` (javac rejects `boolean ^ int`), and NOT be wrapped in `!= 0`.
        assertThatCode(booleanXorClass(ArithOp.XOR, 1, "t"))
            .containsOne("return z ^ true;")
            .doesNotContain("^ 1")
            .doesNotContain("!= 0")
    }

    @Test
    fun booleanAndOrWithIntLiteralRenderBoolean() {
        assertThatCode(booleanXorClass(ArithOp.AND, 0, "t")).containsOne("return z & false;")
        assertThatCode(booleanXorClass(ArithOp.OR, 1, "t")).containsOne("return z | true;")
    }

    @Test
    fun normalIntBitwiseIsUntouched() {
        val a = Local(1, IrType.INT, name = "a", isParam = true)
        val b = Local(2, IrType.INT, name = "b", isParam = true)
        val cls = irClass("a.Foo")
        cls.method("m", returnType = IrType.INT, argTypes = listOf(IrType.INT, IrType.INT)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("a", "b")
            body(ret(expr(arith(ArithOp.XOR, a.ref(), b.ref()))))
        }
        assertThatCode(generate(cls))
            .containsOne("return a ^ b;")
            .doesNotContain("true")
            .doesNotContain("false")
    }
}
