package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class KotlinNullThrowTest {
    @Test fun nullSsaLocalLowersToNullPointerException() {
        val failure = Local(0, IrType.OBJECT, "failure")
        val constant = assign(failure.ref(), Instruction(IrOpcode.CONST, args = listOf(LiteralOperand(0, IrType.OBJECT))))
        // TestIr's linked local keeps the exact SSA definition used by this throw.
        failure.ssaValue.assign = constant.result!!
        val forwarded = InstructionOperand(Instruction(IrOpcode.MOVE, args = listOf(failure.ref())))
        val cls = irClass("example.NullThrow")
        cls.method("test") { body(constant, Instruction(IrOpcode.THROW, args = listOf(forwarded))) }
        assertThatCode(generate(cls)).containsOne("throw kotlin.NullPointerException()")
            .doesNotContain("throw failure")
    }
}
