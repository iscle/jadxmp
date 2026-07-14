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
    fun anonymousStyleNumericSuffixIsNotSplitIntoNestedName() {
        // `Outer$1` is the compiler's anonymous/synthetic marker: it can never be referenced as
        // `Outer.1`. The `$1` must stay part of the simple name so the reference matches the class
        // declaration (`class Lambda$1`) and recompiles — not the broken `Lambda._1`.
        val ic = ImportCollector("inline")
        assertEquals("Lambda\$1", ic.useClass("inline.Lambda\$1"))
        assertEquals(emptyList(), ic.imports())
    }

    @Test
    fun foreignNumericSuffixClassImportsWholeSimpleName() {
        val ic = ImportCollector("a.b")
        // A `$N` class in another package imports the whole simple name (no phantom `Outer` import).
        assertEquals("TestCls\$1", ic.useClass("inner.TestCls\$1"))
        assertEquals(listOf("inner.TestCls\$1"), ic.imports())
    }

    @Test
    fun nestedNumericInsideNamedInnerKeepsOnlyTheNumericLiteral() {
        val ic = ImportCollector("a.b")
        // Only the non-digit `$` is a boundary: `Outer.Inner$2`, importing `Outer`.
        assertEquals("Outer.Inner\$2", ic.useClass("x.y.Outer\$Inner\$2"))
        assertEquals(listOf("x.y.Outer"), ic.imports())
    }

    @Test
    fun repeatedUseIsStable() {
        val ic = ImportCollector("a.b")
        assertEquals("List", ic.useClass("java.util.List"))
        assertEquals("List", ic.useClass("java.util.List"))
        assertEquals(listOf("java.util.List"), ic.imports())
    }
}
