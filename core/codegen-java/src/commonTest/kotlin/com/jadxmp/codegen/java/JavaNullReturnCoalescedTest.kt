package com.jadxmp.codegen.java

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.LocalVar
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

/**
 * Regression for the coalesced-null return bug (corpus `trycatch/TestFinally3`): a register holding a
 * `const 0` (null) is coalesced by out-of-SSA with an incompatibly-typed local (an `InputStream`), then
 * returned where the method type is `byte[]`. Rendering the coalesced variable name is a type error
 * (`InputStream` cannot be converted to `byte[]`); the value IS null, so the fix emits `return null;`.
 */
class JavaNullReturnCoalescedTest {

    private val inputStream = IrType.objectType("java.io.InputStream")
    private val byteArr = IrType.array(IrType.BYTE)

    @Test
    fun coalescedNullReturnedAsIncompatibleArrayIsNullLiteral() {
        val constResult = RegisterOperand(0, inputStream)
        val constInsn = Instruction(
            IrOpcode.CONST,
            result = constResult,
            args = listOf(LiteralOperand(0, inputStream)),
        )
        val ssa = SsaValue(0, 0, constResult)
        LocalVar().also {
            it.name = "inputStream"
            it.type = inputStream
            it.addSsaValue(ssa)
        }
        val useRef = RegisterOperand(0, inputStream).also { it.ssaValue = ssa }
        val cls = irClass("a.Foo")
        cls.method("test", returnType = byteArr) { body(constInsn, ret(useRef)) }

        assertThatCode(generate(cls))
            .containsOne("return null;")
            .doesNotContain("return inputStream;")
    }

    @Test
    fun coalescedNullOfArrayReturnedAsIncompatibleObjectIsNullLiteral() {
        // The mirror direction: local typed `byte[]`, returned where the method type is `InputStream`.
        val constResult = RegisterOperand(0, byteArr)
        val constInsn = Instruction(
            IrOpcode.CONST,
            result = constResult,
            args = listOf(LiteralOperand(0, byteArr)),
        )
        val ssa = SsaValue(0, 0, constResult)
        LocalVar().also {
            it.name = "buf"
            it.type = byteArr
            it.addSsaValue(ssa)
        }
        val useRef = RegisterOperand(0, byteArr).also { it.ssaValue = ssa }
        val cls = irClass("a.Foo")
        cls.method("test", returnType = inputStream) { body(constInsn, ret(useRef)) }

        assertThatCode(generate(cls))
            .containsOne("return null;")
            .doesNotContain("return buf;")
    }

    @Test
    fun coalescedNullReturnedAsCompatibleTypeKeepsVariable() {
        // Same coalesced-null shape, but the local type EQUALS the return type — no type error, so the
        // variable must be kept (no spurious `return null;` churn).
        val constResult = RegisterOperand(0, inputStream)
        val constInsn = Instruction(
            IrOpcode.CONST,
            result = constResult,
            args = listOf(LiteralOperand(0, inputStream)),
        )
        val ssa = SsaValue(0, 0, constResult)
        LocalVar().also {
            it.name = "inputStream"
            it.type = inputStream
            it.addSsaValue(ssa)
        }
        val useRef = RegisterOperand(0, inputStream).also { it.ssaValue = ssa }
        val cls = irClass("a.Foo")
        cls.method("test", returnType = inputStream) { body(constInsn, ret(useRef)) }

        assertThatCode(generate(cls))
            .containsOne("return inputStream;")
            .doesNotContain("return null;")
    }

    @Test
    fun nonNullReferenceReturnIsUnaffected() {
        // A real object producer (not a null const) returned into an incompatible-looking slot is NOT
        // rewritten — only provably-null-const registers are.
        val s = Local(1, IrType.STRING, name = "s", isParam = true)
        val cls = irClass("a.Foo")
        cls.method("test", returnType = IrType.STRING, argTypes = listOf(IrType.STRING)) {
            this[com.jadxmp.codegen.CodegenKeys.PARAM_NAMES] = listOf("s")
            body(ret(s.ref()))
        }
        assertThatCode(generate(cls))
            .containsOne("return s;")
            .doesNotContain("return null;")
    }
}
