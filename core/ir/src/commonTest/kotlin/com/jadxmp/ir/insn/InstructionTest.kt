package com.jadxmp.ir.insn

import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InstructionTest {

    private fun reg(n: Int, t: IrType = IrType.INT) = RegisterOperand(n, t)

    @Test
    fun argsGetParentLinkOnConstruction() {
        val a = reg(0)
        val b = reg(1)
        val insn = Instruction(IrOpcode.ARITH, result = reg(2), args = listOf(a, b))
        assertSame(insn, a.parent)
        assertSame(insn, b.parent)
        assertSame(insn, insn.result!!.parent)
        assertEquals(2, insn.argCount)
    }

    @Test
    fun mutationMaintainsParentLinks() {
        val insn = Instruction(IrOpcode.INVOKE)
        val a = reg(0)
        insn.addArg(a)
        assertSame(insn, a.parent)

        val b = reg(1)
        insn.setArg(0, b)
        assertNull(a.parent)
        assertSame(insn, b.parent)

        val removed = insn.removeArg(0)
        assertSame(b, removed)
        assertNull(b.parent)
        assertEquals(0, insn.argCount)
    }

    @Test
    fun replaceArgReplacesFirstMatch() {
        val a = reg(0)
        val insn = Instruction(IrOpcode.RETURN, args = listOf(a))
        val wrap = InstructionOperand(Instruction(IrOpcode.CONST, result = reg(5)))
        assertTrue(insn.replaceArg(a, wrap))
        assertSame(wrap, insn.getArg(0))
        assertSame(insn, wrap.parent)
        assertNull(a.parent)
    }

    @Test
    fun settingResultUpdatesBackLinks() {
        val insn = Instruction(IrOpcode.CONST)
        val r1 = reg(0)
        insn.result = r1
        assertSame(insn, r1.parent)
        val r2 = reg(1)
        insn.result = r2
        assertNull(r1.parent)
        assertSame(insn, r2.parent)
    }

    @Test
    fun nestedOperandFormsExpressionTree() {
        val inner = Instruction(IrOpcode.ARITH, result = reg(3, IrType.INT))
        val wrap = InstructionOperand(inner)
        assertTrue(wrap.isNested)
        assertEquals(IrType.INT, wrap.type) // mirrors wrapped result type
        assertSame(inner, wrap.instruction)
    }

    @Test
    fun operandKindFlags() {
        assertTrue(reg(0).isRegister)
        assertTrue(LiteralOperand(0, IrType.INT).isLiteral)
        assertTrue(LiteralOperand(0, IrType.INT).isZero)
    }

    @Test
    fun arithInstructionCarriesOperatorAndOpcode() {
        val add = ArithInstruction(ArithOp.ADD, result = reg(0), args = listOf(reg(1), reg(2)))
        assertEquals(IrOpcode.ARITH, add.opcode)
        assertEquals(ArithOp.ADD, add.op)

        val neg = ArithInstruction(ArithOp.NEGATE, result = reg(0), args = listOf(reg(1)))
        assertEquals(IrOpcode.NEG, neg.opcode)
    }

    @Test
    fun ifInstructionCarriesConditionOp() {
        val insn = IfInstruction(ConditionOp.LT, args = listOf(reg(0), reg(1)))
        assertEquals(IrOpcode.IF, insn.opcode)
        assertEquals(ConditionOp.LT, insn.condition)
    }

    @Test
    fun conditionOpNegation() {
        assertEquals(ConditionOp.NE, ConditionOp.EQ.negate())
        assertEquals(ConditionOp.GE, ConditionOp.LT.negate())
        assertEquals(ConditionOp.LE, ConditionOp.GT.negate())
        for (op in ConditionOp.entries) {
            assertEquals(op, op.negate().negate())
        }
    }
}
