package com.jadxmp.ui.client

import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.CodeAnnotation
import com.jadxmp.codegen.CodeMetadata
import com.jadxmp.codegen.DefinitionAnnotation
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.codegen.ReferenceAnnotation
import com.jadxmp.codegen.VarRef
import com.jadxmp.codegen.VariableAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the lexer↔metadata coloring merge in [JavaColorizer] with **synthetic** metadata — no engine,
 * no dex. Proves that engine annotations override the lexer (precise kind + jump target), that the
 * name heuristic fills in where metadata is absent, and that nested-class separators are reconciled.
 */
class JavaColorizerTest {

    private fun metadata(vararg entries: Pair<Int, CodeAnnotation>): CodeMetadata =
        CodeMetadata.build(entries.toMap(), emptyMap())

    private fun colorize(
        source: String,
        metadata: CodeMetadata?,
        resolveClass: (String) -> NodeId? = { null },
    ): List<CodeToken> =
        JavaColorizer.colorize(source, metadata, resolveClass).flatMap { it.tokens }

    private fun token(tokens: List<CodeToken>, text: String): CodeToken =
        tokens.first { it.text == text }

    @Test
    fun classDefinitionBecomesNavigableType() {
        val source = "class Foo {}"
        val offset = source.indexOf("Foo")
        val tokens = colorize(source, metadata(offset to DefinitionAnnotation(ClassNodeRef("Foo")))) {
            if (it == "Foo") NodeId("cls:Foo") else null
        }
        val foo = token(tokens, "Foo")
        assertEquals(TokenKind.TYPE, foo.kind)
        assertEquals(NodeId("cls:Foo"), foo.definition)
    }

    @Test
    fun referenceToAnotherClassNavigatesToThatClass() {
        val source = "Bar b;"
        val tokens = colorize(source, metadata(0 to ReferenceAnnotation(ClassNodeRef("com.x.Bar")))) {
            if (it == "com.x.Bar") NodeId("cls:com.x.Bar") else null
        }
        val bar = token(tokens, "Bar")
        assertEquals(TokenKind.TYPE, bar.kind)
        assertEquals(NodeId("cls:com.x.Bar"), bar.definition)
    }

    @Test
    fun methodAndFieldReferencesGetTheirKindAndOwnerTarget() {
        val ownerNode = NodeId("cls:com.x.Owner")
        val resolve: (String) -> NodeId? = { if (it == "com.x.Owner") ownerNode else null }

        val mSrc = "x.foo();"
        val mTokens = colorize(
            mSrc,
            metadata(mSrc.indexOf("foo") to ReferenceAnnotation(MethodNodeRef("com.x.Owner", "foo", emptyList()))),
            resolve,
        )
        val foo = token(mTokens, "foo")
        assertEquals(TokenKind.METHOD, foo.kind)
        assertEquals(ownerNode, foo.definition)

        val fSrc = "y.count"
        val fTokens = colorize(
            fSrc,
            metadata(fSrc.indexOf("count") to ReferenceAnnotation(FieldNodeRef("com.x.Owner", "count"))),
            resolve,
        )
        val count = token(fTokens, "count")
        assertEquals(TokenKind.FIELD, count.kind)
        assertEquals(ownerNode, count.definition)
    }

    @Test
    fun nestedClassReferenceResolvesRegardlessOfSeparator() {
        // The client canonicalizes ($ -> .) both the loaded names and the ref; simulate that resolver.
        val index = listOf("Outer\$Inner").associateBy { it.replace('$', '.') }
        val resolve: (String) -> NodeId? = { name -> index[name.replace('$', '.')]?.let { NodeId("cls:$it") } }
        val expected = NodeId("cls:Outer\$Inner")

        // A reference may arrive dotted…
        val dotted = colorize("Inner x;", metadata(0 to ReferenceAnnotation(ClassNodeRef("Outer.Inner"))), resolve)
        assertEquals(expected, token(dotted, "Inner").definition, "dotted nested ref should resolve")

        // …or with the $ separator — both reach the same loaded class node.
        val dollar = colorize("Inner x;", metadata(0 to ReferenceAnnotation(ClassNodeRef("Outer\$Inner"))), resolve)
        assertEquals(expected, token(dollar, "Inner").definition, "\$-qualified nested ref should resolve")
    }

    @Test
    fun unloadedReferenceColorsButDoesNotNavigate() {
        val tokens = colorize("String s;", metadata(0 to ReferenceAnnotation(ClassNodeRef("java.lang.String")))) { null }
        val s = token(tokens, "String")
        assertEquals(TokenKind.TYPE, s.kind)
        assertNull(s.definition, "external/unloaded class refs color but do not jump")
    }

    @Test
    fun variableOccurrenceIsNotNavigable() {
        val source = "int count = 0;"
        val offset = source.indexOf("count")
        val tokens = colorize(source, metadata(offset to VariableAnnotation(VarRef(1, "count"), declaration = true)))
        val count = token(tokens, "count")
        assertEquals(TokenKind.PLAIN, count.kind)
        assertNull(count.definition)
    }

    @Test
    fun heuristicAppliesWhenMetadataAbsent() {
        val tokens = colorize("Foo bar() baz", metadata = null)
        assertEquals(TokenKind.TYPE, token(tokens, "Foo").kind, "Capitalized identifier → TYPE")
        assertEquals(TokenKind.METHOD, token(tokens, "bar").kind, "name before '(' → METHOD")
        assertEquals(TokenKind.PLAIN, token(tokens, "baz").kind, "bare lowercase → PLAIN")
        assertNull(token(tokens, "Foo").definition, "heuristic never fabricates a nav target")
    }

    @Test
    fun groupIntoLinesNumbersLinesAndSplitsAcrossNewlines() {
        val lines = JavaColorizer.groupIntoLines(
            listOf(
                CodeToken("a", TokenKind.PLAIN),
                CodeToken("\n\n", TokenKind.PLAIN), // blank line in between
                CodeToken("b", TokenKind.PLAIN),
            ),
        )
        assertEquals(listOf(1, 2, 3), lines.map { it.number })
        assertEquals(listOf("a"), lines[0].tokens.map { it.text })
        assertTrue(lines[1].tokens.isEmpty(), "the blank middle line has no tokens")
        assertEquals(listOf("b"), lines[2].tokens.map { it.text })
    }

    @Test
    fun groupIntoLinesSplitsMultiLineTokenPreservingKind() {
        val lines = JavaColorizer.groupIntoLines(listOf(CodeToken("/*x\ny*/", TokenKind.COMMENT)))
        assertEquals(2, lines.size)
        assertEquals("/*x", lines[0].tokens.single().text)
        assertEquals("y*/", lines[1].tokens.single().text)
        assertTrue(lines.all { it.tokens.all { t -> t.kind == TokenKind.COMMENT } }, "kind survives the split")
    }
}
