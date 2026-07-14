package com.jadxmp.codegen.kotlin

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
 * Kotlin-backend parity for the coalesced-null return fix (see the Java `JavaNullReturnCoalescedTest`):
 * a `const 0` (null) coalesced into an incompatibly-typed local and returned where the method type
 * differs must render `return null`, not the wrongly-typed variable name.
 */
class KotlinNullReturnCoalescedTest {

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
            .containsOne("return null")
            .doesNotContain("return inputStream")
    }

    @Test
    fun coalescedNullReturnedAsCompatibleTypeKeepsVariable() {
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
            .containsOne("return inputStream")
            .doesNotContain("return null")
    }
}
