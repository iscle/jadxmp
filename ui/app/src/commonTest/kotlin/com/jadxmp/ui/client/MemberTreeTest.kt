package com.jadxmp.ui.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the pure member-tree seam ([MemberTree]) — the NodeId scheme and the row builder, kept
 * free of Compose and the engine so the encoding round-trip, ordering, kinds and nested recursion are
 * asserted directly.
 */
class MemberTreeTest {

    private val field = MemberDescriptor(MemberSort.FIELD, "count", "count: int")
    private val ctor = MemberDescriptor(MemberSort.CONSTRUCTOR, "Foo", "Foo(int)")
    private val method = MemberDescriptor(MemberSort.METHOD, "run", "run(): void")
    private val nested = MemberDescriptor(MemberSort.NESTED_CLASS, "Inner", "Inner", nestedFqn = "p.Foo\$Inner")

    @Test
    fun memberNodesPreserveOrderAndCarryKindsIdsSignatures() {
        val nodes = MemberTree.memberNodes("p.Foo", "p.Foo", listOf(field, ctor, method, nested))

        assertEquals(listOf("count", "Foo", "run", "Inner"), nodes.map { it.label }, "declaration order preserved")
        assertEquals(
            listOf(NodeKind.FIELD, NodeKind.METHOD, NodeKind.METHOD, NodeKind.CLASS),
            nodes.map { it.kind },
            "field→FIELD, method/constructor→METHOD, nested→CLASS",
        )
        assertEquals(listOf("count: int", "Foo(int)", "run(): void", "Inner"), nodes.map { it.secondary })
        // Only the nested class recurses; the rest are leaves.
        assertEquals(listOf(false, false, false, true), nodes.map { it.hasChildren })
        // Ids are unique and carry the member prefix (distinct from cls:/pkg:/res:).
        assertTrue(nodes.all { it.id.value.startsWith("mbr:") })
        assertEquals(nodes.map { it.id.value }.toSet().size, nodes.size, "ids are unique within a class")
    }

    @Test
    fun idRoundTripsThroughParse() {
        val node = MemberTree.memberNodes("p.Foo", "p.Foo", listOf(method)).single()
        val parsed = MemberTree.parse(node.id)!!
        assertEquals("p.Foo", parsed.first, "topLevel")
        assertEquals("p.Foo", parsed.second, "owner")
        assertEquals(MemberTree.slug(MemberSort.METHOD, "run(): void"), parsed.third, "slug")
    }

    @Test
    fun overloadsGetDistinctIdsViaSignature() {
        val a = MemberDescriptor(MemberSort.METHOD, "f", "f(int): void")
        val b = MemberDescriptor(MemberSort.METHOD, "f", "f(String): void")
        val nodes = MemberTree.memberNodes("p.C", "p.C", listOf(a, b))
        assertTrue(nodes[0].id != nodes[1].id, "overloads must not collide")
    }

    @Test
    fun nestedRecursionPreservesTopLevelAndSwitchesOwner() {
        // Expanding a nested-class row re-enumerates the nested class as the new owner, keeping topLevel.
        val nestedNode = MemberTree.memberNodes("p.Foo", "p.Foo", listOf(nested)).single()
        val (top, owner, _) = MemberTree.parse(nestedNode.id)!!
        assertEquals("p.Foo", top)
        assertEquals("p.Foo", owner, "the nested row itself is owned by its outer class")

        // The client, having resolved nested.nestedFqn, builds the grandchildren under the nested owner.
        val grandMethod = MemberDescriptor(MemberSort.METHOD, "g", "g(): void")
        val grand = MemberTree.memberNodes("p.Foo", nested.nestedFqn!!, listOf(grandMethod)).single()
        val parsedGrand = MemberTree.parse(grand.id)!!
        assertEquals("p.Foo", parsedGrand.first, "top-level class is carried through nesting")
        assertEquals("p.Foo\$Inner", parsedGrand.second, "owner switches to the nested class")
    }

    @Test
    fun parseRejectsNonMemberIds() {
        assertNull(MemberTree.parse(NodeId("cls:p.Foo")))
        assertNull(MemberTree.parse(NodeId("pkg:p")))
        assertNull(MemberTree.parse(NodeId("mbr:only#twoparts")), "needs both separators")
    }
}
