package com.jadxmp.codegen.java

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

/**
 * Casts involving `boolean`. In DEX a boolean is an int, so a value typed `boolean` can reach a use
 * site that wants a numeric primitive; a plain `(byte) z` is rejected by javac. These verify the
 * conditional coercion (jadx-compatible) that keeps the output compilable.
 */
class JavaCastTest {

    private val foo = IrType.objectType("a.Foo")
    private val z = Local(1, IrType.BOOLEAN, name = "z", isParam = true)
    private val i = Local(2, IrType.INT, name = "i", isParam = true)

    /** `void m(boolean z, int i) { <stmt> }` around a single statement. */
    private fun withStatement(stmt: com.jadxmp.ir.insn.Instruction): String {
        val cls = irClass("a.Foo")
        cls.method("m", argTypes = listOf(IrType.BOOLEAN, IrType.INT)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("z", "i")
            body(stmt)
        }
        return generate(cls)
    }

    @Test
    fun booleanToByteBecomesConditionalNotIllegalCast() {
        val call = staticInvoke(foo, "write", IrType.VOID, listOf(IrType.BYTE), listOf(expr(cast(IrType.BYTE, z.ref()))))
        assertThatCode(withStatement(call))
            .containsOne("Foo.write(z ? (byte) 1 : (byte) 0);")
            // The illegal form `(byte) z` (boolean cannot be converted to byte) must never appear.
            .doesNotContain("(byte) z")
    }

    @Test
    fun booleanToShortAndCharAlsoCast() {
        val toShort = staticInvoke(foo, "s", IrType.VOID, listOf(IrType.SHORT), listOf(expr(cast(IrType.SHORT, z.ref()))))
        assertThatCode(withStatement(toShort)).containsOne("z ? (short) 1 : (short) 0")

        val toChar = staticInvoke(foo, "c", IrType.VOID, listOf(IrType.CHAR), listOf(expr(cast(IrType.CHAR, z.ref()))))
        assertThatCode(withStatement(toChar)).containsOne("z ? (char) 1 : (char) 0")
    }

    @Test
    fun booleanToIntUsesBareOneZero() {
        val call = staticInvoke(foo, "write", IrType.VOID, listOf(IrType.INT), listOf(expr(cast(IrType.INT, z.ref()))))
        assertThatCode(withStatement(call))
            .containsOne("Foo.write(z ? 1 : 0);")
            .doesNotContain("(int) z")
    }

    @Test
    fun numericToBooleanBecomesNotEqualsZero() {
        val call = staticInvoke(foo, "flag", IrType.VOID, listOf(IrType.BOOLEAN), listOf(expr(cast(IrType.BOOLEAN, i.ref()))))
        assertThatCode(withStatement(call))
            .containsOne("Foo.flag(i != 0);")
            .doesNotContain("(boolean) i")
    }

    @Test
    fun booleanArgumentToIntParamCoercedWithoutAnyCastNode() {
        // The corpus case (TestBooleanToInt): a boolean is passed straight to write(int) — no CAST
        // instruction, the coercion is implicit in DEX. Codegen must still emit compilable Java.
        val call = staticInvoke(foo, "write", IrType.VOID, listOf(IrType.INT), listOf(z.ref()))
        assertThatCode(withStatement(call))
            .containsOne("Foo.write(z ? 1 : 0);")
            .doesNotContain("(int) z")
    }

    @Test
    fun booleanArgumentToByteParamCoercedWithoutAnyCastNode() {
        val call = staticInvoke(foo, "write", IrType.VOID, listOf(IrType.BYTE), listOf(z.ref()))
        assertThatCode(withStatement(call))
            .containsOne("Foo.write(z ? (byte) 1 : (byte) 0);")
            .doesNotContain("(byte) z")
    }

    @Test
    fun booleanReturnedFromIntMethodIsCoerced() {
        val cls = irClass("a.Foo")
        cls.method("m", returnType = IrType.INT, argTypes = listOf(IrType.BOOLEAN)) {
            this[CodegenKeys.PARAM_NAMES] = listOf("z")
            body(ret(z.ref()))
        }
        assertThatCode(generate(cls)).containsOne("return z ? 1 : 0;")
    }

    @Test
    fun intConstantReturnedFromBooleanMethodBecomesBooleanLiteral() {
        // The corpus case (TestBooleanToInt2.getValue): `const 0; return v0` in a boolean method. The
        // literal is NARROW/int-typed, so it must be rendered as `false`, not `0`.
        val cls = irClass("a.Foo")
        // Direct literal operand.
        cls.method("getFalse", returnType = IrType.BOOLEAN) {
            body(ret(LiteralOperand(0, IrType.INT)))
        }
        // Literal wrapped in a CONST instruction (the inlined-single-use form).
        cls.method("getTrue", returnType = IrType.BOOLEAN) {
            body(ret(expr(Instruction(IrOpcode.CONST, args = listOf(LiteralOperand(1, IrType.INT))))))
        }
        assertThatCode(generate(cls))
            .containsOne("return false;")
            .containsOne("return true;")
            .doesNotContain("return 0;")
            .doesNotContain("return 1;")
    }

    @Test
    fun normalIntArgumentToIntParamIsUntouched() {
        // A numeric arg into a numeric param must not be turned into a ternary.
        val call = staticInvoke(foo, "write", IrType.VOID, listOf(IrType.INT), listOf(i.ref()))
        assertThatCode(withStatement(call))
            .containsOne("Foo.write(i);")
            .doesNotContain("?")
    }

    @Test
    fun ordinaryNumericCastIsUnaffected() {
        // A genuine numeric-to-numeric cast must still render as a plain cast.
        val call = staticInvoke(foo, "b", IrType.VOID, listOf(IrType.BYTE), listOf(expr(cast(IrType.BYTE, i.ref()))))
        assertThatCode(withStatement(call))
            .containsOne("Foo.b((byte) i);")
            .doesNotContain("?")
    }
}
