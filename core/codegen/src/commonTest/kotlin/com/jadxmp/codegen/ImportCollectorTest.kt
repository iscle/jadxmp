package com.jadxmp.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

class ImportCollectorTest {

    @Test
    fun importsForeignClassAndUsesShortName() {
        val ic = ImportCollector("a.b")
        assertEquals("List", ic.useClass("java.util.List"))
        assertEquals(listOf("java.util.List"), ic.imports())
    }

    @Test
    fun javaLangIsNotImported() {
        val ic = ImportCollector("a.b")
        assertEquals("String", ic.useClass("java.lang.String"))
        assertEquals(emptyList(), ic.imports())
    }

    @Test
    fun samePackageIsNotImported() {
        val ic = ImportCollector("a.b")
        assertEquals("Sibling", ic.useClass("a.b.Sibling"))
        assertEquals(emptyList(), ic.imports())
    }

    @Test
    fun clashingSimpleNameStaysFullyQualified() {
        val ic = ImportCollector("a.b")
        assertEquals("Date", ic.useClass("java.util.Date"))
        // Same simple name, different package -> must stay qualified, no second import.
        assertEquals("java.sql.Date", ic.useClass("java.sql.Date"))
        assertEquals(listOf("java.util.Date"), ic.imports())
    }

    @Test
    fun nestedClassImportsTopLevelAndRendersDotted() {
        val ic = ImportCollector("a.b")
        assertEquals("Map.Entry", ic.useClass("java.util.Map\$Entry"))
        assertEquals(listOf("java.util.Map"), ic.imports())
    }

    @Test
    fun repeatedUseIsStable() {
        val ic = ImportCollector("a.b")
        assertEquals("List", ic.useClass("java.util.List"))
        assertEquals("List", ic.useClass("java.util.List"))
        assertEquals(listOf("java.util.List"), ic.imports())
    }
}
