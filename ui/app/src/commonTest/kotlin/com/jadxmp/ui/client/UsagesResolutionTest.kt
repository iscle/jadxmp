package com.jadxmp.ui.client

import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.CodeAnnotation
import com.jadxmp.codegen.CodeMetadata
import com.jadxmp.codegen.DefinitionAnnotation
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.codegen.RefKind
import com.jadxmp.codegen.ReferenceAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for the pure find-usages helpers in [CoreApiDecompilerClient]: [referenceOnLine] (position →
 * exact engine ref, the query key) and the label projections. Synthetic [CodeMetadata], no engine — the
 * same style as [JavaColorizerTest].
 */
class UsagesResolutionTest {

    private fun metadata(vararg entries: Pair<Int, CodeAnnotation>): CodeMetadata =
        CodeMetadata.build(entries.toMap(), emptyMap())

    // ── referenceOnLine ──────────────────────────────────────────────────────

    @Test
    fun resolvesReferenceOnItsLine() {
        val src = "class A {\n    B field;\n}"
        val off = src.indexOf("B")
        val meta = metadata(off to ReferenceAnnotation(ClassNodeRef("pkg.B")))
        assertEquals(ClassNodeRef("pkg.B"), referenceOnLine(src, meta, line = 2, token = "B", kindHint = TokenKind.TYPE))
    }

    @Test
    fun ignoresAMatchOnADifferentLine() {
        val src = "class A {\n    B field;\n}"
        val off = src.indexOf("B")
        val meta = metadata(off to ReferenceAnnotation(ClassNodeRef("pkg.B")))
        // "B" is on line 2, so querying line 1 (or 3) resolves nothing.
        assertNull(referenceOnLine(src, meta, line = 1, token = "B", kindHint = TokenKind.TYPE))
        assertNull(referenceOnLine(src, meta, line = 3, token = "B", kindHint = TokenKind.TYPE))
    }

    @Test
    fun disambiguatesFieldVsMethodByTokenKind() {
        // One line uses `count` as both a field read and a method call.
        val src = "y = count + count();"
        val fieldOff = src.indexOf("count")
        val methodOff = src.indexOf("count", fieldOff + 1)
        val meta = metadata(
            fieldOff to ReferenceAnnotation(FieldNodeRef("A", "count")),
            methodOff to ReferenceAnnotation(MethodNodeRef("A", "count", emptyList())),
        )
        assertEquals(FieldNodeRef("A", "count"), referenceOnLine(src, meta, 1, "count", TokenKind.FIELD))
        assertEquals(MethodNodeRef("A", "count", emptyList()), referenceOnLine(src, meta, 1, "count", TokenKind.METHOD))
        // With no kind preference, the first indexed match on the line wins (deterministic).
        assertEquals(FieldNodeRef("A", "count"), referenceOnLine(src, meta, 1, "count", TokenKind.PLAIN))
    }

    @Test
    fun rejectsALongerIdentifierThatMerelyStartsWithTheToken() {
        val src = "foobar = foo;"
        val fooBarOff = src.indexOf("foobar")
        val fooOff = src.indexOf("foo", fooBarOff + 1)
        val meta = metadata(
            fooBarOff to ReferenceAnnotation(FieldNodeRef("A", "foobar")),
            fooOff to ReferenceAnnotation(FieldNodeRef("A", "foo")),
        )
        // Must match the standalone `foo` (offset 9), never the `foo` prefix inside `foobar` (offset 0).
        assertEquals(FieldNodeRef("A", "foo"), referenceOnLine(src, meta, 1, "foo", TokenKind.FIELD))
    }

    @Test
    fun resolvesADefinitionSiteToo() {
        val src = "void run() {}"
        val off = src.indexOf("run")
        val meta = metadata(off to DefinitionAnnotation(MethodNodeRef("A", "run", emptyList())))
        assertEquals(MethodNodeRef("A", "run", emptyList()), referenceOnLine(src, meta, 1, "run", TokenKind.METHOD))
    }

    @Test
    fun returnsNullForNoMatchEmptyTokenOrOutOfRangeLine() {
        val src = "B field;"
        val meta = metadata(0 to ReferenceAnnotation(ClassNodeRef("pkg.B")))
        assertNull(referenceOnLine(src, meta, 1, "missing", TokenKind.TYPE))
        assertNull(referenceOnLine(src, meta, 1, "", TokenKind.TYPE))
        assertNull(referenceOnLine(src, meta, 9, "B", TokenKind.TYPE))
    }

    // ── label projections ────────────────────────────────────────────────────

    @Test
    fun simpleTypeNameStripsPackagesKeepsArraysAndGenerics() {
        assertEquals("int", simpleTypeName("int"))
        assertEquals("String", simpleTypeName("java.lang.String"))
        assertEquals("String[]", simpleTypeName("java.lang.String[]"))
        assertEquals("Inner", simpleTypeName("com.x.Outer\$Inner"))
        assertEquals("Map<java.lang.String, int>", simpleTypeName("java.util.Map<java.lang.String, int>"))
    }

    @Test
    fun usageSymbolLabelReadsPerKind() {
        assertEquals("Foo", usageSymbolLabel(ClassNodeRef("com.x.Foo")))
        assertEquals("Inner", usageSymbolLabel(ClassNodeRef("com.x.Outer\$Inner")))
        assertEquals("bar(String, int)", usageSymbolLabel(MethodNodeRef("com.x.A", "bar", listOf("java.lang.String", "int"))))
        assertEquals("count", usageSymbolLabel(FieldNodeRef("com.x.A", "count")))
    }

    @Test
    fun enclosingMemberLabelIsAMethodSignatureOrNull() {
        assertEquals("run()", enclosingMemberLabel(MethodNodeRef("A", "run", emptyList())))
        assertEquals("of(String)", enclosingMemberLabel(MethodNodeRef("A", "of", listOf("java.lang.String"))))
        assertNull(enclosingMemberLabel(FieldNodeRef("A", "x")))
        assertNull(enclosingMemberLabel(null))
    }

    @Test
    fun nodeKindMapsRefKind() {
        assertEquals(NodeKind.CLASS, nodeKindOf(RefKind.CLASS))
        assertEquals(NodeKind.METHOD, nodeKindOf(RefKind.METHOD))
        assertEquals(NodeKind.FIELD, nodeKindOf(RefKind.FIELD))
    }
}
