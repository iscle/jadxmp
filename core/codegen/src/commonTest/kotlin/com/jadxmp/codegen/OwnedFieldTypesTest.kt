package com.jadxmp.codegen

import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnedFieldTypesTest {
    @Test fun exactErasedTypesAndArrayRanks() {
        fun matches(a: IrType, b: IrType) = OwnedFieldTypes.equal(a, b) { true }
        assertTrue(matches(IrType.INT, IrType.INT)!!)
        assertFalse(matches(IrType.INT, IrType.BOOLEAN)!!)
        assertTrue(matches(IrType.array(IrType.OBJECT), IrType.array(IrType.OBJECT))!!)
        assertFalse(matches(IrType.array(IrType.INT), IrType.array(IrType.LONG))!!)
        assertFalse(matches(IrType.array(IrType.INT), IrType.INT)!!)
        assertFalse(matches(IrType.UNKNOWN, IrType.UNKNOWN)!!)
    }

    @Test fun longReferenceTextIsChargedBeforeComparisonOnEveryHit() {
        val a = IrType.objectType("long.Name".repeat(1000))
        val b = IrType.objectType((a as IrType.Object).className.toCharArray().concatToString())
        var remaining = 40_000L
        val charge = { amount: Long -> if (amount > remaining) false else { remaining -= amount; true } }
        assertTrue(OwnedFieldTypes.equal(a, b, charge)!!)
        assertTrue(OwnedFieldTypes.equal(a, b, charge)!!)
        assertNull(OwnedFieldTypes.equal(a, b, charge))
    }

    @Test fun deepArraysStopBeforeRecursiveRenderingOrEquality() {
        var type: IrType = IrType.INT
        repeat(10_000) { type = IrType.array(type) }
        var remaining = 10L
        assertNull(OwnedFieldTypes.equal(type, type) { amount ->
            if (amount > remaining) false else { remaining -= amount; true }
        })
        assertEquals(0, remaining)
    }
}
