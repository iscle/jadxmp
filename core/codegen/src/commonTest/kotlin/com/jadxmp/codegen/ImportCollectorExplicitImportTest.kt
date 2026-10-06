package com.jadxmp.codegen

import kotlin.test.*

class ImportCollectorExplicitImportTest {
    @Test fun explicitJavaLangImportOverridesUnmodeledSamePackageTypes() {
        val imports = ImportCollector("p")
        assertEquals("Float", imports.useExplicitClass("java.lang.Float"))
        assertEquals(listOf("java.lang.Float"), imports.imports())
        assertEquals("p.Float", imports.useClass("p.Float"))
    }

    @Test fun alreadyClaimedTypeCannotBeReboundByExplicitImport() {
        val imports = ImportCollector("p")
        assertEquals("Float", imports.useClass("other.Float"))
        assertEquals("java.lang.Float", imports.useExplicitClass("java.lang.Float"))
        assertEquals(listOf("other.Float"), imports.imports())
    }

    @Test fun existingImplicitClaimCanBePromotedOnce() {
        val imports = ImportCollector("p")
        imports.useClass("java.lang.Double")
        repeat(10) { assertEquals("Double", imports.useExplicitClass("java.lang.Double")) }
        assertEquals(listOf("java.lang.Double"), imports.imports())
    }
}
