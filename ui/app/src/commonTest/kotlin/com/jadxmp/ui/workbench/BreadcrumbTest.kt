package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.NodeKind
import kotlin.test.Test
import kotlin.test.assertEquals

class BreadcrumbTest {

    @Test
    fun classIdSplitsIntoPackageAndType() {
        val crumbs = breadcrumbSegments(NodeId("cls:com.example.app.MainActivity"))
        assertEquals(
            listOf(
                BreadcrumbSegment("com.example.app", CrumbEmphasis.PLAIN),
                BreadcrumbSegment("MainActivity", CrumbEmphasis.TYPE),
            ),
            crumbs,
        )
    }

    @Test
    fun memberIdAddsAMemberCrumbWithParens() {
        val crumbs = breadcrumbSegments(NodeId("mbr:com.example.app.MainActivity#onCreate"))
        assertEquals(3, crumbs.size)
        assertEquals(BreadcrumbSegment("MainActivity", CrumbEmphasis.TYPE), crumbs[1])
        assertEquals(BreadcrumbSegment("onCreate()", CrumbEmphasis.MEMBER), crumbs[2])
    }

    @Test
    fun methodMemberWithKindGetsParens() {
        val crumbs = breadcrumbSegments(NodeId("mbr:com.example.app.MainActivity#onCreate"), NodeKind.METHOD)
        assertEquals(BreadcrumbSegment("onCreate()", CrumbEmphasis.MEMBER), crumbs.last())
    }

    @Test
    fun fieldMemberIsNotRenderedAsAMethod() {
        // A FIELD id is shape-identical to a method id; the kind is what stops it reading as `count()`.
        val crumbs = breadcrumbSegments(NodeId("mbr:com.example.app.User#count"), NodeKind.FIELD)
        assertEquals(BreadcrumbSegment("count", CrumbEmphasis.MEMBER), crumbs.last())
    }

    @Test
    fun constructorMemberIsNotDoubleParenthesised() {
        val crumbs = breadcrumbSegments(NodeId("mbr:com.example.app.StringUtils#<init>"))
        assertEquals(BreadcrumbSegment("<init>", CrumbEmphasis.MEMBER), crumbs.last())
    }

    @Test
    fun resourcePathSplitsOnSlashes() {
        val crumbs = breadcrumbSegments(NodeId("res:res/layout/activity_main.xml"))
        assertEquals(listOf("res", "layout", "activity_main.xml"), crumbs.map { it.text })
        assertEquals(CrumbEmphasis.PLAIN, crumbs.last().emphasis)
    }

    @Test
    fun defaultPackageClassHasNoPackageCrumb() {
        val crumbs = breadcrumbSegments(NodeId("cls:TopLevel"))
        assertEquals(listOf(BreadcrumbSegment("TopLevel", CrumbEmphasis.TYPE)), crumbs)
    }

    @Test
    fun unknownPrefixFallsBackToWholeLabel() {
        val crumbs = breadcrumbSegments(NodeId("pkg:com.example.app"))
        assertEquals(listOf(BreadcrumbSegment("com.example.app", CrumbEmphasis.PLAIN)), crumbs)
    }
}
