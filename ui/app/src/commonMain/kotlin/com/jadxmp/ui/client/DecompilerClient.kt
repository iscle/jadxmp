package com.jadxmp.ui.client

import kotlinx.coroutines.flow.StateFlow

/**
 * The single seam between `ui:app` and the decompiler engine.
 *
 * `core:api` (not yet created) will provide the production implementation, adapting its
 * `Decompiler`/`DecompilerScheduler` to this interface. Until then the shell renders against
 * [StubDecompilerClient]. Keeping this contract in `ui:app` means the UI can be built, previewed,
 * and tested with zero dependency on the engine — the module boundary the architecture mandates
 * (ui depends on the engine only through a facade).
 *
 * Everything that can block or take time is `suspend` and therefore cancelable; state that the UI
 * observes is exposed as [StateFlow]. Nothing here assumes a thread — safe on single-threaded wasm.
 */
interface DecompilerClient {
    /** Observable session lifecycle (empty → loading → ready/failed). */
    val session: StateFlow<SessionState>

    /** Open an input container. Updates [session] as it progresses. */
    suspend fun open(request: OpenRequest)

    /** Top-level rows for a tree (root packages, or resource roots). */
    suspend fun rootNodes(tree: TreeKind): List<TreeNode>

    /** Children of a node, loaded lazily when a row is expanded. */
    suspend fun childNodes(parent: NodeId): List<TreeNode>

    /**
     * Every class (type) node in the project, for whole-program scans such as code-content search.
     * Names only — cheap; no decompilation happens here. The scan drives [code] class-by-class,
     * reusing its cache. Ordered (stable) so a scan's progress is deterministic.
     */
    suspend fun classNodes(): List<TreeNode>

    /** Which source views a node can be shown in (e.g. a class offers Java/Kotlin/Smali). */
    fun availableViews(node: NodeId): List<CodeView>

    /** Rendered source for a node in a given view, with per-token metadata for highlighting/nav. */
    suspend fun code(node: NodeId, view: CodeView): CodeDocument

    /** Run a search across the selected scopes. */
    suspend fun search(query: SearchQuery): SearchResults

    /**
     * Resolve a member tree/search node (a `mbr:` id, produced by this client's own [childNodes]) to
     * the class tab to open and the source line of its definition. Returns null only when the id is not
     * a member id this client recognizes; a recognized member whose definition offset can't be resolved
     * yet (a static initializer, or a not-yet-annotated def) yields a [MemberLocation] with a null line
     * — "open the class, don't scroll". Cheap by contract (no full re-scan of the program), but may
     * decompile the one owning class to read its metadata.
     *
     * The default is a no-op (null): a client that does not surface members need not implement it.
     */
    suspend fun memberLocation(memberNodeId: NodeId): MemberLocation? = null

    /**
     * Export the whole loaded project as [ExportFile]s (`path → bytes`) for the given source [view]
     * (defaulting to Java), for the "Export decompiled sources" action (P0#7). Every class becomes one
     * `<package>/<Simple>.<ext>` file; resources (manifest + decoded `res/…xml`) are included under
     * `resources/`. `suspend` and cancelable — it decompiles the whole program.
     *
     * The default returns an empty list: a client with no engine backing it (a preview stub) simply
     * offers nothing to export.
     */
    suspend fun exportProject(view: CodeView? = null): List<ExportFile> = emptyList()
}
