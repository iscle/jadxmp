package com.jadxmp.api

import com.jadxmp.api.internal.CodegenBridge
import com.jadxmp.api.internal.RenderabilityGuard
import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.CodeInfo
import com.jadxmp.codegen.CodeNodeRef
import com.jadxmp.codegen.CommentMap
import com.jadxmp.codegen.java.JavaCodeGenerator
import com.jadxmp.codegen.kotlin.KotlinCodeGenerator
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.attr.AttrKey
import com.jadxmp.ir.attr.AttrNode
import com.jadxmp.ir.attr.DecompileError
import com.jadxmp.ir.attr.IrAttrs
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.input.ClassData
import com.jadxmp.input.CodeLoader
import com.jadxmp.input.ListCodeLoader
import com.jadxmp.pipeline.AnalysisPipeline
import com.jadxmp.pipeline.BuildCfgPass
import com.jadxmp.pipeline.model.ModelBuilder
import com.jadxmp.pipeline.pass.CancellationCheck
import com.jadxmp.pipeline.pass.PassContext
import com.jadxmp.pipeline.pass.PassRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield

/**
 * The public facade (ARCHITECTURE §2) and the only engine type UI/tools depend on. Wires the landed
 * stages into an end-to-end decompile:
 *
 * ```
 * .dex bytes ──DexInput──▶ input model ──ModelBuilder──▶ IrRoot
 *            ──AnalysisPipeline (CFG/SSA/types)──▶ typed SSA
 *            ──CodegenBridge (out-of-SSA)──▶ named locals
 *            ──JavaCodeGenerator──▶ Java per class
 * ```
 *
 * Decompilation is **lazy and per-class**: [load] only builds signatures/structure (method bodies stay
 * un-decoded); a class is fully analyzed and rendered on demand by [decompileClass], cached thereafter.
 * [decompileAll] walks every class sequentially; [decompileAllParallel] does the same over the
 * coroutine [DecompilerScheduler] with bounded parallelism and cancellation.
 *
 * One [Decompiler] instance corresponds to one loaded input. Per-class analysis runs at most once per
 * class regardless of which entry point drives it (guarded by [LOWERED]); codegen is pure and re-runs
 * freely. Not thread-safe for the *cached* [decompileClass]/[decompileAll] path — drive concurrency
 * only through [decompileAllParallel], which touches distinct class nodes per coroutine and no shared
 * cache.
 *
 * jadx: `JadxDecompiler`.
 */
class Decompiler(val args: DecompilerArgs = DecompilerArgs()) {

    private var root: IrRoot? = null
    // The EFFECTIVE alias map codegen reads at every definition and use site: the deobfuscation auto-map
    // ([deobfOverrides]) merged with the user rename store ([userRenames]), user winning. Rebuilt by
    // [rebuildAliasMap] on load and on any rename/clear; READ-ONLY between rebuilds, so the parallel
    // per-class render path reads it race-free (mirrors the "pure, built-once" naming design in codegen).
    // The safety invariant: with deobfuscation off AND no user renames it is [AliasMap.EMPTY] BY IDENTITY
    // (AliasMap.of(emptyMap()) === EMPTY), so every codegen naming seam takes its pre-feature fast path and
    // output is byte-for-byte identical to a build without this feature — which is why default-args runs
    // (the differential oracle) are completely unaffected by either populator.
    private var aliasMap: AliasMap = AliasMap.EMPTY
    // The deobfuscation auto-map's raw overrides (empty unless [DecompilerArgs.deobfuscation]). Kept as the
    // raw map — not just the built [AliasMap] — so [rebuildAliasMap] can merge it with the user renames.
    // Built ONCE per load; the user store is layered on top at merge time (user precedence).
    private var deobfOverrides: Map<CodeNodeRef, String> = emptyMap()
    // Session-local user renames (this loaded input only; no persistence this phase). Mutated only on the
    // single-threaded cached path, alongside the [cache] it shares invalidation with. Validated +
    // collision-checked against the model before an entry is accepted (see [UserRenameStore]).
    private val userRenames = UserRenameStore()
    // The effective user-comment map codegen reads at each definition site to inject a `//` note before it.
    // Rebuilt by [rebuildCommentMap] from [userComments] on load and on any comment edit; READ-ONLY between
    // rebuilds (like [aliasMap]), so the parallel per-class render path reads it race-free. With no user
    // comments it is [CommentMap.EMPTY] BY IDENTITY (CommentMap.of(emptyMap()) === EMPTY), so the comment
    // seam emits nothing and output is byte-for-byte identical to a build without this feature — which is
    // why default-args runs (the differential oracle) are completely unaffected.
    private var commentMap: CommentMap = CommentMap.EMPTY
    // Session-local user comments (this loaded input only; no persistence this phase). Free text — no
    // validation beyond trimming (codegen sanitizes it to always-valid source). Mutated only on the
    // single-threaded cached path, alongside the [cache] it shares invalidation with (see [UserCommentStore]).
    private val userComments = UserCommentStore()
    // The loaded input model, retained so [smali] can disassemble a class's raw bytecode straight from
    // the parser output — no pipeline decompile. Keyed by the same binary (dotted) name as [classNames]
    // (the parser's descriptor `Lp/C;` folded to `p.C`), so a UI/tool selection resolves without a scan.
    private var inputClasses: Map<String, ClassData> = emptyMap()
    // Keyed on (top-level name, output format): the SAME loaded class can be rendered as both Java and
    // Kotlin without reloading, and the two renders coexist in the cache instead of clobbering each other
    // (the UI's format toggle relies on this). Lowering is format-independent and runs at most once per
    // class (guarded by LOWERED); only the pure leaf codegen re-runs per format.
    private val cache = LinkedHashMap<ClassCacheKey, DecompiledClass>()
    // Lazily-built find-usages inverse index, keyed by output format: the referencing offsets/lines a
    // [UsageSite] reports are format-specific (Java vs Kotlin render differently), so — like [cache] —
    // each format gets its own index. Built on the first [findUsages] for a format and reused; cleared by
    // [load]. Never rebuilt implicitly, so repeated queries are O(hits) map lookups.
    private val usageIndexCache = LinkedHashMap<OutputFormat, UsageIndex>()
    private val runner: PassRunner = buildRunner()
    private val loadDiagnostics = ArrayList<String>()
    private var resourcesInternal: ApkResources? = null

    /** Diagnostics recorded while loading (e.g. a malformed/hostile container that could not be read). */
    val diagnostics: List<String> get() = loadDiagnostics.toList()

    /**
     * The decoded resources of the loaded container (`AndroidManifest.xml`, the `resources.arsc` table,
     * binary XML under `res/`), or `null` when the input is not a resource-bearing APK. Populated by [load];
     * resource decoding is fault-isolated (a malformed table/manifest degrades to `null`/diagnostics,
     * never a crash) and independent of class decompilation. Consumed by the UI resources tree.
     */
    val resources: ApkResources? get() = resourcesInternal

    /**
     * Load one input container (a `.dex`, DEX v41 container, or `.apk`/`.jar`/`.zip`) named [name].
     * Replaces any previously loaded input. Returns the number of classes discovered. Only structure is
     * built here; bodies decode lazily on first [decompileClass].
     *
     * The load stage is **fault-isolated** like the per-method stage: a malformed or hostile container
     * (truncated dex, a zip with a bad timestamp/checksum, a zip bomb) is caught, recorded in
     * [diagnostics], and degraded to zero classes — never an uncaught crash (CONVENTIONS "Errors").
     */
    fun load(name: String, bytes: ByteArray): Int {
        loadDiagnostics.clear()
        cache.clear()
        usageIndexCache.clear()
        resourcesInternal = null
        inputClasses = emptyMap()
        // A fresh input starts with no renames and no auto-map; [rebuildAliasMap] below sets the effective
        // map (EMPTY by identity when deobfuscation is off, keeping the load path byte-identical).
        userRenames.clear()
        deobfOverrides = emptyMap()
        aliasMap = AliasMap.EMPTY
        // Likewise a fresh input starts with no user comments; the map stays EMPTY by identity (byte-identical).
        userComments.clear()
        commentMap = CommentMap.EMPTY
        val loader = try {
            args.registry.load(name, bytes) ?: ListCodeLoader(emptyList())
        } catch (e: Exception) {
            loadDiagnostics.add("failed to load '$name': ${e.message ?: e.toString()}")
            ListCodeLoader(emptyList())
        }
        inputClasses = indexInput(loader)
        // Resources decode independently of classes and are fault-isolated: a hostile/malformed container
        // that trips a zip-guard or has no readable resources degrades to null, never failing the load.
        resourcesInternal = try {
            ApkResources.decode(bytes)
        } catch (e: Exception) {
            loadDiagnostics.add("failed to read resources from '$name': ${e.message ?: e.toString()}")
            null
        }
        val built = ModelBuilder.build(loader)
        root = built
        // Build the deobfuscation auto-map once for this model — empty unless opted in via
        // [DecompilerArgs.deobfuscation]. The effective [aliasMap] is then derived from it plus the (empty)
        // user store; with deobfuscation off this yields [AliasMap.EMPTY] by identity (byte-identical load).
        deobfOverrides = if (args.deobfuscation) Deobfuscator.buildOverrides(built) else emptyMap()
        rebuildAliasMap()
        return built.classes.size
    }

    /**
     * Record a **user rename** of [target] to [newName] and apply it everywhere on the next render, or
     * reject it without changing anything (see [RenameResult]). [target] is the one symbol identity the
     * whole engine speaks — a [MemberInfo.key] from the navigation tree, a find-usages
     * ([findUsages]) target, or `ClassNodeRef(binaryName)` for a class — so a UI can go
     * "find usages → rename" coherently: the rename key is the *binary* identity the metadata records, which
     * is **invariant under renaming** (an alias is a codegen spelling only, never a change to the model), so
     * the same ref keeps resolving before and after.
     *
     * The rename is **validated and collision-checked against the loaded model** first: the new name must be
     * a legal, non-reserved Java identifier (codegen emits a user override verbatim), and must not clash with
     * a sibling in the same scope. A user rename **takes precedence** over a deobfuscation alias on the same
     * symbol. On success this merges the override into the effective alias map and invalidates the render
     * caches so the next [decompileClass]/[decompileAll]/[findUsages] reflects it.
     *
     * **Cost (honest):** applying a rename is O(1) plus a rebuild of the small effective map; invalidation
     * clears the whole per-class render cache and the find-usages index. That is deliberately coarse
     * (simplest-correct): re-rendering is **pure codegen only** — the destructive analysis/lowering is
     * guarded per class ([LOWERED] persists on the IR), so it is never repeated — and it is **lazy**, so a
     * class is re-rendered only when next requested. Refining invalidation to just the declaring class plus
     * its referrers (via the find-usages index) is a documented follow-up.
     *
     * Not thread-safe (like [decompileClass]): drive it only on the cached, sequential path, never
     * concurrently with [decompileAllParallel]. Returns [RenameResult.UnrenamableTarget] when nothing is
     * loaded.
     */
    fun rename(target: CodeNodeRef, newName: String): RenameResult {
        val model = root ?: return RenameResult.UnrenamableTarget(target, "no input is loaded")
        val result = userRenames.tryRename(model, deobfOverrides, target, newName)
        if (result is RenameResult.Applied) {
            rebuildAliasMap()
            invalidateRenderCaches()
        }
        return result
    }

    /**
     * Drop every user rename, reverting names to the deobfuscation auto-map (or the raw names when
     * deobfuscation is off) on the next render. A no-op — with no cache churn — when there are no user
     * renames. Not thread-safe (see [rename]).
     */
    fun clearRenames() {
        if (userRenames.isEmpty) return
        userRenames.clear()
        rebuildAliasMap()
        invalidateRenderCaches()
    }

    /**
     * The user renames currently in effect, as an immutable `CodeNodeRef → chosen name` snapshot in
     * application order (excludes deobfuscation aliases — those are automatic, not user edits). Backs a
     * future rename-list UI and the deferred `.jadx` persistence; independent of later [rename]/[clearRenames].
     */
    val renames: Map<CodeNodeRef, String> get() = userRenames.snapshot()

    /**
     * Attach a **user comment** [text] to [target], rendered as `//` line(s) immediately before that
     * symbol's definition on the next render — or, when [text] is blank, remove any existing comment (one
     * call serves a UI's edit and its clear). [target] is the one symbol identity the whole engine speaks —
     * a [MemberInfo.key] from the navigation tree, a find-usages ([findUsages]) target, or
     * `ClassNodeRef(binaryName)` for a class — so a UI can comment the symbol under the cursor exactly as it
     * would rename it. The comment attaches to the *binary* identity the metadata records, which is invariant
     * under renaming, so a commented symbol stays commented after it is renamed.
     *
     * Unlike [rename], a comment is **free text and never rejected**: it needs no legal-identifier or
     * collision check because codegen renders it as line comment(s) and sanitizes it so it can never break
     * the source (a multi-line note becomes multiple `//` lines; anything that could escape a `//` comment is
     * defused). The only normalization is trimming. When the comment actually changes, this invalidates the
     * render caches so the next [decompileClass]/[decompileAll]/[findUsages] shows it (same coarse-but-correct
     * invalidation as [rename]); an unchanged set is a no-op with no cache churn.
     *
     * The comment is **decoration only** — it is not part of any symbol's definition range, so it does not
     * change [findUsages]/`nodeAt` results (it merely shifts later offsets, and the metadata is recomputed
     * per render). Comments are Kotlin-render-agnostic for now: the Kotlin backend does not yet inject them
     * (a documented follow-up, like Kotlin renaming). Not thread-safe (like [rename]): drive it only on the
     * cached, sequential path, never concurrently with [decompileAllParallel].
     */
    fun setComment(target: CodeNodeRef, text: String) {
        if (userComments.set(target, text)) {
            rebuildCommentMap()
            invalidateRenderCaches()
        }
    }

    /**
     * Remove any user comment on [target] (a no-op, with no cache churn, when there is none). Equivalent to
     * [setComment] with blank text. Not thread-safe (see [setComment]).
     */
    fun removeComment(target: CodeNodeRef) {
        if (userComments.remove(target)) {
            rebuildCommentMap()
            invalidateRenderCaches()
        }
    }

    /**
     * The user comments currently in effect, as an immutable `CodeNodeRef → comment text` snapshot in
     * authoring order. Backs a future comment-list UI and the deferred `.jadx` persistence; independent of
     * later [setComment]/[removeComment]/[clearComments].
     */
    val comments: Map<CodeNodeRef, String> get() = userComments.snapshot()

    /**
     * Drop every user comment, reverting the next render to un-commented output. A no-op — with no cache
     * churn — when there are no user comments. Not thread-safe (see [setComment]).
     */
    fun clearComments() {
        if (userComments.isEmpty) return
        userComments.clear()
        rebuildCommentMap()
        invalidateRenderCaches()
    }

    /**
     * Fully-qualified names of the **top-level** loaded classes, in input order. A nested class is not
     * listed here — it is decompiled as part of its outer class's single output unit (one file per outer),
     * so the oracle and UI see exactly one entry per emitted `.java`.
     */
    val classNames: List<String> get() = root?.classes?.filter { it.outerClass == null }?.map { it.fullName } ?: emptyList()

    /**
     * The declared members (fields, methods/constructors/static-initializer, and directly-nested-class
     * references) of the class named by its **binary** [fullName] (as listed in [classNames]), read
     * straight from the loaded model — **no decompilation**, so it is cheap enough to build the whole
     * navigation tree and to back the Methods/Fields search scopes (ARCHITECTURE §9).
     *
     * Ordering is deterministic: fields, then methods, then nested classes, each in declaration order —
     * the same order the backend emits them. Synthetic/bridge members are filtered to match jadx's tree.
     * Each [MemberInfo.key] aligns with the member's `DefinitionAnnotation` in the decompiled
     * [ClassMetadata.code], so the UI can scroll to a member's definition after the class opens (see
     * [MemberInfo]).
     *
     * Fault-isolated (rule 4): an unknown/absent [fullName], or nothing loaded, returns an empty list
     * (never a crash); a member whose signature can't be rendered degrades to an honest label.
     */
    fun classMembers(fullName: String): List<MemberInfo> {
        val model = root ?: return emptyList()
        val cls = model.findClass(fullName) ?: return emptyList()
        return membersOf(cls)
    }

    /**
     * The kind (class/interface/enum/annotation) and source modifiers of the class named by its
     * **binary** [fullName], read straight from the loaded model — **no decompilation**, so it is cheap
     * enough to badge every row of the navigation tree (interfaces/enums/annotations/abstract classes get
     * distinct icons instead of a generic class glyph). jadx: the GUI's per-node `AccessInfo` icon pick.
     *
     * Fault-isolated (rule 4): an unknown/absent [fullName], or nothing loaded, returns `null` (never a
     * crash), and the caller falls back to the generic class badge.
     */
    fun classInfo(fullName: String): ClassInfo? {
        val model = root ?: return null
        val cls = model.findClass(fullName) ?: return null
        return classInfoOf(cls)
    }

    /**
     * Disassemble the class named by its **binary** [fullName] (as listed in [classNames]) to
     * baksmali-style **smali** text, read straight from the loaded input model — **no pipeline
     * decompile**. This is the engine's "show bytecode" view (ARCHITECTURE §6/§8: smali display is
     * generated from the input model), independent of and much cheaper than [decompileClass].
     *
     * Fault-isolated (rule 4): an unknown/absent [fullName], nothing loaded, or a disassembler fault
     * returns `null` — never a crash; the caller shows an honest placeholder. A single undecodable
     * method inside the class degrades to a comment without losing the rest of the class (the
     * disassembler is itself fault-isolated per method).
     */
    fun smali(fullName: String): String? {
        val cls = inputClasses[fullName] ?: return null
        return try {
            cls.disassemble()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Every site that **references** the symbol [target] — jadx-gui's "Find Usages". [target] is a
     * [CodeNodeRef], the one symbol identity the whole engine already speaks: pass a [MemberInfo.key] for a
     * method/field (straight from the navigation tree), or `com.jadxmp.codegen.ClassNodeRef(binaryName)`
     * for a class (a [classNames] entry). No parallel query scheme is introduced — this is the exact key
     * the codegen attaches to each reference, so the lookup is a direct inversion.
     *
     * **What it is:** the inverse of click-to-definition. The backend already annotates every emitted
     * reference with its target (`ReferenceAnnotation`); [findUsages] inverts (site → target) into
     * (target → [UsageSite]s) across all classes. Excludes the symbol's own declaration and local-variable
     * occurrences, so the result is uses only.
     *
     * **Cost / correctness trade-off (honest):** a class's references are only known once it is decompiled,
     * so a *complete* answer requires decompiling **every** class. The first [findUsages] for a given
     * [format] therefore forces a full decompile of the app (O(all classes)) to build the inverse index,
     * then caches it — every subsequent query, for any symbol, is an O(hits) map lookup. This is the
     * correct-but-eager choice (rule 2: never a partial/wrong answer); the one-time cost is the price of
     * completeness. It runs the **cached, sequential** [decompileClass] path, so it also warms that cache
     * (classes the UI later opens are already done) and is safe to call repeatedly — unlike
     * [decompileAllParallel], it never leaves a half-lowered instance. Memory is O(total references in the
     * app); the index is dropped on [load]. On the single-threaded wasm/browser UI a first call over a
     * large app blocks for the full decompile — a caller that must stay responsive should drive it off the
     * critical path (or pre-warm via [decompileAll]).
     *
     * **Fault isolation (rule 4):** a class that throws while decompiling is skipped, the rest are still
     * indexed; an unknown/unreferenced [target], or nothing loaded, returns an empty list — never a throw.
     * Results are deterministically ordered (by referring class name, then source position).
     *
     * [format] selects which rendering's positions to report (default the instance's [DecompilerArgs.outputFormat]);
     * each format is indexed independently, mirroring [decompileClass].
     */
    fun findUsages(target: CodeNodeRef, format: OutputFormat = args.outputFormat): List<UsageSite> {
        if (root == null) return emptyList()
        val index = usageIndexCache.getOrPut(format) { buildUsageIndexNow(format) }
        return index.query(target)
    }

    /**
     * Decompile a single class on demand and cache the result. Returns null if nothing is loaded or no
     * class matches [fullName]. [cancellation] lets a caller abort a long single-class analysis.
     *
     * [format] is a **per-call override** of [DecompilerArgs.outputFormat]: one loaded [Decompiler] can
     * render the same class as either [OutputFormat.JAVA] or [OutputFormat.KOTLIN] without reloading (the
     * UI's format toggle drives this). Both formats route through the identical lowered IR / out-of-SSA
     * bridge — only the leaf backend differs — so the [DecompiledClass]/[ClassMetadata] contract and the
     * honesty markers are shared. Each (class, format) pair is cached independently. Omitting [format]
     * keeps the instance default (JAVA unless [DecompilerArgs.outputFormat] says otherwise) — unchanged.
     */
    fun decompileClass(
        fullName: String,
        format: OutputFormat = args.outputFormat,
        cancellation: CancellationCheck = CancellationCheck.None,
    ): DecompiledClass? {
        val model = root ?: return null
        val found = model.findClass(fullName) ?: return null
        // A nested class comes through its outer: decompile (and cache under) the top-level ancestor, whose
        // single output unit already contains this class's body. Guarantees no nested class is rendered as
        // its own standalone file.
        val top = topLevelOf(found)
        val key = ClassCacheKey(top.fullName, format)
        cache[key]?.let { return it }
        return decompileNow(model, top, format, cancellation).also { cache[key] = it }
    }

    /**
     * Fold a class **type descriptor** (`Lp/C;`, `Lp/Outer$Inner;`) to the binary dotted name used as the
     * key of [classNames] and the model (`p.C`, `p.Outer$Inner`) — matching `ModelBuilder`'s naming so a
     * name obtained from the tree/facade resolves back to its input [ClassData]. The nested `$` is
     * preserved (the model keeps it too). A descriptor that is not an object type is returned unchanged.
     */
    private fun binaryName(descriptor: String): String =
        if (descriptor.length > 2 && descriptor[0] == 'L' && descriptor[descriptor.length - 1] == ';') {
            descriptor.substring(1, descriptor.length - 1).replace('/', '.')
        } else {
            descriptor
        }

    private fun topLevelOf(cls: IrClass): IrClass {
        var c = cls
        while (c.outerClass != null) c = c.outerClass!!
        return c
    }

    /**
     * Sequentially decompile every loaded class (blocking, no coroutines). This is the simple entry
     * point for non-coroutine callers such as the oracle harness.
     */
    fun decompileAll(): DecompilationResult {
        val model = root ?: return DecompilationResult(emptyList(), 0)
        val classes = model.classes.filter { it.outerClass == null }.map { decompileClass(it.fullName)!! }
        return DecompilationResult(classes, classes.sumOf { it.metadata.errorCount })
    }

    /**
     * Decompile every class over the coroutine [scheduler] with bounded parallelism and structured
     * cancellation. Bypasses the single-class cache (each coroutine renders a distinct class node), so
     * use this OR the cached path, not both, on one instance.
     *
     * **After a cancellation, discard this [Decompiler].** Cancellation can interrupt a class mid-
     * lowering, leaving its IR half-transformed (partial CFG/SSA, no `LOWERED` marker); re-running any
     * entry point over that class would re-apply destructive passes and corrupt it. Cancellation means
     * "throw this instance away and reload", not "resume".
     */
    suspend fun decompileAllParallel(
        scheduler: DecompilerScheduler = DecompilerScheduler(args.parallelism),
    ): DecompilationResult {
        val model = root ?: return DecompilationResult(emptyList(), 0)
        val topLevel = model.classes.filter { it.outerClass == null }
        val classes = scheduler.map(topLevel) { cls, check -> decompileNow(model, cls, args.outputFormat, check) }
        return DecompilationResult(classes, classes.sumOf { it.metadata.errorCount })
    }

    /**
     * Export the **whole loaded project** as a flat list of [ExportedFile] (`path → bytes`), ready for a
     * platform to write as a directory tree or a downloaded ZIP (via [SourceArchive.zip]) — jadx-gui's
     * "Save all"/"Export" (P0#7). Each top-level class is rendered to one `<package-as-dirs>/<Simple>.<ext>`
     * file in [format] (Java by default), reusing the cached per-class [decompileClass] path so a class the
     * UI already opened is not re-analyzed. When [includeResources] and the input carried resources, the
     * decoded `AndroidManifest.xml` and each `res/…xml` are emitted under `resources/`.
     *
     * Uses the **cached, sequential** path deliberately — not [decompileAllParallel] — so it is safe to
     * call repeatedly on a live instance and safe to cancel: cancelling at a [yield] boundary leaves every
     * class either fully cached or untouched (a single `decompileClass` is atomic), never the half-lowered
     * IR that discards the parallel path's instance. It `suspend`s and [yield]s between units so a long
     * export stays cooperative on the single wasm UI thread.
     *
     * **Fault isolation (rule 4):** the engine already renders a bad *method* to an in-body error marker
     * rather than throwing, so a class normally exports even when partly broken; on the rare hard failure
     * of a whole class this still emits a placeholder source file with the error as a comment, so no class
     * ever silently vanishes from the export. Binary assets (PNGs, raw files) are not retained by the
     * resource decoder and are therefore not included — a documented follow-on, not a silent drop.
     */
    suspend fun exportSources(
        format: OutputFormat = args.outputFormat,
        includeResources: Boolean = true,
    ): List<ExportedFile> {
        if (root == null) return emptyList()
        val ext = format.sourceExtension()
        val files = ArrayList<ExportedFile>()
        for (binaryName in classNames) {
            yield()
            files += try {
                val decompiled = decompileClass(binaryName, format)
                if (decompiled != null) {
                    ExportedFile(sourcePath(decompiled.fullName, ext), decompiled.code.encodeToByteArray())
                } else {
                    ExportedFile(sourcePath(binaryName, ext), placeholderSource(binaryName, "no output"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ExportedFile(sourcePath(binaryName, ext), placeholderSource(binaryName, e.message ?: e.toString()))
            }
        }
        if (includeResources) appendResourceFiles(files)
        return files
    }

    /** Append the decoded manifest + `res/…xml` of the loaded container under `resources/`, if any. */
    private suspend fun appendResourceFiles(files: MutableList<ExportedFile>) {
        val res = resourcesInternal ?: return
        res.decodeManifest()?.let { files += ExportedFile("resources/AndroidManifest.xml", it.encodeToByteArray()) }
        for (path in res.xmlResourcePaths) {
            yield()
            res.decodeXml(path)?.let { files += ExportedFile("resources/$path", it.encodeToByteArray()) }
        }
    }

    /** Relative output path for an emitted **source** name (`com.example.Foo` → `com/example/Foo.java`). */
    private fun sourcePath(sourceName: String, ext: String): String = sourceName.replace('.', '/') + "." + ext

    /** A minimal but honest placeholder file for a class that could not be rendered at all (rule 4). */
    private fun placeholderSource(binaryName: String, reason: String): ByteArray =
        // `//` is a line comment in both Java and Kotlin, so the placeholder is valid in either format.
        "// JADXMP: could not export '$binaryName': $reason\n".encodeToByteArray()

    // ---- internals ----------------------------------------------------------

    /**
     * Recompute the effective [aliasMap] = deobfuscation auto-map ⊕ user renames (user winning). When both
     * are empty this is [AliasMap.EMPTY] **by identity** (`AliasMap.of(emptyMap()) === EMPTY`), so codegen
     * stays on its byte-identical fast path; when only the deobf map is present the result equals the
     * pre-rename-feature `AliasMap.of(deobfOverrides)` exactly. `Map.plus` gives the user entries precedence
     * on a shared key, satisfying "a user rename overrides a deobfuscation alias on the same symbol".
     */
    private fun rebuildAliasMap() {
        aliasMap = AliasMap.of(if (userRenames.isEmpty) deobfOverrides else deobfOverrides + userRenames.overrides())
    }

    /**
     * Recompute the effective [commentMap] from the user comment store. With no user comments this is
     * [CommentMap.EMPTY] **by identity** (`CommentMap.of(emptyMap()) === EMPTY`), so the codegen comment seam
     * stays on its byte-identical (emit-nothing) fast path. Rebuilt on every comment edit, mirroring
     * [rebuildAliasMap].
     */
    private fun rebuildCommentMap() {
        commentMap = CommentMap.of(userComments.comments())
    }

    /**
     * Discard every cached render and the find-usages index after a rename/clear. Coarse but obviously
     * correct: no stale spelling (or stale reference offset) can survive. Cheap because it only drops
     * memoized *codegen* output — the once-only destructive lowering stays on the IR ([LOWERED]), so a
     * subsequent [decompileClass] re-runs only the pure backend, lazily, for classes actually re-requested.
     */
    private fun invalidateRenderCaches() {
        cache.clear()
        usageIndexCache.clear()
    }

    /**
     * Build the inverse (target → uses) index for [format] by decompiling every top-level class through the
     * cached [decompileClass] path and inverting each class's reference metadata (see [buildUsageIndex]).
     *
     * Fault-isolated per class (rule 4): a class whose decompile throws — or that yields no code metadata —
     * is skipped, never sinking the whole index. Iteration follows [classNames] order; the pure builder
     * then sorts each target's sites, so the result is order-independent and deterministic.
     */
    private fun buildUsageIndexNow(format: OutputFormat): UsageIndex {
        val sources = ArrayList<ClassUsageSource>()
        for (binaryName in classNames) {
            val decompiled = try {
                decompileClass(binaryName, format)
            } catch (e: Exception) {
                null
            } ?: continue
            val metadata = decompiled.metadata.code ?: continue
            // fromClass is the BINARY name (the decompileClass/classNames key a UI navigates with), not the
            // emitted source name — the code/metadata are this class's output unit.
            sources.add(ClassUsageSource(binaryName, decompiled.code, metadata))
        }
        return buildUsageIndex(sources)
    }

    /**
     * Index the loaded input classes by their binary (dotted) name, matching the key space of
     * [classNames] / [ModelBuilder] (descriptor `Lp/C;` → `p.C`, primitives/arrays left as-is). A later
     * duplicate name keeps the first occurrence, mirroring the model's first-wins dedup. Fault-isolated:
     * a class whose descriptor cannot be read is skipped rather than sinking the whole index.
     */
    private fun indexInput(loader: CodeLoader): Map<String, ClassData> {
        val map = LinkedHashMap<String, ClassData>()
        for (cls in loader.classes) {
            val name = try {
                binaryName(cls.type)
            } catch (e: Exception) {
                continue
            }
            if (name !in map) map[name] = cls
        }
        return map
    }

    private fun decompileNow(
        model: IrRoot,
        cls: IrClass,
        format: OutputFormat,
        cancellation: CancellationCheck,
    ): DecompiledClass =
        // Rule-4 per-CLASS net. The per-method backstop (both codegen backends) contains a throwing METHOD
        // inside generate(); this catches a failure OUTSIDE any method — class-level emission (a field /
        // header / enum reconstruction) or lower() itself — so it is contained to THIS class instead of
        // sinking decompileAll / decompileAllParallel's whole batch. It never fires for valid input (both
        // backends render class scope cleanly), so accurate output stays byte-identical. Structured
        // cancellation is re-thrown, never swallowed.
        try {
            decompileClassUnit(model, cls, format, cancellation)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            errorMarkedClass(cls, format)
        }

    /**
     * A minimal honest stub for a class whose lowering/rendering threw OUTSIDE the per-method backstop
     * (the rule-4 per-class net). Flags the class [AttrFlag.HAS_ERROR] so `countErrors` / the accuracy
     * signal see the failure, and returns a one-line `// JADXMP ERROR` source so the batch keeps every
     * other class. The source-name derivation is itself guarded (falls back to the binary name) so the net
     * can never throw.
     */
    private fun errorMarkedClass(cls: IrClass, format: OutputFormat): DecompiledClass {
        cls.add(AttrFlag.HAS_ERROR)
        val name = try {
            sourceFullName(cls, format, aliasMap)
        } catch (e: Exception) {
            cls.fullName
        }
        return DecompiledClass(
            fullName = name,
            code = "// JADXMP ERROR: class '${cls.fullName}' failed to decompile\n",
            metadata = ClassMetadata(code = null, errorCount = 1, fullyStructured = false),
        )
    }

    private fun decompileClassUnit(
        model: IrRoot,
        cls: IrClass,
        format: OutputFormat,
        cancellation: CancellationCheck,
    ): DecompiledClass {
        // A top-level class is emitted as ONE unit that also contains its whole nested-class tree, so every
        // inner class must be lowered (analysis is destructive → run it at most once per class) before the
        // outer is rendered. Codegen itself is pure and recurses into the inners on its own.
        val context = PassContext(model, cancellation)
        lower(cls, context)
        // Codegen is NOT purely a read of the IR: both backends MUTATE shared error attributes while
        // rendering — `flagError`/`emitErrorMarker` add AttrFlag.HAS_ERROR + IrAttrs.ERROR (both shared,
        // core:ir) on the IrClass/IrMethod they render. The inline-dedup key that decides whether a top
        // `// JADXMP ERROR` line is emitted is PER-BACKEND, but HAS_ERROR is not: if Kotlin (rendered
        // first on this instance) flags a node for a Kotlin-only gap, a later Java render on the SAME
        // node would see that stray HAS_ERROR, emit a spurious marker, and over-count — so the Java text
        // would depend on whether Kotlin ran first (a rule-2 determinism break). We defeat that by
        // capturing the post-lower error baseline ONCE (before this instance's first codegen) and
        // resetting every render back to it, so each format sees only lower()'s format-independent flags
        // plus its OWN codegen's flags. lower()'s RenderabilityGuard flagging is the correct baseline
        // (destructive, once-only); only the codegen-added error attrs are transient and restored here.
        val baseline = cls[ERROR_BASELINE] ?: snapshotErrorState(cls).also { cls[ERROR_BASELINE] = it }
        restoreErrorState(baseline)
        // Both backends consume the identical lowered IR (typed SSA + out-of-SSA bridge + RenderabilityGuard
        // flags applied in lower()); only the leaf source emission differs, so the honesty markers and
        // metadata contract are shared across formats. [format] is the per-call override, not necessarily
        // args.outputFormat — the same lowered class can be re-emitted in either format.
        val info: CodeInfo = when (format) {
            // Java applies the deobfuscation/user alias map AND the user comment map (both EMPTY unless the
            // user opted in ⇒ byte-identical when off). Kotlin renaming and comments are follow-ups, so it
            // always renders with the raw names and no injected comments.
            OutputFormat.JAVA -> JavaCodeGenerator().generate(cls, aliasMap, commentMap)
            OutputFormat.KOTLIN -> KotlinCodeGenerator().generate(cls)
        }
        return DecompiledClass(
            // The result's fullName is the EMITTED SOURCE name (sanitized package + simple name), which is
            // what the file path / recompile location must use so `class doWord` lands in `doWord.java` — not
            // the binary IrClass.fullName (`do`). The backend that renders the body is the source of truth,
            // so a renamed class's file path follows its body via the same alias map.
            fullName = sourceFullName(cls, format, aliasMap),
            code = info.code,
            metadata = ClassMetadata(
                code = info.metadata,
                errorCount = countErrors(cls),
                fullyStructured = isFullyStructured(cls),
            ),
        )
    }

    /** Run the (destructive, once-only) analysis passes over [cls] and every nested class beneath it. */
    private fun lower(cls: IrClass, context: PassContext) {
        if (cls[LOWERED] != true) {
            runner.runClass(cls, context)
            if (args.mode == DecompilationMode.FULL) {
                for (method in cls.methods) CodegenBridge.prepareForCodegen(method)
            }
            // Bail honestly on methods we cannot render correctly yet (branchy/φ): flag them so the
            // no-error signal fails and codegen marks them, rather than emitting clean-looking wrong Java.
            for (method in cls.methods) RenderabilityGuard.flagIfUnrenderable(method)
            cls[LOWERED] = true
        }
        for (inner in cls.innerClasses) lower(inner, context)
    }

    /**
     * Capture the post-lower error state of the whole output unit ([cls] + its methods + its nested-class
     * tree, the exact set [countErrors] sums over) so a later render can be reset to it. Only the SHARED,
     * cross-backend error attrs are snapshotted — [AttrFlag.HAS_ERROR] and [IrAttrs.ERROR] — because those
     * are the ones both codegen backends mutate and one backend's write leaks into the other's render. The
     * per-backend inline-dedup keys are NOT snapshotted: each backend reads only its own key, and
     * `emitErrorComment` short-circuits on `HAS_ERROR` before ever consulting it, so with HAS_ERROR reset
     * a stale inline key is unreachable (and its key is `internal` to the codegen module and identity-
     * compared, so core:api cannot reference it regardless). Captured before this instance's FIRST codegen,
     * so it holds only lower()'s format-independent flags — never any codegen contamination.
     */
    private fun snapshotErrorState(cls: IrClass): ClassErrorBaseline {
        val entries = ArrayList<NodeErrorState>()
        fun visit(node: IrClass) {
            entries += NodeErrorState.of(node)
            for (method in node.methods) entries += NodeErrorState.of(method)
            for (inner in node.innerClasses) visit(inner)
        }
        visit(cls)
        return ClassErrorBaseline(entries)
    }

    /** Reset every node in [baseline] to its captured HAS_ERROR / IrAttrs.ERROR state (see [snapshotErrorState]). */
    private fun restoreErrorState(baseline: ClassErrorBaseline) {
        for (entry in baseline.entries) entry.restore()
    }

    /** Error count across [cls], its methods, and its whole nested-class tree (one output unit). */
    private fun countErrors(cls: IrClass): Int {
        var count = if (cls.contains(AttrFlag.HAS_ERROR)) 1 else 0
        for (method in cls.methods) if (method.contains(AttrFlag.HAS_ERROR)) count++
        for (inner in cls.innerClasses) count += countErrors(inner)
        return count
    }

    private fun isFullyStructured(cls: IrClass): Boolean =
        cls.methods.all { RenderabilityGuard.isRenderable(it) } &&
            cls.innerClasses.all { isFullyStructured(it) }

    private fun buildRunner(): PassRunner {
        val plugins = args.registry.passPlugins
        val builtinMethodPasses = when (args.mode) {
            // FULL: the standard Phase-2 pipeline. FALLBACK: decode + CFG only (no SSA/types), so codegen
            // uses its linear per-block form — a robust degrade path, not compilable for arbitrary flow.
            DecompilationMode.FULL -> AnalysisPipeline.methodPasses
            DecompilationMode.FALLBACK -> listOf(BuildCfgPass())
        }
        return PassRunner(
            rootPasses = plugins.flatMap { it.rootPasses() },
            classPasses = plugins.flatMap { it.classPasses() },
            methodPasses = builtinMethodPasses + plugins.flatMap { it.methodPasses() },
        )
    }

    /** Cache identity for a rendered class: its top-level name AND the format it was emitted in. */
    private data class ClassCacheKey(val fullName: String, val format: OutputFormat)

    /**
     * The captured shared-error state of one node ([AttrFlag.HAS_ERROR] presence + the [IrAttrs.ERROR]
     * value), holding the node reference so [restore] re-applies it exactly. See [snapshotErrorState].
     */
    private class NodeErrorState(
        private val node: AttrNode,
        private val hasError: Boolean,
        private val error: DecompileError?,
    ) {
        fun restore() {
            if (hasError) node.add(AttrFlag.HAS_ERROR) else node.remove(AttrFlag.HAS_ERROR)
            if (error != null) node[IrAttrs.ERROR] = error else node.remove(IrAttrs.ERROR)
        }

        companion object {
            fun of(node: AttrNode): NodeErrorState =
                NodeErrorState(node, node.contains(AttrFlag.HAS_ERROR), node[IrAttrs.ERROR])
        }
    }

    /** The post-lower error baseline of a whole output unit; restored before every render (see [snapshotErrorState]). */
    private class ClassErrorBaseline(val entries: List<NodeErrorState>)

    private companion object {
        /** Marks an [IrClass] whose (destructive) analysis passes have already run. */
        val LOWERED: AttrKey<Boolean> = AttrKey("api.lowered")

        /**
         * Caches the post-lower [ClassErrorBaseline] on the top-level [IrClass], captured on the first
         * render and reused so every subsequent format render resets to the SAME clean baseline (never to
         * a baseline already contaminated by a prior format's codegen).
         */
        val ERROR_BASELINE: AttrKey<ClassErrorBaseline> = AttrKey("api.errorBaseline")
    }
}

/**
 * The name a [DecompiledClass] result reports as its [DecompiledClass.fullName] — the **emitted source
 * name**, from which the output `.java` file path and its `pkg/Simple.class` location are derived.
 *
 * For [OutputFormat.JAVA] this is the backend's sanitized source name ([JavaCodeGenerator.sourceName]),
 * so a reserved/invalid binary name (`do`, `do.if.A`, `do-`) is written to a file whose name matches the
 * class body the backend emits (`doWord.java`, `doWord/ifWord/A.java`, `do_.java`) and recompiles. It is
 * a derived VIEW: [IrClass.fullName] stays the binary identity key used everywhere else.
 *
 * Kotlin keeps the binary name for now — the `.kt` backend has the same file-naming gap and it is tracked
 * as a parallel follow-up (see the codegen-kotlin work item), not fixed here.
 */
internal fun sourceFullName(
    cls: IrClass,
    format: OutputFormat,
    aliasMap: AliasMap = AliasMap.EMPTY,
): String = when (format) {
    OutputFormat.JAVA -> JavaCodeGenerator.sourceName(cls, aliasMap)
    OutputFormat.KOTLIN -> cls.fullName
}
