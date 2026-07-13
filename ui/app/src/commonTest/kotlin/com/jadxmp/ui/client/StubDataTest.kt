package com.jadxmp.ui.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StubDataTest {

    @Test
    fun classTreeHasRootsWithChildren() {
        val roots = StubData.roots(TreeKind.CLASSES)
        assertTrue(roots.isNotEmpty())
        val firstPkg = roots.first()
        assertEquals(NodeKind.PACKAGE, firstPkg.kind)
        assertTrue(StubData.childrenOf(firstPkg.id).isNotEmpty())
    }

    @Test
    fun classesOfferJavaKotlinAndSmaliViews() {
        val cls = StubData.childrenOf(StubData.roots(TreeKind.CLASSES).first().id).first()
        val views = StubData.availableViews(cls.id)
        assertEquals(listOf(CodeView.JAVA, CodeView.KOTLIN, CodeView.SMALI), views)
    }

    @Test
    fun documentIsHighlightedNotOnePlainBlob() {
        val cls = StubData.childrenOf(StubData.roots(TreeKind.CLASSES).first().id).first()
        val doc = StubData.document(cls.id, CodeView.JAVA)
        assertTrue(doc.lines.isNotEmpty())
        val kinds = doc.lines.flatMap { it.tokens }.map { it.kind }.toSet()
        assertTrue(TokenKind.KEYWORD in kinds, "expected some keywords, got $kinds")
    }

    @Test
    fun memberNodeResolvesToOwningClassDocument() {
        val cls = StubData.childrenOf(StubData.roots(TreeKind.CLASSES).first().id).first()
        val member = StubData.childrenOf(cls.id).first()
        val doc = StubData.document(member.id, CodeView.JAVA)
        assertTrue(doc.lines.isNotEmpty())
    }

    @Test
    fun searchFindsClassByName() {
        val query = SearchQuery("Main", scopes = setOf(SearchScope.CLASS))
        val results = StubData.search(query)
        assertTrue(results.matches.any { it.title.contains("Main") })
    }

    @Test
    fun emptySearchReturnsNothing() {
        assertTrue(StubData.search(SearchQuery("   ")).matches.isEmpty())
    }
}
