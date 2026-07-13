package com.jadxmp.ui.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure tests for [ResourceSurface] — the Compose-free resource-tree + document logic. A hand-built
 * [FakeResourceProvider] stands in for the engine's `ApkResources`, so every branch (tree shape,
 * folder grouping, table listing, placeholders, the partial-decode footer, fault isolation) is
 * exercised without a real APK or the engine.
 */
class ResourceSurfaceTest {

    private class FakeResourceProvider(
        override val hasManifestEntry: Boolean = false,
        private val manifest: String? = null,
        override val xmlResourcePaths: List<String> = emptyList(),
        private val xml: Map<String, String?> = emptyMap(),
        override val tableTypes: List<ResTableType> = emptyList(),
        private val entries: Map<String, List<ResTableEntry>> = emptyMap(),
        override val diagnostics: List<String> = emptyList(),
        // When true, any decodeXml call fails the test — proves buildTree does NOT eagerly decode res leaves.
        private val failOnDecodeXml: Boolean = false,
    ) : ResourceProvider {
        /** How many times [decodeXml] was invoked — asserts marking is lazy (only on folder expand). */
        var decodeXmlCalls: Int = 0
            private set

        override fun decodeManifest(): String? = manifest
        override fun decodeXml(path: String): String? {
            decodeXmlCalls++
            if (failOnDecodeXml) throw AssertionError("decodeXml('$path') must not run at buildTree — marking is lazy")
            return xml[path]
        }
        override fun tableEntries(type: String): List<ResTableEntry> = entries[type].orEmpty()
    }

    private fun labels(nodes: List<TreeNode>): List<String> = nodes.map { it.label }

    // ── Tree structure ───────────────────────────────────────────────────────────

    @Test
    fun buildTree_hasManifestResFoldersAndTable() {
        val provider = FakeResourceProvider(
            hasManifestEntry = true,
            manifest = "<manifest/>",
            xmlResourcePaths = listOf(
                "res/layout/activity_main.xml",
                "res/values/strings.xml",
                "res/values/colors.xml",
            ),
            xml = mapOf(
                "res/layout/activity_main.xml" to "<LinearLayout/>",
                "res/values/strings.xml" to "<resources/>",
                "res/values/colors.xml" to "<resources/>",
            ),
            tableTypes = listOf(ResTableType("color", 3), ResTableType("string", 2)),
        )

        val tree = ResourceSurface.buildTree(provider)

        // Roots, in order: manifest file, res/ folder, resource table.
        assertEquals(listOf("AndroidManifest.xml", "res", "Resource Table"), labels(tree.roots))
        assertEquals(NodeKind.FILE, tree.roots[0].kind)
        assertEquals(NodeKind.DIRECTORY, tree.roots[1].kind)
        assertEquals(NodeKind.DIRECTORY, tree.roots[2].kind)
        // A manifest that decodes carries NO "(could not decode)" marker (honesty is non-noisy).
        assertNull(tree.roots[0].secondary)

        // res/ groups its files by folder (folders before files, alphabetical).
        val resKids = tree.childrenOf(NodeId("resdir:res"))
        assertEquals(listOf("layout", "values"), labels(resKids))
        assertTrue(resKids.all { it.kind == NodeKind.DIRECTORY && it.hasChildren })

        val valuesKids = tree.childrenOf(NodeId("resdir:res/values"))
        assertEquals(listOf("colors.xml", "strings.xml"), labels(valuesKids))
        assertTrue(valuesKids.all { it.kind == NodeKind.RESOURCE && !it.hasChildren })
        // Leaves are built without a marker (marking is lazy, applied on folder expand).
        assertTrue(valuesKids.all { it.secondary == null })
        assertEquals(0, provider.decodeXmlCalls, "buildTree must not decode res leaves")
        // Leaf ids carry the full res/ path so code() can decode them.
        assertEquals("res:res/values/colors.xml", valuesKids[0].id.value)

        val layoutKids = tree.childrenOf(NodeId("resdir:res/layout"))
        assertEquals(listOf("activity_main.xml"), labels(layoutKids))

        // Resource-table types, sorted, with entry counts as secondary text.
        val typeKids = tree.childrenOf(NodeId("restable:"))
        assertEquals(listOf("color", "string"), labels(typeKids))
        assertEquals("restype:color", typeKids[0].id.value)
        assertEquals("3 entries", typeKids[0].secondary)
        assertEquals("2 entries", typeKids[1].secondary)
    }

    @Test
    fun buildTree_omitsManifestNodeWhenNoEntry() {
        // No manifest ENTRY at all → no manifest node (nothing is present, so nothing is lost).
        val provider = FakeResourceProvider(
            hasManifestEntry = false,
            xmlResourcePaths = listOf("res/values/strings.xml"),
            xml = mapOf("res/values/strings.xml" to "<resources/>"),
        )
        val tree = ResourceSurface.buildTree(provider)
        assertEquals(listOf("res"), labels(tree.roots)) // no manifest, no table
    }

    @Test
    fun buildTree_showsManifestNodeMarkedWhenPresentButUndecodable() {
        // The honesty gap: the manifest ENTRY is present but does not decode. It must appear (rule 4),
        // marked "(could not decode)", not silently vanish.
        val provider = FakeResourceProvider(hasManifestEntry = true, manifest = null)
        val tree = ResourceSurface.buildTree(provider)

        assertEquals(listOf("AndroidManifest.xml"), labels(tree.roots))
        val manifest = tree.roots.single()
        assertEquals(NodeKind.FILE, manifest.kind)
        assertEquals("res:AndroidManifest.xml", manifest.id.value)
        assertEquals("(could not decode)", manifest.secondary)
        assertEquals(ResourceSurface.UNDECODABLE_MARKER, manifest.secondary)
    }

    @Test
    fun buildTree_isLazy_marksResXmlLeavesOnlyOnFolderExpand() {
        // Symmetric to the manifest, but LAZY: a listed res/ xml that decodes stays clean and one that
        // returns null is marked — computed when the folder is EXPANDED (childNodes → markResourceChildren),
        // never eagerly at buildTree (which on web would decode the whole res/ subtree on the UI thread).
        val provider = FakeResourceProvider(
            xmlResourcePaths = listOf("res/xml/broken.xml", "res/xml/ok.xml"),
            xml = mapOf(
                "res/xml/broken.xml" to null,
                "res/xml/ok.xml" to "<paths/>",
            ),
        )
        val tree = ResourceSurface.buildTree(provider)

        // buildTree touched no file bytes: the leaves exist, unmarked, with zero decode calls.
        assertEquals(0, provider.decodeXmlCalls)
        val built = tree.childrenOf(NodeId("resdir:res/xml"))
        assertEquals(listOf("broken.xml", "ok.xml"), labels(built))
        assertTrue(built.all { it.secondary == null })

        // Expanding the folder is what decodes its leaves and applies the honest marker.
        val expanded = ResourceSurface.markResourceChildren(provider, built)
        assertEquals(2, provider.decodeXmlCalls)
        assertEquals(ResourceSurface.UNDECODABLE_MARKER, expanded[0].secondary)
        assertNull(expanded[1].secondary)
        // Ids/labels/kinds are untouched by marking (only secondary changes).
        assertEquals(built.map { it.id }, expanded.map { it.id })
        assertEquals(built.map { it.kind }, expanded.map { it.kind })
    }

    @Test
    fun buildTree_neverDecodesResLeaves_evenWhenDecodeWouldThrow() {
        // Hard proof the marker is not eager: a provider whose decodeXml throws must still build the tree
        // without error (buildTree must not call it). Expanding a folder is where a decode would occur.
        val provider = FakeResourceProvider(
            xmlResourcePaths = listOf("res/layout/a.xml", "res/layout/b.xml", "res/values/c.xml"),
            failOnDecodeXml = true,
        )
        val tree = ResourceSurface.buildTree(provider) // would throw if buildTree decoded a leaf
        assertEquals(0, provider.decodeXmlCalls)
        assertEquals(listOf("res"), labels(tree.roots))
        // The structure is present; only the (lazy) marker is deferred.
        assertEquals(listOf("layout", "values"), labels(tree.childrenOf(NodeId("resdir:res"))))
    }

    @Test
    fun markResourceChildren_leavesFoldersAndTableRowsUntouched() {
        // Sub-folders (resdir:) and resource-table type rows (restype:, with an entry-count secondary) are
        // not res/ leaves — marking must pass them through without decoding or clobbering their secondary.
        val provider = FakeResourceProvider(failOnDecodeXml = true) // fails if a non-leaf is decoded
        val folder = TreeNode(NodeId("resdir:res/layout"), "layout", NodeKind.DIRECTORY, hasChildren = true)
        val typeRow = TreeNode(NodeId("restype:string"), "string", NodeKind.RESOURCE, hasChildren = false, secondary = "2 entries")
        val out = ResourceSurface.markResourceChildren(provider, listOf(folder, typeRow))
        assertEquals(0, provider.decodeXmlCalls)
        assertEquals(listOf(folder, typeRow), out)
        assertEquals("2 entries", out[1].secondary)
    }

    @Test
    fun buildTree_emptyProviderYieldsEmptyRoots() {
        // Fault isolation: an APK with nothing decodable (or a non-APK, which never builds a provider)
        // shows an empty Resources tree — not an error.
        val tree = ResourceSurface.buildTree(FakeResourceProvider())
        assertTrue(tree.roots.isEmpty())
        assertTrue(tree.children.isEmpty())
    }

    // ── Documents ────────────────────────────────────────────────────────────────

    @Test
    fun document_manifestReturnsDecodedText() {
        val provider = FakeResourceProvider(hasManifestEntry = true, manifest = "<manifest package=\"com.x\"/>")
        val doc = ResourceSurface.document(provider, NodeId("res:AndroidManifest.xml"), CodeView.JAVA)
        assertEquals("AndroidManifest.xml", doc.title)
        assertTrue(doc.plainText().contains("package=\"com.x\""))
    }

    @Test
    fun document_resXmlReturnsDecodedText() {
        val provider = FakeResourceProvider(
            xmlResourcePaths = listOf("res/layout/a.xml"),
            xml = mapOf("res/layout/a.xml" to "<LinearLayout/>"),
        )
        val doc = ResourceSurface.document(provider, NodeId("res:res/layout/a.xml"), CodeView.JAVA)
        assertEquals("a.xml", doc.title)
        assertEquals("<LinearLayout/>", doc.plainText())
    }

    @Test
    fun document_nullDecodeYieldsHonestPlaceholder() {
        val provider = FakeResourceProvider(
            xmlResourcePaths = listOf("res/xml/broken.xml"),
            xml = mapOf("res/xml/broken.xml" to null),
        )
        val doc = ResourceSurface.document(provider, NodeId("res:res/xml/broken.xml"), CodeView.JAVA)
        val text = doc.plainText()
        assertTrue(text.contains("Could not decode"), "placeholder should be honest, was: $text")
        assertTrue(text.contains("res/xml/broken.xml"))
    }

    @Test
    fun document_partialDecodeSurfacesDiagnosticsFooter() {
        val path = "res/layout/partial.xml"
        val provider = FakeResourceProvider(
            xmlResourcePaths = listOf(path),
            xml = mapOf(path to "<View/>"),
            diagnostics = listOf("$path: skipped unknown chunk 0x0200", "other/file: unrelated"),
        )
        val doc = ResourceSurface.document(provider, NodeId("res:$path"), CodeView.JAVA)
        val text = doc.plainText()
        assertTrue(text.contains("<View/>"))
        assertTrue(text.contains("decode notes"), "partial decode must surface its note")
        assertTrue(text.contains("skipped unknown chunk 0x0200"))
        assertFalse(text.contains("unrelated"), "only this file's diagnostics belong in its footer")
    }

    @Test
    fun document_tableTypeListsEntriesWithReferenceAndHexId() {
        val provider = FakeResourceProvider(
            tableTypes = listOf(ResTableType("string", 2)),
            entries = mapOf(
                "string" to listOf(
                    ResTableEntry(reference = "@string/app_name", config = "", value = "Example", hexId = "0x7f0f0001"),
                    ResTableEntry(reference = "@string/hello", config = "en-rUS", value = "Hello", hexId = "0x7f0f0002"),
                ),
            ),
        )
        val doc = ResourceSurface.document(provider, NodeId("restype:string"), CodeView.JAVA)
        val text = doc.plainText()
        assertEquals("string", doc.title)
        assertTrue(text.contains("@string/app_name = Example"))
        assertTrue(text.contains("0x7f0f0001"))
        // Non-default config qualifiers are shown.
        assertTrue(text.contains("@string/hello [en-rUS] = Hello"))
    }

    @Test
    fun unavailable_isHonestPlaceholder() {
        val doc = ResourceSurface.unavailable(NodeId("res:AndroidManifest.xml"), CodeView.JAVA)
        assertTrue(doc.plainText().contains("resources are not available"))
    }

    @Test
    fun isResourceNode_recognizesResourcePrefixesOnly() {
        assertTrue(ResourceSurface.isResourceNode(NodeId("res:AndroidManifest.xml")))
        assertTrue(ResourceSurface.isResourceNode(NodeId("resdir:res/layout")))
        assertTrue(ResourceSurface.isResourceNode(NodeId("restable:")))
        assertTrue(ResourceSurface.isResourceNode(NodeId("restype:color")))
        assertFalse(ResourceSurface.isResourceNode(NodeId("cls:com.example.Foo")))
        assertFalse(ResourceSurface.isResourceNode(NodeId("mbr:com.example.Foo#bar")))
        assertFalse(ResourceSurface.isResourceNode(NodeId("pkg:com.example")))
    }
}
