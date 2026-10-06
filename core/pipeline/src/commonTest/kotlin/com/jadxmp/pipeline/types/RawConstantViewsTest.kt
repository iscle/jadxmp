package com.jadxmp.pipeline.types

import com.jadxmp.ir.insn.*
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.type.IrType
import kotlin.test.*

class RawConstantViewsTest {
    private var next = 0
    private fun value(insn: Instruction): SsaValue = SsaValue(next++, 0, insn.result!!)
    private fun constant(bits: Long, type: IrType = IrType.WIDE) = value(Instruction(
        IrOpcode.CONST, RegisterOperand(next, type), listOf(LiteralOperand(bits, type))))
    private fun read(value: SsaValue) = RegisterOperand(value.regNum, value.assign.type).also(value::addUse)
    private fun move(source: SsaValue, type: IrType = IrType.WIDE) = value(Instruction(
        IrOpcode.MOVE, RegisterOperand(next, type), listOf(read(source))))
    private fun phi(vararg sources: SsaValue): SsaValue {
        val insn = PhiInstruction(RegisterOperand(next, IrType.UNKNOWN))
        val result = value(insn)
        sources.forEachIndexed { i, source -> source.addUse(insn.addIncoming(source.regNum, IrType.UNKNOWN, BasicBlock(i))) }
        return result
    }

    @Test fun equalBitsPhiAndMovesRetainWidthWithoutNumericConversion() {
        val bits = (-0.0).toRawBits()
        val merged = phi(move(constant(bits)), constant(bits))
        val views = RawConstantViews()
        assertEquals(bits, views.literal(read(merged), IrType.DOUBLE)?.value)
        assertEquals(IrType.DOUBLE, views.literal(read(merged), IrType.DOUBLE)?.type)
        assertEquals(bits, views.literal(read(merged), IrType.LONG)?.value)
        assertNull(views.literal(read(merged), IrType.FLOAT))
        assertNull(views.literal(read(phi(constant(1), constant(2))), IrType.LONG))
        assertNull(views.literal(read(phi(constant(1), constant(1, IrType.NARROW))), IrType.LONG))
    }

    @Test fun typedConstantsParametersConversionsAndCyclicPhisAreNotRawConstants() {
        val views = RawConstantViews()
        assertNull(views.literal(read(constant(1, IrType.LONG)), IrType.DOUBLE))
        val param = SsaValue(next++, 0, RegisterOperand(next, IrType.LONG))
        assertNull(views.literal(read(param), IrType.DOUBLE))
        val cast = value(Instruction(IrOpcode.CAST, RegisterOperand(next, IrType.DOUBLE), listOf(read(constant(1)))))
        assertNull(views.literal(read(cast), IrType.DOUBLE))
        val loop = phi(constant(0))
        val def = loop.assign.parent as PhiInstruction
        loop.addUse(def.addIncoming(loop.regNum, IrType.UNKNOWN, BasicBlock(4)))
        assertNull(views.literal(read(loop), IrType.DOUBLE))
        assertNull(views.literal(read(move(constant(1), IrType.NARROW)), IrType.LONG))
    }

    @Test fun longCopyGraphsUseAnIterativeBoundedTraversal() {
        var v = constant(1)
        repeat(20_000) { v = move(v) }
        val views = RawConstantViews()
        repeat(10) { assertEquals(1L, views.literal(read(v), IrType.LONG)?.value) }
    }
}
