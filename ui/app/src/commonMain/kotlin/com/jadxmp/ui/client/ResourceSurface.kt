package com.jadxmp.ui.client

/**
 * Pure, Compose-free resource-tree + resource-document logic for the Resources view.
 *
 * The engine's resource facade (`core:api`'s `ApkResources`) is a concrete, hard-to-fabricate type, so
 * this surface consumes a minimal [ResourceProvider] projection of it instead. That keeps every branch
 * here — the tree shape, the folder grouping, the placeholder/fault-isolation paths, the table
 * rendering — unit-testable with a hand-built fake, and keeps `CoreApiDecompilerClient` a thin adapter
 * ([ApkResourcesProvider]) that forwards to the engine.
 *
 * NodeId scheme (all resource ids carry a resource-specific prefix so [DecompilerClient.code] and the
 * tree routing can tell them apart from `cls:`/`mbr:` class ids):
 * - `res:AndroidManifest.xml`      — the decoded manifest (opens [ResourceProvider.decodeManifest]).
 * - `res:res/layout/activity_main.xml` — a binary/text XML under `res/` (opens [ResourceProvider.decodeXml]).
 * - `resdir:res`, `resdir:res/layout` — a folder in the `res/` subtree (expandable, never opened).
 * - `restable:`                    — the `resources.arsc` root (expandable, never opened).
 * - `restype:string`               — one resource-table type; opens a readable listing of its entries.
 */
public object ResourceSurface {

    private const val RES_PREFIX = "res:"
    private const val DIR_PREFIX = "resdir:"
    private const val TYPE_PREFIX = "restype:"
    private const val TABLE_ID = "restable:"
    private const val MANIFEST_PATH = "AndroidManifest.xml"
    private const val MANIFEST_ID = RES_PREFIX + MANIFEST_PATH

    /**
     * Dimmed secondary label on a resource node that is *present in the container but does not decode*
     * (rule 4: a present-but-undecodable entry must stay visible and honestly marked, never vanish).
     * A normally-decoding manifest/xml carries no secondary, so this is the only signal that separates
     * "here but unreadable" from "here and fine". Selecting a marked node opens the honest
     * "// Could not decode …" placeholder document.
     */
    public const val UNDECODABLE_MARKER: String = "(could not decode)"

    /** True if [node] belongs to the Resources tree (so [DecompilerClient.code] routes it here). */
    public fun isResourceNode(node: NodeId): Boolean {
        val v = node.value
        return v == TABLE_ID ||
            v.startsWith(RES_PREFIX) ||
            v.startsWith(DIR_PREFIX) ||
            v.startsWith(TYPE_PREFIX)
    }

    // ── Tree ────────────────────────────────────────────────────────────────────

    /**
     * Build the whole Resources tree from a [provider]. Roots are (in order): `AndroidManifest.xml`
     * (whenever the container carries a manifest **entry** — see [ResourceProvider.hasManifestEntry] —
     * even one that will not decode, so it never silently vanishes; rule 4), the `res/` folder subtree
     * grouped by directory, and the `Resource Table` grouped by type. A provider with nothing at all
     * yields empty roots — the honest "no resources" state, indistinguishable to the UI from a non-APK
     * input (rule 4).
     *
     * ## Marking is eager for the manifest, LAZY for `res/…xml` leaves
     * The manifest is a single, always-relevant root, so its `(could not decode)` marker is computed here
     * with one bounded [ResourceProvider.decodeManifest] call. The `res/…xml` **leaves are built without a
     * marker** and decoded lazily by [markResourceChildren] when their folder is expanded — decoding every
     * leaf here would run the full binary-XML decoder for *hundreds–thousands* of files up front, and on
     * web that whole loop runs on the single wasm event-loop thread with no yield, freezing the UI at
     * open (rule 1). A leaf's honest marker still appears the moment it becomes visible (folder expand).
     */
    public fun buildTree(provider: ResourceProvider): ResourceTreeModel {
        val roots = ArrayList<TreeNode>()
        val children = LinkedHashMap<NodeId, MutableList<TreeNode>>()

        // Show the manifest node for a present ENTRY, decoding or not; mark it only when it fails to decode.
        // One bounded decode — the manifest is a single root, not the unbounded res/ leaf set.
        if (provider.hasManifestEntry) {
            roots += TreeNode(
                id = NodeId(MANIFEST_ID),
                label = MANIFEST_PATH,
                kind = NodeKind.FILE,
                hasChildren = false,
                secondary = markerFor(provider.decodeManifest()),
            )
        }

        val paths = provider.xmlResourcePaths.filter { it.isNotBlank() }
        if (paths.isNotEmpty()) {
            roots += TreeNode(NodeId(dirId("res")), "res", NodeKind.DIRECTORY, hasChildren = true)
            buildResFolders(paths, children)
        }

        val types = provider.tableTypes
        if (types.isNotEmpty()) {
            val tableId = NodeId(TABLE_ID)
            roots += TreeNode(tableId, "Resource Table", NodeKind.DIRECTORY, hasChildren = true)
            children[tableId] = types.map { t ->
                TreeNode(
                    id = NodeId(TYPE_PREFIX + t.name),
                    label = t.name,
                    kind = NodeKind.RESOURCE,
                    hasChildren = false,
                    secondary = if (t.entryCount == 1) "1 entry" else "${t.entryCount} entries",
                )
            }.toMutableList()
        }

        val ordered = children.mapValues { (_, kids) -> kids.sortedWith(CHILD_ORDER) }
        return ResourceTreeModel(roots, ordered)
    }

    /**
     * Expand `res/…/file.xml` paths into a directory tree under `resdir:res`. Every intermediate folder
     * becomes an expandable [NodeKind.DIRECTORY] node and every leaf a [NodeKind.RESOURCE] file; each
     * child is registered under its parent once (paths that share a folder don't duplicate it).
     *
     * Leaves are built **without** a decode marker — no `decodeXml` runs here (that would decode the whole
     * `res/` subtree at build). Their `(could not decode)` marker is applied lazily by [markResourceChildren]
     * when a folder is expanded. This is pure path arithmetic; it never touches file bytes.
     */
    private fun buildResFolders(
        paths: List<String>,
        children: LinkedHashMap<NodeId, MutableList<TreeNode>>,
    ) {
        val added = HashMap<NodeId, MutableSet<String>>()
        for (path in paths) {
            val segs = path.split('/').filter { it.isNotEmpty() }
            // segs[0] ("res") is a root; walk each deeper segment, linking it to its parent folder.
            for (j in 1 until segs.size) {
                val parentPath = segs.subList(0, j).joinToString("/")
                val childPath = segs.subList(0, j + 1).joinToString("/")
                val isFile = j == segs.size - 1
                val parentId = NodeId(dirId(parentPath))
                val childId = if (isFile) NodeId(RES_PREFIX + childPath) else NodeId(dirId(childPath))
                if (!added.getOrPut(parentId) { HashSet() }.add(childId.value)) continue
                val node = if (isFile) {
                    // Leaf id carries the full `res/…xml` path (childPath == the whole path when isFile), so
                    // markResourceChildren/document can recover the decodeXml key from the id later.
                    TreeNode(childId, segs[j], NodeKind.RESOURCE, hasChildren = false)
                } else {
                    TreeNode(childId, segs[j], NodeKind.DIRECTORY, hasChildren = true)
                }
                children.getOrPut(parentId) { ArrayList() }.add(node)
            }
        }
    }

    /**
     * Apply each `res/…xml` leaf's honest [UNDECODABLE_MARKER] **lazily**, at folder-expand time. The
     * client calls this when a `resdir:` node's children are requested (`childNodes`) — so only the folders
     * a user actually opens are decoded, spreading the cost across interactions and bounding both the
     * up-front work and the memoized decode text on web (rule 1). A leaf whose [ResourceProvider.decodeXml]
     * returns `null` gains the marker; a decoding leaf stays clean; folders and resource-table type rows
     * (which are not `res:`-prefixed and carry their own entry-count secondary) pass through untouched.
     *
     * Idempotent and safe to call on any child list: it decodes only `res:`-prefixed leaf files.
     */
    public fun markResourceChildren(provider: ResourceProvider, children: List<TreeNode>): List<TreeNode> =
        children.map { child ->
            if (child.kind == NodeKind.RESOURCE && child.id.value.startsWith(RES_PREFIX)) {
                val path = child.id.value.removePrefix(RES_PREFIX)
                child.copy(secondary = markerFor(provider.decodeXml(path)))
            } else {
                child
            }
        }

    /** `null` (no marker) when [decoded] text is present; the honest [UNDECODABLE_MARKER] when it is `null`. */
    private fun markerFor(decoded: String?): String? = if (decoded != null) null else UNDECODABLE_MARKER

    private fun dirId(path: String): String = DIR_PREFIX + path

    /** Folders before files, each group alphabetical — the conventional file-tree ordering. */
    private val CHILD_ORDER: Comparator<TreeNode> =
        compareBy({ if (it.kind == NodeKind.DIRECTORY) 0 else 1 }, { it.label })

    // ── Documents ─────────────────────────────────────────────────────────────────

    /**
     * Render a resource node to a [CodeDocument] for the existing code/text viewer. XML files decode to
     * text (with a decode-notes footer when the engine reported a *partial* decode, so a salvaged file
     * is never silently presented as complete — rule 4); a table type renders as a readable entry
     * listing. A decode that returns `null` becomes an honest placeholder, never a blank page or crash.
     */
    public fun document(provider: ResourceProvider, node: NodeId, view: CodeView): CodeDocument {
        val v = node.value
        return when {
            v == MANIFEST_ID -> renderXml(node, view, MANIFEST_PATH, provider.decodeManifest(), provider.diagnostics)
            v.startsWith(RES_PREFIX) -> {
                val path = v.removePrefix(RES_PREFIX)
                renderXml(node, view, path, provider.decodeXml(path), provider.diagnostics)
            }
            v.startsWith(TYPE_PREFIX) -> {
                val type = v.removePrefix(TYPE_PREFIX)
                renderTable(node, view, type, provider.tableEntries(type))
            }
            // A folder / table-root node is never opened as a document, but degrade honestly if asked.
            else -> placeholder(node, view, labelOf(node), "// ${labelOf(node)} is a folder")
        }
    }

    /** Document shown when the input carries no resources at all (non-APK). */
    public fun unavailable(node: NodeId, view: CodeView): CodeDocument =
        placeholder(node, view, labelOf(node), "// resources are not available for this input")

    private fun renderXml(
        node: NodeId,
        view: CodeView,
        path: String,
        text: String?,
        diagnostics: List<String>,
    ): CodeDocument {
        val title = path.substringAfterLast('/')
        if (text == null) {
            return placeholder(node, view, title, "// Could not decode $path")
        }
        // Syntax-highlight the decoded XML (manifest and every res/…xml flow through here). The colorizer
        // returns 1-based lines; the decode-notes footer below continues from where it leaves off.
        val lines = ArrayList<CodeLine>(XmlColorizer.colorize(text))
        var n = lines.size + 1
        // A partial/best-effort decode is still returned, but its diagnostics (prefixed with the path by
        // the engine) must surface so the reader knows the file isn't wholly decoded.
        val notes = diagnostics.filter { it.startsWith("$path:") }
        if (notes.isNotEmpty()) {
            lines += CodeLine(n++, listOf(CodeToken("", TokenKind.PLAIN)))
            lines += CodeLine(n++, listOf(CodeToken("<!-- decode notes -->", TokenKind.COMMENT)))
            for (note in notes) {
                lines += CodeLine(n++, listOf(CodeToken("<!-- $note -->", TokenKind.COMMENT)))
            }
        }
        return CodeDocument(node, title, view, lines)
    }

    private fun renderTable(node: NodeId, view: CodeView, type: String, entries: List<ResTableEntry>): CodeDocument {
        if (entries.isEmpty()) {
            return placeholder(node, view, type, "// No entries for resource type '$type'")
        }
        val lines = ArrayList<CodeLine>()
        var n = 1
        val count = if (entries.size == 1) "1 entry" else "${entries.size} entries"
        lines += CodeLine(n++, listOf(CodeToken("// resources.arsc — type '$type' ($count)", TokenKind.COMMENT)))
        lines += CodeLine(n++, listOf(CodeToken("", TokenKind.PLAIN)))
        for (e in entries) {
            val cfg = if (e.config.isBlank()) "" else " [${e.config}]"
            val text = "${e.reference}$cfg = ${e.value}    ${e.hexId}"
            lines += CodeLine(n++, listOf(CodeToken(text, TokenKind.PLAIN)))
        }
        return CodeDocument(node, type, view, lines)
    }

    private fun placeholder(node: NodeId, view: CodeView, title: String, message: String): CodeDocument =
        CodeDocument(node, title, view, listOf(CodeLine(1, listOf(CodeToken(message, TokenKind.COMMENT)))))

    private fun labelOf(node: NodeId): String =
        node.value.substringAfter(':').substringAfterLast('/').ifEmpty { "resource" }
}

/**
 * The minimal projection of the engine's resource facade the [ResourceSurface] needs. Implemented for
 * production by `CoreApiDecompilerClient`'s `ApkResourcesProvider` (a thin wrapper over `ApkResources`)
 * and by test fakes. Decode calls are cheap and synchronous on the engine; nothing here blocks.
 */
public interface ResourceProvider {
    /**
     * Whether the container carries an `AndroidManifest.xml` **entry at all**, independent of whether it
     * decodes. A present entry always earns a tree node; a present-but-undecodable one is shown marked
     * (rule 4: it must not silently vanish), so this — not decodability — gates the manifest node.
     */
    public val hasManifestEntry: Boolean

    /** Decoded `AndroidManifest.xml` text, or `null` if absent/undecodable. */
    public fun decodeManifest(): String?

    /** Every `res/…xml` path present, sorted. */
    public val xmlResourcePaths: List<String>

    /** Decode one `res/…xml` path to text, or `null` if it cannot be decoded. */
    public fun decodeXml(path: String): String?

    /** The resource-table types (name + entry count), sorted; empty when there is no `resources.arsc`. */
    public val tableTypes: List<ResTableType>

    /** The entries of one table type, pre-formatted for display. */
    public fun tableEntries(type: String): List<ResTableEntry>

    /** Non-fatal decode diagnostics accumulated so far (path-prefixed for per-file notes). */
    public val diagnostics: List<String>
}

/** One resource-table type for the tree: its name and how many entries it holds. */
public data class ResTableType(val name: String, val entryCount: Int)

/** One resource-table entry, flattened to display text (mirrors `core:api`'s `ResourceEntryView`). */
public data class ResTableEntry(
    val reference: String,
    val config: String,
    val value: String,
    val hexId: String,
)

/** The Resources tree: roots plus lazily-resolvable children, keyed by parent [NodeId]. */
public class ResourceTreeModel(
    public val roots: List<TreeNode>,
    public val children: Map<NodeId, List<TreeNode>>,
) {
    public fun childrenOf(parent: NodeId): List<TreeNode> = children[parent].orEmpty()
}
