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

    /**
     * User-driven **rename** of the symbol at a code-area position — jadx-gui's Rename, driven from the code
     * viewer's right-click. [target] carries where the user invoked it (the open class node + shown view +
     * clicked line/token); the client resolves that position to the engine's exact reference key (the SAME
     * `CodeNodeRef` space go-to-definition / find-usages use) and applies the rename at the symbol's
     * definition and every use (see `com.jadxmp.api.Decompiler.rename`).
     *
     * The engine **validates and collision-checks** the request against the loaded model, so a rename is
     * either [RenameOutcome.Applied] (it took effect; the render + usage caches are invalidated so a
     * re-fetch shows the new name) or [RenameOutcome.Rejected] with a UI-ready reason (an illegal/reserved
     * name, a within-scope collision, or a target that can't be renamed in this version) — jadxmp never
     * silently clobbers a name. A token that does not resolve to a renamable symbol (a package/local/keyword,
     * an unresolved position) is likewise a [RenameOutcome.Rejected], never a throw (rule 4).
     *
     * `suspend` and cancelable because a successful rename invalidates the engine's caches, so the affected
     * class(es) re-decompile on the next fetch. The default rejects (a client with no engine backing cannot
     * rename); the production client maps the engine result, the stub returns a benign outcome.
     */
    suspend fun rename(target: RenameQuery, newName: String): RenameOutcome =
        RenameOutcome.Rejected("Renaming is not available for this project.")

    /**
     * Drop every user rename applied so far, reverting names to the engine's automatic naming on the next
     * fetch (see `com.jadxmp.api.Decompiler.clearRenames`). The default is a no-op — a client with no engine
     * backing has nothing to clear; the production client also invalidates its own render/tree caches so a
     * subsequent fetch reflects the revert. Backs a future "Clear renames" affordance.
     */
    suspend fun clearRenames() {}
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

/**
 * Where the user invoked "Rename" in the code area — the same clicked-position shape as [UsageQuery], since
 * a rename resolves its target through the exact click-to-definition mechanism find-usages uses. Deliberately
 * UI-typed (no engine `CodeNodeRef`) so the [DecompilerClient] seam stays engine-free; the impl does the ref
 * resolution internally.
 *
 * @property classNode the open class (`cls:`) node whose decompiled source was right-clicked.
 * @property view the shown source view — the engine annotates positions per format, so the query must match it.
 * @property line the 1-based clicked line within that document.
 * @property token the clicked identifier token's text (the symbol name at the caret).
 * @property tokenKind the token's [TokenKind], disambiguating a name used as both, say, a field and a method.
 */
@Immutable
data class RenameQuery(
    val classNode: NodeId,
    val view: CodeView,
    val line: Int,
    val token: String,
    val tokenKind: TokenKind,
)

/**
 * The outcome of a [DecompilerClient.rename] — the UI-facing projection of the engine's `RenameResult`
 * (kept engine-type-free like the rest of the seam; the production client maps one to the other). A rename
 * either takes effect ([Applied], carrying the [Applied.name] now emitted everywhere) or is **rejected
 * without changing anything** ([Rejected], carrying a short human-readable [Rejected.reason] a dialog can
 * surface directly). The three engine rejections (illegal/reserved name, within-scope collision, unrenamable
 * target) and the "token didn't resolve to a symbol" case all fold to [Rejected] — the dialog treats them
 * identically: keep open, show the reason.
 */
@Immutable
sealed interface RenameOutcome {
    /** The rename took effect; [name] is the identifier now emitted at the definition and every use. */
    data class Applied(val name: String) : RenameOutcome

    /** The rename was rejected and nothing changed; [reason] is a ready-to-show explanation. */
    data class Rejected(val reason: String) : RenameOutcome
}
