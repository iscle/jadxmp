package com.jadxmp.pipeline.types

import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArrayConstantViewsTest {
    @Test fun sharedBitsHaveIndependentArrayConsumersInEitherOrder() {
        for (wide in listOf(false, true)) for (reverse in listOf(false, true)) {
            val bits = if (wide) 1.0.toBits() else 1.0f.toBits().toLong()
            val integral = if (wide) IrType.LONG else IrType.INT
            val floating = if (wide) IrType.DOUBLE else IrType.FLOAT
            val types = if (reverse) listOf(integral, floating) else listOf(floating, integral)
            val method = TestPipeline.buildMethod(FakeCodeReader(7, listOf(
                Insn(if (wide) Opcode.CONST_WIDE else Opcode.CONST, 0, intArrayOf(0), literal = bits),
                Insn(if (wide) Opcode.MOVE_WIDE else Opcode.MOVE, 1, intArrayOf(2, 0)),
                Insn(Opcode.CONST, 2, intArrayOf(4), literal = 0),
                Insn(if (wide) Opcode.APUT_WIDE else Opcode.APUT, 3, intArrayOf(2, 5, 4)),
                Insn(if (wide) Opcode.APUT_WIDE else Opcode.APUT, 4, intArrayOf(2, 6, 4)),
                Insn(Opcode.RETURN, 5, intArrayOf(0)),
            )), returnType = integral, argTypes = types.map { IrType.array(it) })
            TestPipeline.full(method)
            val stores = method.blocks.flatMap { it.instructions }.filter { it.opcode == IrOpcode.ARRAY_PUT }
            stores.zip(types).forEach { (store, type) ->
                val literal = assertIs<LiteralOperand>(store.getArg(0))
                assertEquals(type, literal.type)
                assertEquals(bits, literal.value)
                assertEquals(store, literal.parent)
            }
            val returned = method.blocks.flatMap { it.instructions }.single { it.opcode == IrOpcode.RETURN }.getArg(0)
            assertEquals(integral, returned.type)
            method.ssaValues.forEach { value -> value.uses.forEach { use ->
                assertEquals(value, use.ssaValue)
                assertEquals(true, use.parent!!.args.any { it === use })
            } }
        }
    }
}
