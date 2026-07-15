package com.jadxmp.ui.client

import androidx.compose.runtime.Immutable
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

    /**
     * Raw (already-inflated) bytes of a resource node — an image or an opaque binary the workbench routes
     * to the image / hex viewer instead of the text code viewer. Returns `null` for a non-resource node,
     * a resource with no reachable bytes, or a client with no engine backing. The default is `null`, so a
     * client that surfaces only text resources need not implement it (the viewers then degrade to the
     * existing text/placeholder path — rule 4).
     */
    suspend fun resourceBytes(node: NodeId): ByteArray? = null

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

    /**
     * "Find usages" of the symbol at a code-area position — jadx-gui's Find Usages, driven from the code
     * viewer's right-click. [query] carries where the user invoked it (the open class node + shown view +
     * clicked line/token); the client resolves that position to the engine's exact reference key (the same
     * `CodeNodeRef` space go-to-definition uses) and inverts the whole-program reference metadata into the
     * list of referring sites (see `com.jadxmp.api.Decompiler.findUsages`).
     *
     * `suspend` and cancelable because the **first** query for a format decompiles the entire app to build
     * (and cache) the inverse index; later queries are fast lookups. Returns `null` when the token does not
     * resolve to an indexable symbol (a package/local/keyword, or an unresolved position) — never a throw
     * (rule 4). A resolved symbol that nothing references returns a [UsageResults] with an empty [UsageResults.sites].
     *
     * The default is `null`: a client with no engine backing offers no usages.
     */
    suspend fun findUsages(query: UsageQuery): UsageResults? = null
}

/**
 * Where the user invoked "Find usages" in the code area — enough for the client to resolve the clicked
 * symbol precisely from the open class's metadata. Deliberately UI-typed (no engine `CodeNodeRef`), so the
 * seam stays engine-free like the rest of [DecompilerClient]; the impl does the ref resolution internally.
 *
 * @property classNode the open class (`cls:`) node whose decompiled source was right-clicked.
 * @property view the shown source view — the engine indexes positions per format, so the query must match it.
 * @property line the 1-based clicked line within that document.
 * @property token the clicked identifier token's text (the symbol name at the caret).
 * @property tokenKind the token's [TokenKind], a hint that disambiguates a name used as both, say, a field
 *   and a method on the same line.
 */
@Immutable
data class UsageQuery(
    val classNode: NodeId,
    val view: CodeView,
    val line: Int,
    val token: String,
    val tokenKind: TokenKind,
)

/**
 * A resolved find-usages query, projected for [com.jadxmp.ui.workbench.UsagesPanel]: the readable [symbol]
 * label + its [kind] for the panel header, and every referring [sites] row. An empty [sites] means the
 * symbol resolved but nothing references it ("No usages found") — distinct from a `null` [UsageResults],
 * which means the clicked token did not resolve to a symbol at all.
 */
@Immutable
data class UsageResults(
    val symbol: String,
    val kind: NodeKind,
    val sites: List<UsageSiteRow>,
)

/**
 * One referring site, projected from the engine's `UsageSite` for the panel: the class tab to open
 * ([classNode]) with its short [classLabel], the enclosing member signature ([memberLabel], `null` at class
 * scope — an `extends`/field-type use), and the 1-based [line] to center on jump.
 */
@Immutable
data class UsageSiteRow(
    val classNode: NodeId,
    val classLabel: String,
    val memberLabel: String?,
    val line: Int,
    val kind: NodeKind,
)
