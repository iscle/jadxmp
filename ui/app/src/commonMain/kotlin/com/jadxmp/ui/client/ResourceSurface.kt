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

    /**
     * Resource text at or above this many characters is NOT rendered into the viewer (jadx uses a ~10 MB
     * cap). A placeholder is shown instead, so opening a huge file never freezes the single-threaded UI
     * on a giant text render + colorize pass (rule 1). The cap is on the decoded character count — a
     * cheap, honest proxy for the file's size.
     */
    internal const val MAX_VIEW_CHARS: Int = 10 * 1024 * 1024

    /** True if [node] belongs to the Resources tree (so [DecompilerClient.code] routes it here). */
    public fun isResourceNode(node: NodeId): Boolean {
        val v = node.value
        return v == TABLE_ID ||
            v.startsWith(RES_PREFIX) ||
            v.startsWith(DIR_PREFIX) ||
            v.startsWith(TYPE_PREFIX)
    }

    // ── Content detection (route raw bytes → image / hex / text viewer) ─────────────

    /**
     * Classify a resource's raw [bytes] into the viewer that should render it (the router the workbench
     * uses to pick [com.jadxmp.ui.workbench.ImageViewer] / [com.jadxmp.ui.workbench.HexViewer] / the text
     * code viewer). Pure and total — never throws, so a hostile blob always resolves to *some* viewer
     * (rule 4). Image magic wins first; otherwise a NUL byte or a dense run of control characters marks
     * the content binary (→ hex); everything else is treated as text and left to the existing colorizer
     * path. [path] is accepted for symmetry/future extension but the decision is content-driven (magic
     * bytes are authoritative — an extension can lie).
     */
    public fun classifyContent(path: String, bytes: ByteArray): ResourceContentKind = when {
        imageFormatOf(bytes) != null -> ResourceContentKind.IMAGE
        looksLikeText(bytes) -> ResourceContentKind.TEXT
        else -> ResourceContentKind.HEX
    }

    /**
     * The raster-image container [bytes] begins with, sniffed from its magic bytes, or `null` when the
     * head matches no known image format. Recognizes PNG, JPEG, GIF, WebP (RIFF/WEBP) and BMP — the
     * formats jadx-gui previews. Content-based (never trusts an extension) and bounds-checked, so a short
     * or empty array is simply "not an image".
     */
    public fun imageFormatOf(bytes: ByteArray): ImageFormat? = when {
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        startsWith(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> ImageFormat.PNG
        // JPEG (JFIF/EXIF/raw): FF D8 FF
        startsWith(bytes, 0xFF, 0xD8, 0xFF) -> ImageFormat.JPEG
        // GIF: "GIF8" (covers both 87a and 89a)
        startsWith(bytes, 0x47, 0x49, 0x46, 0x38) -> ImageFormat.GIF
        // BMP: "BM"
        startsWith(bytes, 0x42, 0x4D) -> ImageFormat.BMP
        // WebP: "RIFF" <4-byte size> "WEBP"
        bytes.size >= 12 && startsWith(bytes, 0x52, 0x49, 0x46, 0x46) &&
            byteAt(bytes, 8) == 0x57 && byteAt(bytes, 9) == 0x45 &&
            byteAt(bytes, 10) == 0x42 && byteAt(bytes, 11) == 0x50 -> ImageFormat.WEBP
        else -> null
    }

    /**
     * Heuristic "is this text?" over a bounded prefix of [bytes]. A single NUL byte marks it binary
     * outright (the classic text/binary discriminator); otherwise a **dense** run of non-whitespace
     * control characters (≥ ~5% of the sampled bytes) also marks it binary. Tab/CR/LF are text, and
     * high bytes (0x80–0xFF, i.e. UTF-8 lead/continuation) are allowed so genuine UTF-8 text is not
     * misfiled as binary. Empty content is treated as text (nothing to hex-dump). Bounded to the first
     * few KB so classification stays O(1)-ish on a multi-MB blob (rule 1).
     */
    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val limit = minOf(bytes.size, TEXT_SNIFF_LIMIT)
        // Skip a leading UTF-8 BOM so its bytes don't count against the control-character budget.
        var i = if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) 3 else 0
        var suspicious = 0
        while (i < limit) {
            when (val b = bytes[i].toInt() and 0xFF) {
                0x00 -> return false // NUL → definitely binary
                0x09, 0x0A, 0x0D -> {} // tab / LF / CR are ordinary text
                0x7F -> suspicious++ // DEL
                else -> if (b < 0x20) suspicious++ // other C0 control char
            }
            i++
        }
        // Allow a small fraction of stray control bytes; beyond that, treat as binary.
        return suspicious * 20 < limit
    }

    /** True when file [name]'s extension is a raster image type — a cheap TREE-icon hint (see [imageFormatOf]). */
    private fun isImageName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    /** [bytes][index] as an unsigned 0..255 int, or -1 when out of range (keeps sniffing branch-safe). */
    private fun byteAt(bytes: ByteArray, index: Int): Int =
        if (index < bytes.size) bytes[index].toInt() and 0xFF else -1

    /** True when [bytes] begins with the given unsigned-byte [prefix]. Bounds-checked. */
    private fun startsWith(bytes: ByteArray, vararg prefix: Int): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) if (bytes[i].toInt() and 0xFF != prefix[i]) return false
        return true
    }

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    /** Prefix of a resource sampled by [looksLikeText]; a few KB is plenty to decide text vs binary. */
    private const val TEXT_SNIFF_LIMIT = 8 * 1024

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

        // res/ leaves: text XML (decoded lazily) plus any binary blobs (images / raw files), folded into
        // one directory tree. Binary paths are gated to the res/ subtree so buildResFolders' "segs[0] is
        // the res root" invariant holds; a bytes-carrying backend supplies them via binaryResourcePaths
        // (empty by default, so a text-only provider builds exactly the tree it did before).
        val xmlPaths = provider.xmlResourcePaths.filter { it.isNotBlank() }
        val binPaths = provider.binaryResourcePaths.filter { it.isNotBlank() && it.startsWith("res/") }
        val resPaths = xmlPaths + binPaths
        if (resPaths.isNotEmpty()) {
            roots += TreeNode(NodeId(dirId("res")), "res", NodeKind.DIRECTORY, hasChildren = true)
            buildResFolders(resPaths, children)
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
                    // Leaf id carries the full `res/…` path (childPath == the whole path when isFile), so
                    // markResourceChildren/document/rawResource can recover the key from the id later. Kind
                    // is by extension: `.xml` → RESOURCE (lazily decoded + markable), an image → IMAGE, any
                    // other binary → FILE. Only RESOURCE (xml) leaves are decode-probed by
                    // markResourceChildren, so an image/binary leaf never gains a spurious "(could not
                    // decode)" marker.
                    val kind = when {
                        isImageName(segs[j]) -> NodeKind.IMAGE
                        segs[j].endsWith(".xml", ignoreCase = true) -> NodeKind.RESOURCE
                        else -> NodeKind.FILE
                    }
                    TreeNode(childId, segs[j], kind, hasChildren = false)
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
            v == MANIFEST_ID -> renderTextResource(node, view, MANIFEST_PATH, provider.decodeManifest(), provider.diagnostics)
            v.startsWith(RES_PREFIX) -> {
                val path = v.removePrefix(RES_PREFIX)
                renderTextResource(node, view, path, provider.decodeXml(path), provider.diagnostics)
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

    /**
     * Render a decoded text resource, choosing a syntax colorizer by the file extension (manifest and
     * every `res/…xml` colorize as XML; `.json`/`.css`/`.sql`/… get their own; unknown types are shown
     * plain — see [ResourceColorizers]). A `null` decode becomes an honest placeholder; a file whose
     * decoded text exceeds [MAX_VIEW_CHARS] is not rendered at all (a "too large to view" placeholder is
     * shown instead of freezing on a giant colorize). A partial/best-effort decode still surfaces its
     * per-file decode notes so a salvaged file is never presented as complete (rule 4).
     */
    private fun renderTextResource(
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
        // Large-file guard (rule 1): never load a huge document into the viewer — checked BEFORE the
        // colorize pass, which is the expensive part that would freeze the single-threaded UI.
        if (text.length >= MAX_VIEW_CHARS) {
            return placeholder(node, view, title, "// File too large to view (${megabytesOf(text.length)} MB)")
        }
        // The colorizer returns 1-based lines; the decode-notes footer below continues where it leaves off.
        val lines = ArrayList<CodeLine>(ResourceColorizers.colorize(path, text))
        // A partial/best-effort decode is still returned, but its diagnostics (prefixed with the path by
        // the engine) must surface so the reader knows the file isn't wholly decoded. Use a comment style
        // that fits the file (XML/HTML → markup comment; everything else → line comment).
        val notes = diagnostics.filter { it.startsWith("$path:") }
        if (notes.isNotEmpty()) {
            val markup = ResourceColorizers.isMarkup(path)
            var n = lines.size + 1
            lines += CodeLine(n++, listOf(CodeToken("", TokenKind.PLAIN)))
            lines += CodeLine(n++, listOf(CodeToken(comment("decode notes", markup), TokenKind.COMMENT)))
            for (note in notes) {
                lines += CodeLine(n++, listOf(CodeToken(comment(note, markup), TokenKind.COMMENT)))
            }
        }
        return CodeDocument(node, title, view, lines)
    }

    /** A one-line comment wrapping [text] in the file's comment syntax (markup `<!-- … -->` vs `// …`). */
    private fun comment(text: String, markup: Boolean): String = if (markup) "<!-- $text -->" else "// $text"

    /** Whole-plus-one-decimal megabytes for [chars], integer-only (wasm has no `String.format`). */
    internal fun megabytesOf(chars: Int): String {
        val tenths = chars.toLong() * 10 / (1024L * 1024L)
        return "${tenths / 10}.${tenths % 10}"
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

/** Which viewer a resource's raw bytes route to. See [ResourceSurface.classifyContent]. */
public enum class ResourceContentKind {
    /** Human-readable text — the existing colorized code viewer renders it. */
    TEXT,

    /** A raster image ([ResourceSurface.imageFormatOf] matched) — the image viewer renders it. */
    IMAGE,

    /** Opaque binary — the hex viewer renders an offset / hex / ASCII dump. */
    HEX,
}

/** A raster-image container recognized by magic bytes. [label] is the short name shown in captions. */
public enum class ImageFormat(public val label: String) {
    PNG("PNG"),
    JPEG("JPEG"),
    GIF("GIF"),
    WEBP("WebP"),
    BMP("BMP"),
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

    /**
     * Every non-text binary resource path present (images and other raw blobs under `res/`), sorted.
     * Feeds the image/hex leaves [buildTree] adds alongside the `res/…xml` leaves. Defaults to empty so
     * a provider (or test fake) that surfaces only text resources is unaffected — this is the seam a
     * bytes-carrying engine backend fills in.
     */
    public val binaryResourcePaths: List<String> get() = emptyList()

    /**
     * The raw (already-inflated) bytes of a resource entry by [path], or `null` when the entry is absent
     * or its bytes are not reachable. The workbench sniffs these to pick an image/hex viewer (see
     * [classifyContent]). Defaults to `null`: a text-only provider exposes no bytes, and the viewers
     * degrade to the existing text/placeholder path (rule 4).
     */
    public fun rawResource(path: String): ByteArray? = null

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
