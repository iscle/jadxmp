package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.NodeKind
import com.jadxmp.ui.client.TreeNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-logic tests for the tree-reveal + context-menu helpers: the ancestor chain a target must expand
 * through, the qualified name to copy, and the loaded-descendant collector behind "collapse subtree".
 * No Compose — these are the unit-testable heart the reveal/menu wiring composes over.
 */
class TreeRevealTest {

    // ── ancestorContainerIds ─────────────────────────────────────────────────────

    @Test
    fun classAncestorsAreThePackageChainRootMostFirst() {
        assertEquals(
            listOf(NodeId("pkg:com"), NodeId("pkg:com.example"), NodeId("pkg:com.example.app")),
            ancestorContainerIds(NodeId("cls:com.example.app.MainActivity")),
        )
    }

    @Test
    fun defaultPackageClassHasNoAncestors() {
        assertTrue(ancestorContainerIds(NodeId("cls:Foo")).isEmpty())
    }

    @Test
    fun resourceFileAncestorsCoverBothFolderSchemes() {
        // Production dirs use `resdir:`, the stub uses `res:` — one list expands the target under either.
        val ids = ancestorContainerIds(NodeId("res:res/layout/activity_main.xml"))
        assertTrue(NodeId("resdir:res") in ids)
        assertTrue(NodeId("resdir:res/layout") in ids)
        assertTrue(NodeId("res:res") in ids)
        assertTrue(NodeId("res:res/layout") in ids)
        // Root-most first: the shallower folder precedes the deeper one.
        assertTrue(ids.indexOf(NodeId("resdir:res")) < ids.indexOf(NodeId("resdir:res/layout")))
    }

    @Test
    fun manifestRootHasNoAncestors() {
        assertTrue(ancestorContainerIds(NodeId("res:AndroidManifest.xml")).isEmpty())
    }

    @Test
    fun tableTypeAncestorIsTheTableRoot() {
        assertEquals(listOf(NodeId("restable:")), ancestorContainerIds(NodeId("restype:string")))
    }

    @Test
    fun memberAncestorsIncludeOwnerClassAndItsPackages() {
        val id = NodeId("mbr:com.example.Foo#com.example.Foo#M:bar()")
        val ids = ancestorContainerIds(id)
        assertTrue(NodeId("pkg:com") in ids)
        assertTrue(NodeId("pkg:com.example") in ids)
        assertTrue(NodeId("cls:com.example.Foo") in ids, "the class node must expand so its member shows")
    }

    // ── qualifiedNodeName ────────────────────────────────────────────────────────

    @Test
    fun qualifiedNameOfClassIsItsFqn() {
        val node = TreeNode(NodeId("cls:com.example.Foo"), "Foo", NodeKind.CLASS, hasChildren = true)
        assertEquals("com.example.Foo", qualifiedNodeName(node))
    }

    @Test
    fun qualifiedNameOfMemberIsOwnerDotLabel() {
        val node = TreeNode(NodeId("mbr:com.example.Foo#com.example.Foo#M:bar()"), "bar", NodeKind.METHOD, hasChildren = false)
        assertEquals("com.example.Foo.bar", qualifiedNodeName(node))
    }

    @Test
    fun qualifiedNameOfPackageIsDottedName() {
        val node = TreeNode(NodeId("pkg:com.example.app"), "app", NodeKind.PACKAGE, hasChildren = true)
        assertEquals("com.example.app", qualifiedNodeName(node))
    }

    @Test
    fun qualifiedNameOfResourceFileIsItsPath() {
        val node = TreeNode(NodeId("res:res/layout/activity_main.xml"), "activity_main.xml", NodeKind.RESOURCE, hasChildren = false)
        assertEquals("res/layout/activity_main.xml", qualifiedNodeName(node))
    }

    // ── collectLoadedDescendantIds ───────────────────────────────────────────────

    @Test
    fun collectLoadedDescendantIdsWalksTheCacheAndExcludesRoot() {
        val cache = mapOf(
            NodeId("pkg:a") to listOf(TreeNode(NodeId("cls:a.B"), "B", NodeKind.CLASS, hasChildren = true)),
            NodeId("cls:a.B") to listOf(TreeNode(NodeId("mbr:a.B#a.B#M:m()"), "m", NodeKind.METHOD, hasChildren = false)),
        )
        val ids = collectLoadedDescendantIds(NodeId("pkg:a"), cache)
        assertEquals(setOf(NodeId("cls:a.B"), NodeId("mbr:a.B#a.B#M:m()")), ids)
        assertTrue(NodeId("pkg:a") !in ids, "the root itself is not a descendant")
    }
}
