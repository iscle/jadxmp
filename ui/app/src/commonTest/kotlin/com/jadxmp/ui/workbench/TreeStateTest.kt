package com.jadxmp.ui.workbench

import com.jadxmp.ui.client.NodeId
import com.jadxmp.ui.client.NodeKind
import com.jadxmp.ui.client.TreeNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TreeStateTest {
    // pkg → [ClassA → [m1], ClassB]
    private val pkg = TreeNode(NodeId("pkg"), "com.example", NodeKind.PACKAGE, hasChildren = true)
    private val clsA = TreeNode(NodeId("A"), "Alpha", NodeKind.CLASS, hasChildren = true)
    private val clsB = TreeNode(NodeId("B"), "Beta", NodeKind.CLASS, hasChildren = false)
    private val m1 = TreeNode(NodeId("A#m1"), "compute", NodeKind.METHOD, hasChildren = false)

    private val children = mapOf(
        NodeId("pkg") to listOf(clsA, clsB),
        NodeId("A") to listOf(m1),
    )
    private val childrenOf: (NodeId) -> List<TreeNode> = { children[it].orEmpty() }
    private val roots = listOf(pkg)

    @Test
    fun collapsedShowsOnlyRoots() {
        val rows = buildVisibleRows(roots, childrenOf, expanded = emptySet())
        assertEquals(listOf(NodeId("pkg")), rows.map { it.node.id })
        assertFalse(rows.single().expanded)
    }

    @Test
    fun expandingRevealsChildrenAtNextDepth() {
        val rows = buildVisibleRows(roots, childrenOf, expanded = setOf(NodeId("pkg")))
        assertEquals(listOf(NodeId("pkg"), NodeId("A"), NodeId("B")), rows.map { it.node.id })
        assertEquals(listOf(0, 1, 1), rows.map { it.depth })
        assertTrue(rows.first().expanded)
    }

    @Test
    fun nestedExpansionDescends() {
        val rows = buildVisibleRows(roots, childrenOf, expanded = setOf(NodeId("pkg"), NodeId("A")))
        assertEquals(listOf(NodeId("pkg"), NodeId("A"), NodeId("A#m1"), NodeId("B")), rows.map { it.node.id })
        assertEquals(2, rows.first { it.node.id == NodeId("A#m1") }.depth)
    }

    @Test
    fun filterAutoRevealsMatchesAndKeepsAncestors() {
        // "compute" only exists under pkg → A; filtering must surface it with its ancestors.
        val rows = buildVisibleRows(roots, childrenOf, expanded = emptySet(), filter = "compute")
        val ids = rows.map { it.node.id }
        assertTrue(NodeId("A#m1") in ids)
        assertTrue(NodeId("pkg") in ids)
        assertTrue(NodeId("A") in ids)
        assertFalse(NodeId("B") in ids) // non-matching sibling pruned
    }

    @Test
    fun filterIsCaseInsensitive() {
        val rows = buildVisibleRows(roots, childrenOf, expanded = emptySet(), filter = "ALPHA")
        assertTrue(rows.any { it.node.id == NodeId("A") })
    }

    @Test
    fun deepMatchIsFoundWhenTheWholeSubtreeIsCached() {
        // M6: a match inside a not-expanded package surfaces as long as its children are in the cache
        // (WorkbenchState eagerly loads the subtree while a filter is active).
        val rows = buildVisibleRows(roots, childrenOf, expanded = emptySet(), filter = "compute")
        assertEquals(listOf(NodeId("pkg"), NodeId("A"), NodeId("A#m1")), rows.map { it.node.id })
    }

    // ── flatten = single-child package-chain collapse (jadx behaviour), NOT auto-expand ──────────
    // com › example › app › Widget  — each package has exactly one (package) child until `app`.
    private val com = TreeNode(NodeId("com"), "com", NodeKind.PACKAGE, hasChildren = true)
    private val example = TreeNode(NodeId("com.example"), "example", NodeKind.PACKAGE, hasChildren = true)
    private val app = TreeNode(NodeId("com.example.app"), "app", NodeKind.PACKAGE, hasChildren = true)
    private val widget = TreeNode(NodeId("com.example.app.Widget"), "Widget", NodeKind.CLASS, hasChildren = false)
    private val chainChildren = mapOf(
        NodeId("com") to listOf(example),
        NodeId("com.example") to listOf(app),
        NodeId("com.example.app") to listOf(widget),
    )
    private val chainOf: (NodeId) -> List<TreeNode> = { chainChildren[it].orEmpty() }

    @Test
    fun flattenCollapsesSingleChildPackageChainIntoDottedRow() {
        val rows = buildVisibleRows(listOf(com), chainOf, expanded = emptySet(), flattenPackages = true)
        // One collapsed row; keeps the deepest package's id so expansion still targets `app`.
        assertEquals(1, rows.size)
        assertEquals("com.example.app", rows.single().node.label)
        assertEquals(NodeId("com.example.app"), rows.single().node.id)
        assertFalse(rows.single().expanded) // flatten does NOT auto-expand
    }

    @Test
    fun expandingACollapsedChainRevealsTheLeafPackagesClasses() {
        val rows = buildVisibleRows(
            listOf(com),
            chainOf,
            expanded = setOf(NodeId("com.example.app")),
            flattenPackages = true,
        )
        assertEquals(listOf(NodeId("com.example.app"), NodeId("com.example.app.Widget")), rows.map { it.node.id })
        assertEquals(listOf(0, 1), rows.map { it.depth })
    }

    @Test
    fun flattenStopsCollapsingAtAPackageWithMultipleChildren() {
        // `pkg` has two children (Alpha, Beta) → it is a chain end, so it is not merged and not expanded.
        val rows = buildVisibleRows(roots, childrenOf, expanded = emptySet(), flattenPackages = true)
        assertEquals(listOf(NodeId("pkg")), rows.map { it.node.id })
        assertEquals("com.example", rows.single().node.label) // unchanged label, no merge
        assertFalse(rows.single().expanded)
    }

    @Test
    fun withoutFlattenNoChainCollapseHappens() {
        val rows = buildVisibleRows(listOf(com), chainOf, expanded = emptySet(), flattenPackages = false)
        assertEquals(listOf(NodeId("com")), rows.map { it.node.id })
        assertEquals("com", rows.single().node.label)
    }
}
