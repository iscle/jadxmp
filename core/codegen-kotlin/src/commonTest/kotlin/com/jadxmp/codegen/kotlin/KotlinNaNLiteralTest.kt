package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals

class KotlinNaNLiteralTest {
    @Test fun rawFloatNaNsRetainEverySignAndPayloadBit() {
        for (bits in listOf(0x7fc00001, 0x7fffffff, 0x7f800001, 0xffc00000.toInt(), 0xffc12345.toInt(), 0xff800001.toInt())) {
            assertEquals("Float.fromBits($bits)", KotlinLiterals.format(LiteralOperand(bits.toLong(), IrType.FLOAT)))
        }
    }

    @Test fun rawDoubleNaNsRetainEverySignAndPayloadBit() {
        for (bits in listOf(0x7ff8000000000001L, 0x7fffffffffffffffL, 0x7ff0000000000001L,
            0xfff8000000000000UL.toLong(), 0xfff923456789abcdUL.toLong(), 0xfff0000000000001UL.toLong())) {
            assertEquals("Double.fromBits(${bits}L)", KotlinLiterals.format(LiteralOperand(bits, IrType.DOUBLE)))
        }
    }

}
