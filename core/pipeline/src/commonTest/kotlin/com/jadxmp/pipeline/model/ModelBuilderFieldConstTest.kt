package com.jadxmp.pipeline.model

import com.jadxmp.input.EncodedValue
import com.jadxmp.input.EncodedValueType
import com.jadxmp.ir.node.IrFieldConst
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.support.FakeClassData
import com.jadxmp.pipeline.support.FakeCodeLoader
import com.jadxmp.pipeline.support.FakeFieldData
import com.jadxmp.pipeline.support.FakeFieldRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelBuilderFieldConstTest {

    private fun buildField(fieldType: String, const: EncodedValue?): com.jadxmp.ir.node.IrField {
        val ref = FakeFieldRef("Lcom/example/Foo;", "F", fieldType)
        val cls = FakeClassData(
            type = "Lcom/example/Foo;",
            fields = listOf(FakeFieldData(ref, constValue = const)),
        )
        val root = ModelBuilder.build(FakeCodeLoader(listOf(cls)))
        return root.classes.single().fields.single()
    }

    @Test
    fun intConstantIsSurfaced() {
        val const = buildField("I", EncodedValue(EncodedValueType.INT, 255)).constValue
        assertEquals(IrFieldConst.Primitive(255L, IrType.INT), const)
    }

    @Test
    fun stringConstantIsSurfaced() {
        val const = buildField("Ljava/lang/String;", EncodedValue(EncodedValueType.STRING, "hi")).constValue
        assertEquals(IrFieldConst.Str("hi"), const)
    }

    @Test
    fun floatConstantUsesRawBits() {
        val const = buildField("F", EncodedValue(EncodedValueType.FLOAT, 1.5f)).constValue
        assertEquals(IrFieldConst.Primitive(1.5f.toRawBits().toLong(), IrType.FLOAT), const)
    }

    @Test
    fun booleanConstantMapsToBits() {
        assertEquals(
            IrFieldConst.Primitive(1L, IrType.BOOLEAN),
            buildField("Z", EncodedValue(EncodedValueType.BOOLEAN, true)).constValue,
        )
        assertEquals(
            IrFieldConst.Primitive(0L, IrType.BOOLEAN),
            buildField("Z", EncodedValue(EncodedValueType.BOOLEAN, false)).constValue,
        )
    }

    @Test
    fun noConstValueLeavesNull() {
        assertNull(buildField("I", null).constValue)
    }

    @Test
    fun typeAndEnumEncodedConstantsAreLeftNullNotMisMapped() {
        // Non-lossy: TYPE/ENUM/ARRAY can't be modelled as a declaration-site constant, so leave null
        // (the field then honestly gets no initializer rather than a wrong value).
        assertNull(buildField("Ljava/lang/Class;", EncodedValue(EncodedValueType.TYPE, "Lcom/x/Y;")).constValue)
        assertNull(buildField("I", EncodedValue(EncodedValueType.NULL, null)).constValue)
    }

    @Test
    fun longConstantIsSurfaced() {
        val const = buildField("J", EncodedValue(EncodedValueType.LONG, 42L)).constValue
        assertTrue(const is IrFieldConst.Primitive && const.bits == 42L && const.type == IrType.LONG)
    }
}
