package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals

class KotlinBoundaryLiteralTest {
    @Test
    fun minimumLongUsesRepresentableConstant() {
        assertEquals("Long.MIN_VALUE", KotlinLiterals.format(LiteralOperand(Long.MIN_VALUE, IrType.LONG)))
    }

    @Test
    fun negativeDoubleZeroUsesRepresentableRawBits() {
        assertEquals("Double.fromBits(Long.MIN_VALUE)", KotlinLiterals.format(LiteralOperand(Long.MIN_VALUE, IrType.DOUBLE)))
    }
}
