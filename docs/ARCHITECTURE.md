# jadxmp — Architecture

A Kotlin Multiplatform + Jetpack Compose clean-room rewrite of the [jadx](https://github.com/skylot/jadx) Android/Java decompiler.

> This document is the source of truth for **how the system is structured and why**. Module-by-module build details live in [MODULE-LAYOUT.md](MODULE-LAYOUT.md); the delivery plan in [ROADMAP.md](ROADMAP.md); the accuracy strategy in [TESTING-ORACLE.md](TESTING-ORACLE.md); code style in [CONVENTIONS.md](CONVENTIONS.md).

## 1. Goals and non-negotiables

1. **Desktop and web are first-class.** The decompiler engine is authored entirely in `commonMain` Kotlin and must compile and run on **wasmJs in a browser with no backend**. Every engine module is forbidden from touching JVM-only APIs (`java.io`, `java.nio`, `ExecutorService`, `ServiceLoader`, `javax.xml`, `java.awt`, gson, slf4j). Platform needs are met with `expect/actual`, injected interfaces, or an existing multiplatform library. **We don't reinvent solved problems:** DEFLATE/ZIP come from the [Kompress](https://github.com/karmakrafts/Kompress) KMP library, IO from kotlinx-io, JSON from kotlinx.serialization — we hand-write only what has no good multiplatform library (the DEX/class/ARSC binary parsers and the decompiler algorithms themselves).
2. **At least as accurate as jadx.** We do a *clean-room redesign* (new architecture, not a line-by-line port), but jadx is retained as a **differential oracle**. Its ~773-test corpus and three accuracy signals gate every change. Accuracy is never a matter of opinion — it is measured. See [TESTING-ORACLE.md](TESTING-ORACLE.md).
3. **Dual output backends.** One IR feeds both a **Java** source backend (needed to diff against jadx's Java output) and a **Kotlin** source backend (the user-facing differentiator: coroutines→`suspend`, `when`, data classes, null-safety).
4. **Mobile-ready later.** Tablets especially. Nothing in the engine or shared UI may assume a desktop-only capability; desktop-only features (native file dialogs, JVM decompile fallbacks) live behind platform interfaces.

## 2. The big picture

```
              ┌────────────────────────────────────────────────────────────┐
              │                        Applications                          │
              │  desktopApp (JVM)   webApp (wasmJs/js)   androidApp   iosApp │
              └───────────────┬─────────────────────────────┬──────────────┘
                              │ Compose Multiplatform UI     │
              ┌───────────────▼─────────────────────────────▼──────────────┐
              │                      ui:app (commonMain)                     │
              │  project model · tree · code viewer · tabs · search · state │
              └───────────────────────────┬─────────────────────────────────┘
                                          │ facade only (com.jadxmp.api)
              ┌───────────────────────────▼─────────────────────────────────┐
              │                         core:api                             │
              │   Decompiler · DecompilerArgs · orchestration · registry     │
              └───┬───────────┬───────────┬───────────┬──────────┬──────────┘
                  │           │           │           │          │
        ┌─────────▼──┐ ┌──────▼─────┐ ┌───▼──────┐ ┌──▼───────┐ ┌▼──────────┐
        │ core:input │ │  core:ir   │ │core:pipe │ │core:code │ │core:      │
        │  (SPI +    │ │ (IR model, │ │ line     │ │ gen-*    │ │ resources │
        │  dex, jvm) │ │ ArgType)   │ │(passes)  │ │(java,kt) │ │(arsc,xml) │
        └─────┬──────┘ └─────┬──────┘ └────┬─────┘ └────┬─────┘ └─────┬─────┘
              └──────────────┴─────────────┴────────────┴─────────────┘
                                          │
                              ┌───────────▼───────────┐
                              │     core:binary-io     │
                              │ ByteReader · LEB128 ·  │
                              │ MUTF8 · inflate · zip ·│
                              │ FileSystem (kotlinx-io)│
                              └────────────────────────┘
```

Everything above `core:binary-io` is pure computation. `core:binary-io` is the single module that concentrates the "read bytes / touch the filesystem" concerns, behind interfaces, so the rest of the engine never needs `expect/actual`.

## 3. The pipeline (five stages)

We keep jadx's proven *stage decomposition* — it reflects hard data dependencies, not arbitrary choices — while redesigning the internals of each stage. Stages, in strict dependency order:

1. **Load** — an input plugin parses a container/format into the **input model** (`core:input` SPI: `ClassData`/`MethodData`/`CodeReader`). The engine never sees raw `.dex`/`.class` bytes. Multi-dex, duplicate-class resolution handled here.
2. **IR build** — `CodeReader` opcodes are decoded into the **instruction IR** (`InsnNode`, `InsnArg`, the `ArgType` lattice). DEX/JVM opcodes are normalized to a compact IR opcode set.
3. **CFG + SSA** — split into basic blocks, build the dominator tree and dominance frontier, place φ-functions, rename into SSA. This is textbook and must be exact — everything downstream depends on SSA shape.
4. **Analysis** — the two brains:
   - **Type inference**: reconstruct Java types over untyped DEX registers via constraint propagation over a type lattice, with a backtracking search fallback and heuristic repair.
   - **Control-flow structuring**: turn the goto-based CFG into a nested **region tree** (if/else, loops, switch, try/catch/finally, synchronized). This is jadx's #1 bug source, so we invest here deliberately (see §6).
   - Plus expression shaping: inline single-use defs into expression trees, simplify, reconstruct constructors/enums/anonymous classes/string-switch/finally.
5. **Codegen** — walk the region tree and emit source text through a `CodeWriter` that simultaneously records **offset→annotation** metadata (definitions, references, variables, line map). Two backends (Java, Kotlin) share the walk; only leaf emission differs.

The pass framework runs stages 2–5 **per class, lazily and cancelably** (a class is decompiled on demand, then may be unloaded to bound memory), with whole-program "prepare" passes (signatures, usage graph, deobfuscation, anonymous-class detection) run once up front. Per-pass faults are caught and downgraded to node-level error attributes so one bad method never crashes a file.

## 4. Concurrency model

No `ExecutorService`, no `synchronized`. Orchestration is **coroutines**:
- A `DecompilerScheduler` builds dependency-aware batches (a class + its codegen deps decompile together) and runs them over a bounded `Dispatchers.Default`-style pool, with a platform `expect` for the parallelism level (JVM = CPU/2; wasm/js = 1, cooperative).
- Per-class mutual exclusion uses a `Mutex` keyed by class identity.
- Cancellation is structured-concurrency-native (`ensureActive()` in hot loops), replacing jadx's `Thread.interrupt()` checks.
- On single-threaded targets (browser today) everything still works — it just runs sequentially. Long jobs yield so the UI stays responsive.

## 5. The IR

Three progressive representations, each owned by `core:ir`:
- **Instruction IR** — `InsnNode` (type + result register + args). Args are `RegisterArg` / `LiteralArg` / an inlined `InsnWrapArg` (this is how nested expressions are built). Synthetic ops (ternary, constructor, string-concat) are added by later passes.
- **CFG/SSA** — `BasicBlock` list with pred/succ edges, dominator data; `SsaVar` (single def, many uses, a type-info cell, and a link to the final `CodeVar`). Multiple SSA versions collapse to one source-level `CodeVar`.
- **Region tree** — `Region` and its kinds (`IfRegion`, `LoopRegion`, `SwitchRegion`, `TryCatchRegion`, `SyncRegion`) stored on the method after structuring.

`ArgType` is an **immutable type lattice** encoding primitives, objects, arrays, generics/wildcards/type-variables, and the *partial/unknown* types (`UNKNOWN`, `NARROW`, `WIDE`, integral-narrow…) that represent still-ambiguous register types before inference resolves them. Its semantics are the foundation of the type engine and are specified precisely in `core:ir`'s own docs and locked by unit tests.

## 6. Where we deliberately diverge from jadx

Clean-room means we can fix known-structural weaknesses instead of inheriting them:
- **Control-flow structuring** is jadx's largest open-bug category (multi-entry/irreducible loops throw; switch case-ordering breaks). We design structuring around a well-specified algorithm (dominator/loop analysis + a structuring pass in the DREAM / "no-more-gotos" family) with an **explicit, non-crashing fallback** for irreducible graphs (labeled gotos / a `goto`-emitting region) rather than an exception. Correctness is validated against the jadx switch/loop/try-catch test corpus specifically.
- **Non-lossy transformations.** Inlining/shrinking passes must be provably code-preserving; a transformation that would drop code is a bug, not a readability trade-off (jadx #1878). Aggressive readability options are opt-in and separately tested.
- **Fault isolation & hostile input** are first-class: every method decompiles in isolation with a bytecode/smali fallback view, and malformed ARSC/zip degrades gracefully instead of aborting the job.
- **Memory:** lazy per-class decompilation + unload, streaming output, so large modern APKs are usable (jadx OOM/24h-run reports).

## 7. Plugin/extensibility model (multiplatform-safe)

jadx uses `ServiceLoader`, which does not exist off-JVM. We replace it with an explicit **plugin registry**: input plugins and pass plugins are registered by constructing a `Decompiler` with a list (a small default registry is assembled per platform). Same two extension categories as jadx — **input plugins** (produce the input model) and **pass plugins** (transform the IR, ordered by `runAfter`/`runBefore` hints) — but wired statically. External/dynamic JVM plugin loading, if ever needed, is a JVM-only add-on outside the common core.

## 8. What stays JVM-only (for now)

These jadx capabilities depend on heavy JVM/Android libraries with no wasm path; they are **optional, JVM-only modules** injected behind interfaces, never in the common path:
- `.jar`/`.class` → dex conversion (r8/dx/asm) — *not needed*, since we parse `.class` directly.
- AAB/proto resources (protobuf/bundletool), APK signature verification (apksig).
- Kotlin `@Metadata` decoding (kotlin-metadata-jvm) and standard rename-mapping formats (mapping-io) — high value; ported to common later, JVM-shimmed initially.
- Smali *assembly* input (android-smali). Smali *display* we generate ourselves from the input model.

## 9. UI architecture (Compose Multiplatform)

`ui:app` holds all UI logic in `commonMain`: a project/session model, the class/resource tree, a **custom code viewer** (Compose has no RSyntaxTextArea — we build a viewer over the engine's `CodeMetadata`, which already carries per-offset token/definition/reference info, so highlighting and jump-to-definition are driven by engine metadata, not a re-lexer), tabbed editing, and search. Desktop embeds the engine directly; web runs the same engine compiled to wasm; both share the UI. Platform-specific shells (`desktopApp`, `webApp`, `androidApp`, `iosApp`) only provide entry points, file access, and window chrome. See ROADMAP for the phased GUI feature list.

## 10. Reference material

The original jadx is cloned read-only at `reference/jadx`. It is a **design oracle and test oracle only** — do not copy its source. When in doubt about *behavior* (what output is correct for a given input), the answer is "what makes the shared test corpus pass," not "what jadx's code does line-by-line."
