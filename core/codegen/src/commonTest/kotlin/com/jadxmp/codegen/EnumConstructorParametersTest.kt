package com.jadxmp.codegen

import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnumConstructorParametersTest {
    @Test fun onlyTheExactDescriptorPrefixProjects() {
        assertTrue(EnumConstructorParameters.hasSyntheticPrefix(listOf(IrType.STRING, IrType.INT)))
        assertTrue(EnumConstructorParameters.hasSyntheticPrefix(listOf(IrType.STRING, IrType.INT, IrType.LONG, IrType.STRING)))
        assertFalse(EnumConstructorParameters.hasSyntheticPrefix(emptyList()))
        assertFalse(EnumConstructorParameters.hasSyntheticPrefix(listOf(IrType.STRING)))
        assertFalse(EnumConstructorParameters.hasSyntheticPrefix(listOf(IrType.OBJECT, IrType.INT)))
        assertFalse(EnumConstructorParameters.hasSyntheticPrefix(listOf(IrType.STRING, IrType.LONG)))
    }
}
