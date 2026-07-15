package com.jadxmp.ui.client

import com.jadxmp.api.ApkResources
import com.jadxmp.api.Decompiler
import com.jadxmp.api.DecompilerArgs
import com.jadxmp.api.MemberInfo
import com.jadxmp.api.MemberKind
import com.jadxmp.api.OutputFormat
import com.jadxmp.api.RenameResult
import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.CodeMetadata
import com.jadxmp.codegen.CodeNodeRef
import com.jadxmp.codegen.DefinitionAnnotation
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.codegen.RefKind
import com.jadxmp.codegen.ReferenceAnnotation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The production [DecompilerClient]: it adapts a `core:api` [Decompiler] to the shape the workbench
 * consumes. Everything the UI needs is derived from the two things the facade exposes —
 * [Decompiler.classNames] (for the tree and class search) and [Decompiler.decompileClass] (Java text
 * plus per-offset `CodeMetadata`).
 *
 * ## Coloring: a lexer for syntax, engine metadata for semantics
 * [code] delegates to [JavaColorizer], which colors token *shapes* with [JavaLexer] and then lets the
 * engine metadata *override* identifier tokens with a precise [TokenKind] and a jump-to-definition
 * target. See those types for the rationale.
 *
 * ## Concurrency (single monitor over all mutable state)
 * `core:api`'s cached [Decompiler.decompileClass] path is documented as *not* thread-safe, and the UI
 * routinely opens a new file while a class is mid-render. So a single [Mutex] guards **every** access
 * to the mutable engine state and the derived snapshot: the [Decompiler] itself, the [documentCache],
 * and the [model] (tree + class index). Reads ([rootNodes]/[childNodes]/[search]) take the lock only
 * for a fast snapshot copy; decompilation runs under the lock on [Dispatchers.Default] — off the UI
 * thread on JVM/Android, a harmless same-thread dispatch on single-threaded wasm. This closes the
 * race where [open]'s `documentCache.clear()` on a background thread could interleave with an in-flight
 * [code] mutating the same map on the UI thread.
 *
 * Wasm-safe: no `java.*`; input bytes arrive pre-read via [OpenRequest.bytes] from a platform shell.
 */
class CoreApiDecompilerClient(
    args: DecompilerArgs = DecompilerArgs(),
) : DecompilerClient {

    private val decompiler = Decompiler(args)

    /** The one monitor guarding [decompiler], [documentCache] and [model] (see class KDoc). */
    private val lock = Mutex()

    private val _session = MutableStateFlow<SessionState>(SessionState.Empty)
    override val session: StateFlow<SessionState> = _session.asStateFlow()

    /** Immutable, whole-swap snapshot of everything derived from the loaded input. Guarded by [lock]. */
    private var model = LoadedModel()

    /**
     * The user renames in effect, as the engine's `CodeNodeRef → chosen name` map, overlaid onto the tree
     * labels so a renamed class/member shows its new name in the navigation tree (the engine's
     * [Decompiler.classNames]/[Decompiler.classMembers] report *binary/raw* names, which are invariant under
     * renaming — an alias is a codegen spelling only). Empty by construction on [open] (a fresh load has no
     * renames) and refreshed by [rebuildModel] on every rename/clear. Guarded by [lock]; read only under it.
     */
    private var renameOverlay: Map<CodeNodeRef, String> = emptyMap()

    /** Rendered documents cached by node+view. Guarded by [lock]. */
    private val documentCache = LinkedHashMap<DocCacheKey, CodeDocument>()

    /**
     * The decoded resources of the current input, projected to the pure [ResourceProvider] the
     * [ResourceSurface] consumes, plus the pre-built resources tree. `null`/empty when the input is not
     * a resource-bearing APK. Both are whole-swapped under [lock] on every [open], like [model]/[documentCache].
     */
    private var resourceProvider: ResourceProvider? = null
    private var resourceTree = ResourceTreeModel(emptyList(), emptyMap())

    override suspend fun open(request: OpenRequest) {
        val bytes = request.bytes
        if (bytes == null) {
            _session.value = SessionState.Failed("No file bytes provided for '${request.name}'.")
            return
        }
        _session.value = SessionState.Loading("Reading ${request.name}", progress = 0.3f)
        // Capture the class count AND the engine diagnostics inside the same lock. Reading
        // `decompiler.diagnostics` outside `withLock` raced a concurrent `load()` mutating that list
        // (a ConcurrentModificationException under simultaneous FAILED opens); the snapshot below is
        // taken while we still hold the monitor, so the session verdict is computed from stable data.
        val outcome = withContext(Dispatchers.Default) {
            lock.withLock {
                documentCache.clear()
                resourceProvider = null
                resourceTree = ResourceTreeModel(emptyList(), emptyMap())
                val loaded = try {
                    decompiler.load(request.name, bytes)
                } catch (e: Exception) {
                    // Defensive: core:api is fault-isolated, but never let a bad file crash the UI.
                    model = LoadedModel()
                    return@withLock LoadOutcome(-1, decompiler.diagnostics)
                }
                // Badge each class row by its kind (interface/enum/annotation/class) AND its access
                // visibility (public/protected/private/package), read cheaply from the model with no
                // decompilation and fault-isolated to the generic CLASS badge (no overlay) on any fault.
                // A fresh load carries no user renames, so [rebuildModel]'s overlay is empty here (labels
                // are the raw binary names, byte-identical to the pre-rename tree).
                rebuildModel()
                // Build the resources tree here (under the same lock) so a non-APK input honestly yields
                // an empty Resources tree, and so the one-time manifest/arsc decode the tree needs runs on
                // Dispatchers.Default off the UI thread rather than on first tree paint. Fault-isolated:
                // a resource-decode fault degrades to an empty tree + engine diagnostics, never a crash.
                decompiler.resources?.let { res ->
                    val provider = ApkResourcesProvider(res)
                    resourceProvider = provider
                    resourceTree = ResourceSurface.buildTree(provider)
                }
                LoadOutcome(loaded, decompiler.diagnostics)
            }
        }
        _session.value = when {
            outcome.count < 0 -> SessionState.Failed("Could not read '${request.name}'.")
            outcome.count == 0 && outcome.diagnostics.isNotEmpty() ->
                SessionState.Failed(outcome.diagnostics.joinToString("; "))
            else -> SessionState.Ready(request.name, classCount = outcome.count)
        }
    }

    /** Class count + a diagnostics snapshot, both captured under [lock] (see [open]). */
    private data class LoadOutcome(val count: Int, val diagnostics: List<String>)

    override suspend fun rootNodes(tree: TreeKind): List<TreeNode> = when (tree) {
        TreeKind.CLASSES -> lock.withLock { model.roots }
        // Empty for a non-APK input (resources == null); populated from ApkResources at open().
        TreeKind.RESOURCES -> lock.withLock { resourceTree.roots }
    }

    override suspend fun childNodes(parent: NodeId): List<TreeNode> {
        val v = parent.value
        return when {
            // A class row expands to its declared members (fields/methods/nested), enumerated cheaply
            // from the loaded model — no decompilation. Routed through Dispatchers.Default like [code]
            // for a consistent off-UI-thread path (a harmless same-thread dispatch on wasm).
            v.startsWith("cls:") -> {
                val fqn = v.substring(4)
                memberNodesUnder(topLevel = fqn, owner = fqn)
            }
            // A nested-class member row expands to *its* members (recurse), preserving the top-level.
            v.startsWith(MemberTree.PREFIX) -> nestedMemberNodes(parent)
            // A class-package parent lives in [model]; a resource folder / table type in [resourceTree].
            else -> resourceOrPackageChildren(parent)
        }
    }

    /**
     * Children of a class-package (from [model]) or a resource folder / table root (from [resourceTree]).
     * For a resource folder, each `res/…xml` leaf's `(could not decode)` marker is computed **now** — lazily,
     * on expand — by decoding only this folder's leaves, rather than decoding the whole `res/` subtree at
     * open (which would freeze the single wasm UI thread; rule 1). Runs on [Dispatchers.Default] under the
     * [lock] like the other decode paths (a harmless same-thread dispatch on wasm). Fault-isolated: the
     * decode already degrades a bad file to `null` (→ an honest marker), never a throw.
     */
    private suspend fun resourceOrPackageChildren(parent: NodeId): List<TreeNode> =
        withContext(Dispatchers.Default) {
            lock.withLock {
                model.children[parent]?.let { return@withLock it }
                val kids = resourceTree.childrenOf(parent)
                val provider = resourceProvider
                if (provider != null && kids.isNotEmpty()) ResourceSurface.markResourceChildren(provider, kids) else kids
            }
        }

    override suspend fun classNodes(): List<TreeNode> = lock.withLock {
        // Only the flat name index is read here (no decompilation); the scan later calls [code] per
        // class, which is where the document cache is populated/reused. Sorted for deterministic scan
        // progress ("scanned N/total" advances in a stable order across runs).
        model.classNames.sorted().map { fqn ->
            TreeNode(
                id = NodeId("cls:$fqn"),
                // Rename-aware label (the `cls:` id stays the binary name); raw simple name with no renames.
                label = classLabel(fqn),
                kind = model.kindOf(fqn),
                // A class expands to its declared members (childNodes(cls:…)); show the expander.
                hasChildren = true,
                secondary = fqn.substringBeforeLast('.', missingDelimiterValue = ""),
                visibility = model.visibilityOf(fqn),
            )
        }
    }

    override fun availableViews(node: NodeId): List<CodeView> =
        // A class-backed node (cls:/mbr:) offers the two source backends (Java/Kotlin, rendered from the
        // one lowered IR) plus SMALI (disassembled straight from the input model, no pipeline). JAVA stays
        // first so it remains the default view (unchanged open behavior); SMALI last, matching jadx-gui's
        // Java | Smali ordering. Resource/manifest/xml nodes are not code, so they offer Java only.
        if (classFqnOf(node) != null) listOf(CodeView.JAVA, CodeView.KOTLIN, CodeView.SMALI) else listOf(CodeView.JAVA)

    override suspend fun code(node: NodeId, view: CodeView): CodeDocument {
        val key = DocCacheKey(node, view)
        val fqn = classFqnOf(node)
        return withContext(Dispatchers.Default) {
            lock.withLock {
                documentCache[key]?.let { return@withLock it }
                if (ResourceSurface.isResourceNode(node)) {
                    // Resource text (manifest/xml/table) decodes cheaply and synchronously on the client.
                    // A missing provider (non-APK) or a null decode both degrade to an honest placeholder.
                    val provider = resourceProvider
                    val doc = if (provider != null) {
                        ResourceSurface.document(provider, node, view)
                    } else {
                        ResourceSurface.unavailable(node, view)
                    }
                    documentCache[key] = doc
                    return@withLock doc
                }
                if (fqn == null) {
                    return@withLock errorDocument(node, view, "// unsupported node: ${node.value}")
                }
                // The bytecode view: disassemble the class straight from the input model (no pipeline). It
                // shares the (node, view) cache with Java/Kotlin, so toggling to Smali is cached too. Fault
                // isolation (rule 4): a null/undecodable smali degrades to an honest placeholder, never a
                // crash. Coloring is minimal + syntactic (no engine metadata for smali), see SmaliColorizer.
                if (view == CodeView.SMALI) {
                    // Smali is per DEX class, so a member/nested node must resolve to its OWN declaring dex
                    // class — not the top-level unit that Java/Kotlin fold it into. classFqnOf() returns the
                    // top-level fqn (right for the source backends); smaliClassFqnOf() returns the inner
                    // class for a nested-class node and the declaring class for a plain member.
                    val smaliFqn = smaliClassFqnOf(node) ?: fqn
                    val smali = decompiler.smali(smaliFqn)
                        ?: return@withLock errorDocument(node, view, "# smali unavailable: $smaliFqn")
                    val doc = CodeDocument(node, smaliFqn.substringAfterLast('.'), view, SmaliColorizer.colorize(smali))
                    documentCache[key] = doc
                    return@withLock doc
                }
                // The per-call format override routes the SAME lowered class to the Java or Kotlin backend
                // without reloading. The (node, view) cache key means a class rendered as both Java and
                // Kotlin keeps both docs — switching the toggle is instant after first render, and neither
                // clobbers the other. Kotlin's honesty markers (`// JADXMP ERROR`) survive as comment text
                // and are shown as-is (fault isolation: a partial/error Kotlin render is shown honestly,
                // never silently replaced by Java).
                val decompiled = decompiler.decompileClass(fqn, outputFormatFor(view))
                    ?: return@withLock errorDocument(node, view, "// class not found: $fqn")
                // Coloring: the Java lexer classifies token shapes for BOTH formats. Kotlin is close enough
                // to Java lexically (keywords/strings/comments/numbers) that this is acceptable-but-imperfect
                // (e.g. `fun`/`val` are not Kotlin keywords to the Java lexer); a dedicated KotlinLexer is a
                // tracked follow-up. Engine CodeMetadata still overrides identifier tokens with precise
                // TYPE/METHOD/FIELD kinds + jump targets regardless of format.
                // resolveClass reads model.classIndex; we hold the lock, so the read is safe.
                val lines = JavaColorizer.colorize(decompiled.code, decompiled.metadata.code) { name ->
                    model.classIndex[canonicalName(name)]?.let { NodeId("cls:$it") }
                }
                val doc = CodeDocument(node, decompiled.simpleName, view, lines)
                documentCache[key] = doc
                doc
            }
        }
    }

    /**
     * Raw bytes of a resource node for the image / hex viewer, forwarded from the [ResourceProvider] seam
     * under the same [lock] as the other resource decodes. Only genuine binary `res/` blobs route here:
     * [ApkResourcesProvider] surfaces them via `ApkResources.binaryResourcePaths` (the `res/` image/raw
     * leaves, listed cheaply from the container's central directory) and inflates the selected one on demand
     * via `ApkResources.rawResource` — so the image/hex viewers render straight from a real APK with no eager
     * inflation of the whole `res/` tree.
     *
     * The [binaryResourcePaths][ResourceProvider.binaryResourcePaths] gate is load-bearing: it excludes
     * `AndroidManifest.xml`, `resources.arsc` and every compiled `res/…xml`, which are eagerly held as raw
     * COMPILED binary XML (AXML magic `03 00 08 00`, NUL at index 1). Without the gate, `rawResource` would
     * eager-hit those bytes and the workbench's content sniffer — seeing the NUL — would classify them HEX
     * and render a hex dump instead of the decoded XML. Gating keeps them on `null` here so they fall through
     * to the decoded-text document path. A non-APK input (`resourceProvider == null`), a non-`res:` node, or
     * any path that is not a binary blob all yield `null` (the viewer shows its honest placeholder; rule 4).
     */
    override suspend fun resourceBytes(node: NodeId): ByteArray? {
        val v = node.value
        if (!ResourceSurface.isResourceNode(node) || !v.startsWith("res:")) return null
        val path = v.removePrefix("res:")
        return withContext(Dispatchers.Default) {
            lock.withLock {
                val provider = resourceProvider ?: return@withLock null
                // Gate: only a genuine binary res/ blob (image / raw file) is handed to the byte→image/hex
                // path. binaryResourcePaths excludes the manifest, resources.arsc and every compiled res/…xml
                // by construction, so those return null here and keep decoding to TEXT via document() —
                // rawResource would otherwise eager-hit their raw AXML bytes and the sniffer would hex-dump
                // them (regression guard, see CoreApiDecompilerClientResourcesTest).
                if (path in provider.binaryResourcePaths) provider.rawResource(path) else null
            }
        }
    }

    override suspend fun search(query: SearchQuery): SearchResults {
        val needle = query.text.trim()
        if (needle.isEmpty() || SearchScope.CLASS !in query.scopes) {
            return SearchResults(query, emptyList())
        }
        val regex: Regex? = if (query.useRegex) {
            runCatching {
                Regex(needle, if (query.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
            }.getOrNull()
        } else {
            null
        }
        val matcher: (String) -> Boolean = { text ->
            if (query.useRegex) regex?.containsMatchIn(text) ?: false
            else text.contains(needle, ignoreCase = query.ignoreCase)
        }
        val snapshot = lock.withLock { model }
        val matches = snapshot.classNames
            .filter { matcher(it) || matcher(it.substringAfterLast('.')) }
            .sorted()
            .map { fqn ->
                SearchResult(
                    nodeId = NodeId("cls:$fqn"),
                    title = fqn.substringAfterLast('.'),
                    subtitle = fqn.substringBeforeLast('.', missingDelimiterValue = "(default package)"),
                    kind = snapshot.kindOf(fqn),
                )
            }
        return SearchResults(query, matches)
    }

    override suspend fun memberLocation(memberNodeId: NodeId): MemberLocation? {
        val (topLevel, owner, slug) = MemberTree.parse(memberNodeId) ?: return null
        val classNodeId = NodeId("cls:$topLevel")
        return withContext(Dispatchers.Default) {
            lock.withLock {
                try {
                    // Re-enumerate the owner's members and match by the same stable slug used to mint the
                    // id, recovering the real CodeNodeRef key (never re-encoded/decoded from the string).
                    val target = decompiler.classMembers(owner)
                        .firstOrNull { MemberTree.slug(sortOf(it.kind), it.signature) == slug }
                        ?: return@withLock MemberLocation(classNodeId, null)
                    // Decompiling the owner yields the TOP-LEVEL unit (nested folds into its outer), whose
                    // metadata carries this member's DefinitionAnnotation. A null line = open, don't scroll.
                    val decompiled = decompiler.decompileClass(owner)
                        ?: return@withLock MemberLocation(classNodeId, null)
                    val line = MemberDefinitionLocator.locate(decompiled.code, decompiled.metadata.code, target.key)
                    MemberLocation(classNodeId, line)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Fault isolation (rule 4): a member that won't resolve opens its class without scroll.
                    MemberLocation(classNodeId, null)
                }
            }
        }
    }

    override suspend fun exportProject(view: CodeView?): List<ExportFile> {
        // Java unless the user is viewing Kotlin; Smali is not a whole-project export format, so it too
        // exports Java (matching jadx-gui's "save all sources" defaulting to the decompiled language).
        val format = if (view == CodeView.KOTLIN) OutputFormat.KOTLIN else OutputFormat.JAVA
        // Held under the same monitor as decompileClass (the cached path exportSources drives is not
        // thread-safe); the export runs on Dispatchers.Default off the UI thread, a harmless same-thread
        // dispatch on wasm. Fault-isolated end-to-end in the engine — one bad class never sinks the export.
        return withContext(Dispatchers.Default) {
            lock.withLock {
                decompiler.exportSources(format).map { ExportFile(it.path, it.bytes) }
            }
        }
    }

    /**
     * Resolve the clicked token to its exact engine [CodeNodeRef], then invert the whole-program reference
     * metadata into referring sites (see [Decompiler.findUsages]). Two engine steps, both under the single
     * [lock] on [Dispatchers.Default] (off the UI thread on JVM; a harmless same-thread dispatch on wasm):
     *
     *  1. **Resolve** — decompile the OPEN class (cached [Decompiler.decompileClass]) and read the
     *     Reference/Definition annotation at the clicked (line, token) straight from its [CodeMetadata]
     *     ([referenceOnLine]). That ref IS the index key — the same `CodeNodeRef` the codegen recorded —
     *     so no lossy string reconstruction is involved; a member keeps its precise overload identity.
     *  2. **Query** — [Decompiler.findUsages] with that ref and the view's format. The FIRST such call for
     *     a format decompiles the whole app to build+cache the inverse index; later ones are lookups.
     *
     * Fault-isolated (rule 4): a token that resolves to nothing (or to a non-indexed kind), a class that
     * won't decompile, or any thrown fault yields `null` — never a crash. A resolved symbol nothing
     * references yields an empty [UsageResults.sites]. The site's `fromClass` is the BINARY name the tree
     * navigates with, so each row reopens via the same `cls:` id space.
     */
    override suspend fun findUsages(query: UsageQuery): UsageResults? {
        val fqn = classFqnOf(query.classNode) ?: return null
        val format = outputFormatFor(query.view)
        return withContext(Dispatchers.Default) {
            lock.withLock {
                try {
                    val origin = decompiler.decompileClass(fqn, format) ?: return@withLock null
                    val meta = origin.metadata.code ?: return@withLock null
                    val target = referenceOnLine(origin.code, meta, query.line, query.token, query.tokenKind)
                        ?: return@withLock null
                    val sites = decompiler.findUsages(target, format)
                    UsageResults(
                        symbol = usageSymbolLabel(target),
                        kind = nodeKindOf(target.refKind),
                        sites = sites.map { site ->
                            UsageSiteRow(
                                classNode = NodeId("cls:${site.fromClass}"),
                                classLabel = site.fromClass.substringAfterLast('.'),
                                memberLabel = enclosingMemberLabel(site.fromMember),
                                line = site.line,
                                kind = nodeKindOf(site.kind),
                            )
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Any resolve/query fault degrades to "no result", never a crash (rule 4).
                    null
                }
            }
        }
    }

    /**
     * Resolve the clicked token to its exact engine [CodeNodeRef] — the SAME click-to-definition step
     * [findUsages] uses ([referenceOnLine] over a fresh [Decompiler.decompileClass] render) — then apply
     * [Decompiler.rename], all under the single [lock] on [Dispatchers.Default]. On success the engine has
     * already invalidated ITS render + usage caches; we additionally drop THIS client's own caches so a
     * re-fetch renders the new name: [documentCache] (stale rendered text) and [model] via [rebuildModel]
     * (so the tree re-derives with the new [renameOverlay] label). The [RenameResult] is projected to the
     * engine-free [RenameOutcome] the workbench consumes.
     *
     * Fault-isolated (rule 4): a token that resolves to nothing (or a non-renamable kind), a class that
     * won't decompile, or any thrown fault yields a [RenameOutcome.Rejected] with a readable reason — never
     * a crash. The engine's own validation/collision rejections come back as [RenameOutcome.Rejected] too,
     * so jadxmp never silently mangles a name.
     */
    override suspend fun rename(target: RenameQuery, newName: String): RenameOutcome {
        val fqn = classFqnOf(target.classNode)
            ?: return RenameOutcome.Rejected("Select a class, method or field in the code to rename.")
        val format = outputFormatFor(target.view)
        return withContext(Dispatchers.Default) {
            lock.withLock {
                try {
                    val origin = decompiler.decompileClass(fqn, format)
                        ?: return@withLock RenameOutcome.Rejected("This class could not be decompiled.")
                    val meta = origin.metadata.code
                        ?: return@withLock RenameOutcome.Rejected("No symbol information is available here.")
                    val ref = referenceOnLine(origin.code, meta, target.line, target.token, target.tokenKind)
                        ?: return@withLock RenameOutcome.Rejected("Select a class, method or field in the code to rename.")
                    val result = decompiler.rename(ref, newName)
                    if (result is RenameResult.Applied) {
                        // The engine invalidated its caches; drop the client's own so a re-fetch is fresh.
                        documentCache.clear()
                        rebuildModel()
                    }
                    result.toRenameOutcome()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    RenameOutcome.Rejected("Rename failed: ${e.message ?: e.toString()}")
                }
            }
        }
    }

    /**
     * Drop every user rename ([Decompiler.clearRenames]) and invalidate this client's own render/tree caches
     * so a subsequent fetch reflects the revert. Held under [lock] on [Dispatchers.Default] like [rename];
     * a no-op cost when there were no renames (the engine short-circuits and [rebuildModel] rebuilds an
     * identical tree).
     */
    override suspend fun clearRenames() {
        withContext(Dispatchers.Default) {
            lock.withLock {
                decompiler.clearRenames()
                documentCache.clear()
                rebuildModel()
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * Re-derive the class-tree [model] from the engine's current [Decompiler.classNames], refreshing the
     * [renameOverlay] first so class-row labels reflect any user rename. Must be called under [lock] (it
     * reads the engine). Cheap — no decompilation; the badge lookup is a no-decompile model read, fault-
     * isolated to the generic CLASS badge. With no renames the overlay is empty, so labels are the raw
     * binary names — byte-identical to the pre-rename tree.
     */
    private fun rebuildModel() {
        renameOverlay = decompiler.renames
        model = LoadedModel.build(decompiler.classNames, ::classLabel) { fqn ->
            classNodeBadge(runCatching { decompiler.classInfo(fqn) }.getOrNull())
        }
    }

    /**
     * The tree label for a class by its binary [fqn]: its user-renamed name if one is in effect, else the
     * simple binary name. The `cls:` NodeId stays keyed on the binary [fqn] (invariant under renaming), so
     * only the shown label changes — navigation is unaffected. Reads [renameOverlay]; call under [lock].
     */
    private fun classLabel(fqn: String): String =
        renameOverlay[ClassNodeRef(fqn)] ?: fqn.substringAfterLast('.')

    /** Member rows for [owner] (top-level ancestor [topLevel]), enumerated from the model under [lock]. */
    private suspend fun memberNodesUnder(topLevel: String, owner: String): List<TreeNode> =
        withContext(Dispatchers.Default) {
            lock.withLock {
                try {
                    val descriptors = decompiler.classMembers(owner).map { it.toDescriptor() }
                    MemberTree.memberNodes(topLevel, owner, descriptors)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A class whose enumeration blows up simply shows no members — never a crash.
                    emptyList()
                }
            }
        }

    /** Members of the nested class referenced by member node [parent] (a NESTED_CLASS row). */
    private suspend fun nestedMemberNodes(parent: NodeId): List<TreeNode> {
        val (topLevel, owner, slug) = MemberTree.parse(parent) ?: return emptyList()
        // Find the nested-class member this row stands for, then enumerate the nested class's own members.
        val nestedFqn = withContext(Dispatchers.Default) {
            lock.withLock {
                try {
                    decompiler.classMembers(owner)
                        .firstOrNull { MemberTree.slug(sortOf(it.kind), it.signature) == slug }
                        ?.let { (it.key as? ClassNodeRef)?.fullName }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        } ?: return emptyList()
        return memberNodesUnder(topLevel = topLevel, owner = nestedFqn)
    }

    /** Map an engine [MemberInfo] to the UI [MemberDescriptor], carrying a nested class's fqn+kind for recursion/badging. */
    private fun MemberInfo.toDescriptor(): MemberDescriptor {
        val nestedFqn = (key as? ClassNodeRef)?.fullName
        return MemberDescriptor(
            sort = sortOf(kind),
            // Rename-aware label if the user renamed this member (matched by its exact engine key). The
            // [signature] is deliberately left RAW: it feeds the member NodeId slug (MemberTree.slug), which
            // must stay stable for navigation to keep resolving; only the shown label follows the rename.
            displayName = renameOverlay[key] ?: displayName,
            signature = signature,
            nestedFqn = nestedFqn,
            // A nested-class row badges by its own kind; the lookup is cheap (no decompile) and fault-isolated.
            nestedKind = nestedFqn?.let { classNodeKind(runCatching { decompiler.classInfo(it) }.getOrNull()) },
            // Access-visibility overlay from the member's own source modifiers (already computed by the model).
            visibility = visibilityOf(modifiers),
        )
    }

    private fun sortOf(kind: MemberKind): MemberSort = when (kind) {
        MemberKind.FIELD -> MemberSort.FIELD
        MemberKind.METHOD -> MemberSort.METHOD
        MemberKind.CONSTRUCTOR -> MemberSort.CONSTRUCTOR
        MemberKind.STATIC_INITIALIZER -> MemberSort.STATIC_INITIALIZER
        MemberKind.NESTED_CLASS -> MemberSort.NESTED_CLASS
    }


    /**
     * Map a UI [CodeView] to the engine [OutputFormat] driving the per-call render. Only called for the
     * two source backends; SMALI is served separately from the input model (see [code]) and never reaches
     * here, so the `else` is just a safe default.
     */
    private fun outputFormatFor(view: CodeView): OutputFormat = when (view) {
        CodeView.KOTLIN -> OutputFormat.KOTLIN
        else -> OutputFormat.JAVA
    }

    private fun classFqnOf(node: NodeId): String? {
        val v = node.value
        return when {
            v.startsWith("cls:") -> v.removePrefix("cls:")
            v.startsWith("mbr:") -> v.removePrefix("mbr:").substringBefore('#')
            else -> null
        }
    }

    /**
     * The DEX class whose smali should back [node]'s bytecode view. Unlike [classFqnOf] (which returns the
     * top-level unit the Java/Kotlin backends emit), smali is one unit **per dex class**, so:
     *  - a `cls:` node → that class;
     *  - a nested-class `mbr:` node → the **inner** class's own fqn (`Outer$Inner`), which the facade's
     *    `$`-keyed [Decompiler.smali] index resolves to the inner class's own bytecode;
     *  - any other `mbr:` node (method/field/…) → its **declaring** (owner) class.
     *
     * Must be called under [lock] (it reads the [decompiler] model). Fault-isolated: any lookup failure
     * degrades to the owner/top-level fqn rather than throwing.
     */
    private fun smaliClassFqnOf(node: NodeId): String? {
        val v = node.value
        if (v.startsWith("cls:")) return v.removePrefix("cls:")
        val (_, owner, slug) = MemberTree.parse(node) ?: return null
        val nested = try {
            decompiler.classMembers(owner)
                .firstOrNull { MemberTree.slug(sortOf(it.kind), it.signature) == slug }
                ?.let { (it.key as? ClassNodeRef)?.fullName }
        } catch (e: Exception) {
            null
        }
        // A nested-class row → the inner class's own smali; a plain member → its declaring (owner) class.
        return nested ?: owner
    }

    private fun errorDocument(node: NodeId, view: CodeView, message: String): CodeDocument =
        CodeDocument(
            nodeId = node,
            title = node.value.substringAfterLast(':').substringAfterLast('.'),
            view = view,
            lines = listOf(CodeLine(1, listOf(CodeToken(message, TokenKind.COMMENT)))),
        )

    private data class DocCacheKey(val node: NodeId, val view: CodeView)

    /**
     * Thin adapter from the engine's [ApkResources] to the pure [ResourceProvider] the [ResourceSurface]
     * consumes. [hasManifestEntry] forwards the engine's present-but-maybe-undecodable signal so the tree
     * shows a manifest node whenever the entry exists (rule 4), marking it undecodable only when
     * [decodeManifest] returns `null`. Decodes are memoized (manifest via `lazy`, each `res/…xml` in
     * [xmlCache]) so the folder-expand marker probe (`markResourceChildren`) and the later document open
     * share one decode — no double work, and the engine's own dedup keeps its per-file diagnostics from
     * re-appending. Only the folders a user actually expands are decoded and cached, so [xmlCache] stays
     * bounded to what was viewed. Constructed and used only under the client's [lock] (single-threaded
     * contract), so the plain [HashMap] cache needs no synchronization.
     */
    private class ApkResourcesProvider(private val res: ApkResources) : ResourceProvider {
        private val manifestText: String? by lazy { res.decodeManifest() }
        private val xmlCache = HashMap<String, String?>()

        override val hasManifestEntry: Boolean get() = res.hasManifestEntry
        override fun decodeManifest(): String? = manifestText
        override val xmlResourcePaths: List<String> get() = res.xmlResourcePaths
        override fun decodeXml(path: String): String? =
            if (xmlCache.containsKey(path)) xmlCache[path] else res.decodeXml(path).also { xmlCache[path] = it }

        // Binary res/ seam: the leaf names come from the engine's cheap central-directory listing, and the
        // selected blob's bytes are inflated on demand by the engine (one entry at a time). Deliberately NOT
        // cached here — caching inflated image bytes would defeat the whole point of the on-demand design
        // (keeping the wasm heap small); the workbench opens a single image/hex node at a time.
        override val binaryResourcePaths: List<String> get() = res.binaryResourcePaths
        override fun rawResource(path: String): ByteArray? = res.rawResource(path)

        override val tableTypes: List<ResTableType> by lazy {
            val table = res.table ?: return@lazy emptyList()
            table.types.map { ResTableType(it, table.entriesOfType(it).size) }
        }

        override fun tableEntries(type: String): List<ResTableEntry> {
            val table = res.table ?: return emptyList()
            return table.entriesOfType(type).map { e ->
                ResTableEntry(reference = e.reference, config = e.config, value = e.value, hexId = e.hexId)
            }
        }

        override val diagnostics: List<String> get() = res.diagnostics
    }

    /**
     * Immutable snapshot of the loaded input: the class-tree ([roots]/[children]), the flat
     * [classNames] (for search), and a [classIndex] mapping a **canonicalized** name (nested `$`
     * folded to `.`) back to the actual loaded full name — so a reference token that arrives with
     * either separator resolves to the real class node.
     */
    private class LoadedModel private constructor(
        val roots: List<TreeNode>,
        val children: Map<NodeId, List<TreeNode>>,
        val classNames: List<String>,
        val classIndex: Map<String, String>,
        private val badgeByFqn: Map<String, ClassNodeBadge>,
    ) {
        constructor() : this(emptyList(), emptyMap(), emptyList(), emptyMap(), emptyMap())

        /** The badge kind for a class row, falling back to the generic [NodeKind.CLASS] for an unknown fqn. */
        fun kindOf(fqn: String): NodeKind = badgeByFqn[fqn]?.kind ?: NodeKind.CLASS

        /** The access-visibility overlay for a class row, or `null` (no overlay) for an unknown fqn. */
        fun visibilityOf(fqn: String): Visibility? = badgeByFqn[fqn]?.visibility

        companion object {
            /**
             * Build the class tree. [labelOf] supplies each class row's shown label (rename-aware; the
             * `cls:` id stays the binary name), and [badgeOf] its kind + access visibility, both derived
             * once per (re)build from the model (no decompilation) so the whole tree — roots, package
             * children, the scan index and search results — labels/badges consistently.
             */
            fun build(
                names: List<String>,
                labelOf: (String) -> String,
                badgeOf: (String) -> ClassNodeBadge,
            ): LoadedModel {
                // childrenByPackage[""] holds the roots (top-level packages + default-package classes).
                val childrenByPackage = HashMap<String, MutableList<TreeNode>>()
                val allPackages = HashSet<String>()
                val badgeByFqn = HashMap<String, ClassNodeBadge>(names.size)

                for (fqn in names) {
                    val badge = badgeOf(fqn)
                    badgeByFqn[fqn] = badge
                    val pkg = fqn.substringBeforeLast('.', missingDelimiterValue = "")
                    if (pkg.isNotEmpty()) {
                        val parts = pkg.split('.')
                        for (depth in 1..parts.size) allPackages += parts.subList(0, depth).joinToString(".")
                    }
                    childrenByPackage.getOrPut(pkg) { mutableListOf() } += TreeNode(
                        id = NodeId("cls:$fqn"),
                        label = labelOf(fqn),
                        // Distinct badge per class kind (interface/enum/annotation), generic CLASS otherwise.
                        kind = badge.kind,
                        // Expands to the class's declared members (childNodes(cls:…)).
                        hasChildren = true,
                        visibility = badge.visibility,
                    )
                }
                for (pkg in allPackages) {
                    val parent = pkg.substringBeforeLast('.', missingDelimiterValue = "")
                    childrenByPackage.getOrPut(parent) { mutableListOf() } += TreeNode(
                        id = NodeId("pkg:$pkg"),
                        label = pkg.substringAfterLast('.'),
                        kind = NodeKind.PACKAGE,
                        hasChildren = true,
                    )
                }

                val children = childrenByPackage
                    .filterKeys { it.isNotEmpty() }
                    .mapKeys { (pkg, _) -> NodeId("pkg:$pkg") }
                    .mapValues { (_, kids) -> kids.sortedWith(nodeOrder) }
                val roots = childrenByPackage[""].orEmpty().sortedWith(nodeOrder)
                val classIndex = names.associateBy { canonicalName(it) }
                return LoadedModel(roots, children, names, classIndex, badgeByFqn)
            }

            // Packages before classes; each group alphabetical — a conventional class-tree ordering.
            private val nodeOrder: Comparator<TreeNode> =
                compareBy<TreeNode>({ if (it.kind == NodeKind.PACKAGE) 0 else 1 }, { it.label })
        }
    }

    private companion object {
        /**
         * Fold a class name to a canonical key by treating the nested-class separator `$` as a package
         * dot. Definitions use `IrClass.fullName` while type references use `IrType.Object.className`,
         * and the two can differ on the nested separator; canonicalizing both sides makes nested-class
         * navigation resolve regardless. (Collisions require a package and an outer class to share a
         * path — vanishingly unlikely, and the engine's own ref contract already assumes dotted names.)
         */
        fun canonicalName(name: String): String = name.replace('$', '.')
    }
}

// ── Find-usages resolution + labels (pure, unit-tested) ──────────────────────────

/** The ref kinds [Decompiler.findUsages] indexes; the click-to-def resolution only offers these as targets. */
private val INDEXED_REF_KINDS = setOf(RefKind.CLASS, RefKind.METHOD, RefKind.FIELD)

/**
 * The engine [CodeNodeRef] referenced (or defined) by [token] on 1-based [line] of [code], read from
 * [metadata] — the exact key [Decompiler.findUsages] inverts on. Scans the annotations that fall on that
 * line and returns the first Reference/Definition whose emitted text matches [token] (as a whole
 * identifier) and whose kind is find-usages-indexable, preferring one that also matches [kindHint] so a
 * name used as both a field and a method on one line resolves to the clicked one.
 *
 * Reading the ref out of the metadata (rather than rebuilding it from the token's `cls:` NodeId, which only
 * carries the OWNER class) is what keeps a method/field query precise — a specific overload, not its class.
 * Pure + fault-tolerant (rule 4): returns null when nothing on the line matches. `internal` for direct testing.
 */
internal fun referenceOnLine(
    code: String,
    metadata: CodeMetadata,
    line: Int,
    token: String,
    kindHint: TokenKind?,
): CodeNodeRef? {
    if (token.isEmpty()) return null
    val start = lineStartOffset(code, line)
    if (start < 0) return null
    val end = lineStartOffset(code, line + 1).let { if (it < 0) code.length else it }
    var fallback: CodeNodeRef? = null
    // asMap() iterates in ascending offset order, so we can stop once we pass the line's end.
    for ((offset, ann) in metadata.asMap()) {
        if (offset < start) continue
        if (offset >= end) break
        val ref = (ann as? ReferenceAnnotation)?.ref ?: (ann as? DefinitionAnnotation)?.ref ?: continue
        if (ref.refKind !in INDEXED_REF_KINDS) continue
        if (!code.regionMatches(offset, token, 0, token.length)) continue
        // Reject a longer identifier that merely starts with `token` (e.g. `foo` vs `fooBar`).
        val after = offset + token.length
        if (after < code.length && isIdentifierChar(code[after])) continue
        if (kindHint == null || matchesTokenKind(ref.refKind, kindHint)) return ref
        if (fallback == null) fallback = ref
    }
    return fallback
}

/** Character offset where 1-based [line] begins in [code], or -1 when the line is out of range. */
private fun lineStartOffset(code: String, line: Int): Int {
    if (line < 1) return -1
    if (line == 1) return 0
    var seen = 1
    for (i in code.indices) {
        if (code[i] == '\n') {
            seen++
            if (seen == line) return i + 1
        }
    }
    return -1
}

private fun isIdentifierChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

/** Whether an engine [refKind] agrees with the clicked token's [TokenKind] (the resolution's tie-breaker). */
private fun matchesTokenKind(refKind: RefKind, kind: TokenKind): Boolean = when (kind) {
    TokenKind.TYPE -> refKind == RefKind.CLASS
    TokenKind.METHOD -> refKind == RefKind.METHOD
    TokenKind.FIELD -> refKind == RefKind.FIELD
    else -> true
}

/** UI badge kind for an engine [RefKind] (only the indexed kinds reach here; others fall back to CLASS). */
internal fun nodeKindOf(refKind: RefKind): NodeKind = when (refKind) {
    RefKind.METHOD -> NodeKind.METHOD
    RefKind.FIELD -> NodeKind.FIELD
    else -> NodeKind.CLASS
}

/** The panel-header label for a resolved query symbol: `Simple` / `name(Args)` / `field`. */
internal fun usageSymbolLabel(ref: CodeNodeRef): String = when (ref) {
    is ClassNodeRef -> ref.fullName.substringAfterLast('.').substringAfterLast('$')
    is MethodNodeRef -> "${ref.name}(${ref.argTypeDescriptors.joinToString(", ") { simpleTypeName(it) }})"
    is FieldNodeRef -> ref.name
    else -> ref.toString()
}

/** Row label for a referring site's enclosing member (a method body), or null at class scope. */
internal fun enclosingMemberLabel(ref: CodeNodeRef?): String? =
    (ref as? MethodNodeRef)?.let { "${it.name}(${it.argTypeDescriptors.joinToString(", ") { d -> simpleTypeName(d) }})" }

/**
 * Shorten one rendered `IrType.toString()` argument (`int`, `java.lang.String`, `java.lang.String[]`,
 * `Map<java.lang.String, int>`) to its simple form for a label — package-stripped head, array/generic
 * suffix kept. Best-effort (a label, not a key): the generic arguments are left as-is.
 */
internal fun simpleTypeName(raw: String): String {
    val arraySuffix = raw.takeLastWhile { it == '[' || it == ']' }
    val core = raw.dropLast(arraySuffix.length)
    val head = core.substringBefore('<')
    val generics = core.substring(head.length)
    return head.substringAfterLast('.').substringAfterLast('$') + generics + arraySuffix
}

// ── Rename result projection (pure, unit-tested) ─────────────────────────────────

/**
 * Project the engine's `RenameResult` to the engine-free [RenameOutcome] the workbench consumes. The one
 * success case carries the applied name; the three rejections ([RenameResult.InvalidName],
 * [RenameResult.Collision], [RenameResult.UnrenamableTarget]) all fold to [RenameOutcome.Rejected], each
 * surfacing the engine's own ready-to-show reason/conflict text verbatim — so the dialog reports exactly
 * why a rename was refused without the seam leaking a codegen type. Pure; `internal` for direct testing.
 */
internal fun RenameResult.toRenameOutcome(): RenameOutcome = when (this) {
    is RenameResult.Applied -> RenameOutcome.Applied(name)
    is RenameResult.InvalidName -> RenameOutcome.Rejected(reason)
    is RenameResult.Collision -> RenameOutcome.Rejected(conflict)
    is RenameResult.UnrenamableTarget -> RenameOutcome.Rejected(reason)
}
