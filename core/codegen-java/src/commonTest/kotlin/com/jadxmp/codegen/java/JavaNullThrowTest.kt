package com.jadxmp.codegen.java

import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.LocalVar
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test

class JavaNullThrowTest {
    @Test fun provenNullLocalDoesNotAcquireCheckedExceptionRequirement() {
        val assigned = RegisterOperand(0, IrType.THROWABLE)
        val constant = Instruction(IrOpcode.CONST, assigned, listOf(LiteralOperand(0, IrType.THROWABLE)))
        val ssa = SsaValue(0, 0, assigned)
        LocalVar().also { it.name = "failure"; it.type = IrType.THROWABLE; it.addSsaValue(ssa) }
        val use = InstructionOperand(Instruction(IrOpcode.MOVE, args = listOf(
            RegisterOperand(0, IrType.THROWABLE).also { it.ssaValue = ssa })))
        val cls = irClass("example.NullThrow")
        cls.method("test") { body(constant, Instruction(IrOpcode.THROW, args = listOf(use))) }
        assertThatCode(generate(cls)).containsOne("throw null;").doesNotContain("throw failure;")
    }

    @Test fun nullLiteralAndWrappedConstantStayNull() {
        for (operand in listOf(LiteralOperand(0, IrType.THROWABLE),
            InstructionOperand(Instruction(IrOpcode.CONST, args = listOf(LiteralOperand(0, IrType.THROWABLE)))))) {
            val cls = irClass("example.NullThrow")
            cls.method("test") { body(Instruction(IrOpcode.THROW, args = listOf(operand))) }
            assertThatCode(generate(cls)).containsOne("throw null;")
        }
    }

    @Test fun effectfulThrowableExpressionIsEvaluated() {
        val call = staticInvoke(IrType.objectType("example.Source"), "failure", IrType.THROWABLE, emptyList(), emptyList())
        val cls = irClass("example.NullThrow")
        cls.method("test") { body(Instruction(IrOpcode.THROW, args = listOf(InstructionOperand(call)))) }
        assertThatCode(generate(cls)).containsOne("throw Source.failure();").doesNotContain("throw null;")
    }

    @Test fun unknownThrowableIsNotReplacedWithNull() {
        val cls = irClass("example.NullThrow")
        cls.method("test", argTypes = listOf(IrType.THROWABLE)) {
            body(Instruction(IrOpcode.THROW, args = listOf(RegisterOperand(0, IrType.THROWABLE))))
        }
        assertThatCode(generate(cls)).doesNotContain("throw null;")
    }
}
