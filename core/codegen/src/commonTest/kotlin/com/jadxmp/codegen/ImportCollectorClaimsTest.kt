package com.jadxmp.codegen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportCollectorClaimsTest {
    @Test fun claimedSimpleNamesTrackSamePackageAndImportedTypes() {
        val imports = ImportCollector("fixture")
        assertFalse(imports.isSimpleNameClaimed("java"))
        imports.useClass("external.java")
        assertTrue(imports.isSimpleNameClaimed("java"))
        assertFalse(imports.isSimpleNameClaimed("external"))
        imports.useClass("fixture.Float")
        assertTrue(imports.isSimpleNameClaimed("Float"))
        imports.useClass("other.Outer${'$'}Inner")
        assertTrue(imports.isSimpleNameClaimed("Outer"))
        assertFalse(imports.isSimpleNameClaimed("Inner"))
    }
}
