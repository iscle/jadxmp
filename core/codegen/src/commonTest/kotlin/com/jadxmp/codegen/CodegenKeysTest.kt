package com.jadxmp.codegen

import com.jadxmp.ir.attr.AttrStorage
import com.jadxmp.ir.attr.SourceAttributes
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CodegenKeysTest {
    @Test
    fun compatibilityKeysRetainCanonicalIdentity() {
        // Attribute lookup is identity-based: duplicating a key silently loses analysis results.
        assertSame(SourceAttributes.PARAM_NAMES, CodegenKeys.PARAM_NAMES)
        assertSame(SourceAttributes.THROWS, CodegenKeys.THROWS)
        assertSame(SourceAttributes.LOOP_INIT, CodegenKeys.LOOP_INIT)
        assertSame(SourceAttributes.LOOP_UPDATE, CodegenKeys.LOOP_UPDATE)
    }

    @Test
    fun analysisMetadataIsVisibleToExistingBackends() {
        val attributes = AttrStorage()
        val exceptions = listOf(IrType.objectType("java.io.IOException"))
        attributes.put(SourceAttributes.THROWS, exceptions)
        attributes.put(SourceAttributes.PARAM_NAMES, listOf("input"))

        assertEquals(exceptions, attributes.get(CodegenKeys.THROWS))
        assertEquals(listOf("input"), attributes.get(CodegenKeys.PARAM_NAMES))
    }
}
