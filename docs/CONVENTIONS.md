# jadxmp — Coding Conventions

## Language & style
- Kotlin, `kotlin.code.style=official`. **ktlint** formats; **detekt** lints. Both run in CI and must be green. Don't hand-fight the formatter — run it.
- Prefer immutable data (`val`, `data class`, read-only collections) especially in `core:ir`. The IR is mutated by passes, but mutation points are explicit and documented.
- Nullability is expressed in the type system — no `!!` in engine code except where an invariant is truly guaranteed and commented with *why*.
- Public API of `core:*` modules is deliberate and small; the **binary-compatibility-validator** tracks it. Internal types are `internal`.

## Multiplatform discipline (the cardinal rule)
- Engine modules (`core:*`) are authored in **`commonMain`**. Before using any API, confirm it exists on wasmJs/js — not just JVM. When in doubt, check the Kotlin stdlib common surface.
- **Forbidden in `commonMain`:** `java.*`, `javax.*`, `kotlinx.coroutines` blocking APIs, `ServiceLoader`, reflection, `System.*`, threads/`synchronized`. Use `expect/actual` or an injected interface instead, and put the actual in `jvmMain`/`wasmJsMain`.
- All filesystem/byte-source access goes through `core:binary-io` interfaces. No other module imports kotlinx-io directly.
- CI compiles every `core:*` module for wasmJs; a wasm-incompatible dependency or API is a build break, caught early.

## Concurrency
- Coroutines only. No `Thread`, `ExecutorService`, or `synchronized`. Use `Mutex`/`Semaphore` from kotlinx.coroutines and structured concurrency.
- Long-running loops call `coroutineContext.ensureActive()` (or `yield()`) for cancellation and, on single-threaded targets, UI responsiveness.
- Parallelism level comes from a platform `expect` (JVM: CPU-based; wasm/js: 1).

## Errors & robustness
- Per-class/per-method decompilation is fault-isolated: catch, attach an error attribute to the node, continue. One bad method never aborts a file (mirrors jadx's key robustness property).
- Malformed input (bad ARSC, truncated dex, zip bombs) degrades gracefully with a diagnostic, never an uncaught crash.
- No silent code loss. A transform that can't preserve semantics must bail (leaving correct-but-uglier output), not emit wrong code.

## Testing (see TESTING-ORACLE.md)
- Test-first. Behavior lands with `commonTest` tests in the same change.
- Use the shared `CodeAssert` DSL (`containsOne`/`countString`/`containsLine`/`oneOf`).
- Never weaken a test to get green. Never delete an oracle regression — fix it or escalate.
- Name tests after the construct under test and, where relevant, the jadx issue number they defend against.

## Naming & layout
- **We are NOT bound by jadx's names.** This is a clean-room redesign — prefer clearer, self-explanatory Kotlin names over jadx's abbreviations wherever they read better. jadx names are a reference for *what a thing is*, never a requirement for what we call it. Examples of names worth improving: `InsnType`→e.g. `IrOpcode`/`InstructionKind`, `InsnNode`→`Instruction`, `ArgType`→e.g. `TypeRef`/`Jtype`, `InsnArg`→`Operand`, `SSAVar`→`SsaValue`, `mth`/`cls`/`insn` abbreviations→full words. Pick the best name; don't copy a cryptic one just because jadx used it. (Keep genuinely standard domain terms — SSA, φ/phi, dominator, region — since those are industry vocabulary, not jadx-isms.)
- Gradle path `core:xxx` ↔ package `com.jadxmp.xxx`. App/UI under `com.jadxmp` / `com.jadxmp.ui`.
- One top-level type per file for anything non-trivial; small sealed hierarchies may share a file.
- Passes are named `<Verb><Subject>Pass` and declare ordering via `runAfter`/`runBefore`, not implicit list position.
- Whatever names you choose, document the mapping to jadx's concept in a KDoc line where it aids cross-referencing the oracle (e.g. `// jadx: ArgType`).

## Comments
- Comment the *why* and the invariants, not the *what*. The IR and the type lattice carry the subtle invariants — document those precisely.
- Reference the algorithm/source of a non-obvious approach (e.g. a paper for structuring) in a module-level doc, not inline.

## Dependencies
- Default to the kotlinx stack (io, serialization, coroutines, datetime). Adding any new dependency to a `core:*` module requires it to be wasmJs-compatible and is noted in the module's build file with a one-line rationale.
- `reference/jadx` is read-only design/test oracle. Never copy its source into `com.jadxmp.*`.
