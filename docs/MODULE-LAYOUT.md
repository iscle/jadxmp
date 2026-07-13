# jadxmp — Module Layout

Gradle module structure for the rewrite. All engine modules are Kotlin Multiplatform libraries whose **primary source set is `commonMain`** and which target `jvm`, `wasmJs`, `js`, `android`, and (later) `ios`. No engine module may declare a dependency that is unavailable on wasmJs.

Naming: Gradle path `core:xxx`, package root `com.jadxmp.xxx`. Type-safe project accessors are enabled (`projects.core.ir`).

## Engine modules (KMP libraries, `commonMain`-first)

| Module | Package root | Depends on | Responsibility |
|---|---|---|---|
| `core:binary-io` | `com.jadxmp.io` | kotlinx-io, Kompress | The only module that reads bytes / touches a filesystem. `ByteReader` over `ByteArray` (little-endian, LEB128, MUTF-8); **DEFLATE + ZIP reading via the [Kompress](https://github.com/karmakrafts/Kompress) KMP library** (pure-Kotlin, all targets incl. wasmJs — we do NOT reimplement compression); a small zip-slip/zip-bomb **security guard** we own wrapping Kompress's zip reader; and a `FileSystem` interface (kotlinx-io backed). Everything else stays byte-source-agnostic. |
| `core:input` | `com.jadxmp.input` | binary-io | The **input SPI**: `ClassData`, `MethodData`, `FieldData`, `CodeReader`, `Opcode`, debug/annotation model — the normalized model the engine consumes. Also container detection & multi-input assembly. |
| `core:input-dex` | `com.jadxmp.input.dex` | input | Hand-written **DEX** parser (header incl. v41 containers, sections, insns, LEB/MUTF8, annotations, debug info) → input model. Produces smali text for display too. |
| `core:input-jvm` | `com.jadxmp.input.jvm` | input | Hand-written **`.class`/`.jar`** parser (constant pool, attributes, JVM opcodes) → input model. No ASM. |
| `core:ir` | `com.jadxmp.ir` | (none) | The **IR**: `InsnNode`/`InsnArg`, `ArgType` lattice, `RootNode`/`ClassNode`/`MethodNode`/`FieldNode`, `BasicBlock`, `SsaVar`/`CodeVar`, `Region` tree, attribute system. Pure data + invariants. |
| `core:pipeline` | `com.jadxmp.pipeline` | ir, input | The **pass framework** + all analysis passes: IR decode, block/CFG + dominators, SSA, type inference, expression shaping, region structuring, variable naming. The bulk of the engine. May be split into `core:pipeline-ssa`, `core:pipeline-types`, `core:pipeline-regions` if it grows unwieldy (see below). |
| `core:codegen` | `com.jadxmp.codegen` | ir | The `CodeWriter` + **offset→annotation metadata** model + `CodeInfo`. Backend-agnostic source-emission plumbing (imports, indentation, naming). |
| `core:codegen-java` | `com.jadxmp.codegen.java` | codegen, ir | Java source backend (region-tree → `.java`). The accuracy-diff target. |
| `core:codegen-kotlin` | `com.jadxmp.codegen.kotlin` | codegen, ir | Kotlin source backend (region-tree + Kotlin-idiom reconstruction → `.kt`). |
| `core:resources` | `com.jadxmp.resources` | binary-io | Self-contained **ARSC** + **binary-XML/AndroidManifest** decoder → text/XML, plus resource-id → symbolic-name resolution. Own tiny XML writer (no `javax.xml`). |
| `core:api` | `com.jadxmp.api` | all above | Public facade: `Decompiler`, `DecompilerArgs`, `JavaClass`/… result wrappers, the `DecompilerScheduler` (coroutines), the plugin registry, save/stream orchestration. **The only module UI code depends on.** |

If `core:pipeline` exceeds ~15k LOC, split along stage boundaries — the pass framework interface (`Pass`, `PassContext`, ordering) lives in `core:ir` or a tiny `core:pass-api` so sub-modules can register passes without a cycle. Decide at that point, not before.

## Application & UI modules

| Module | Type | Notes |
|---|---|---|
| `ui:app` | KMP (Compose), commonMain | All UI logic & Composables: project/session state, tree, code viewer, tabs, search. Depends on `projects.core.api`. The existing `shared` module is either renamed to `ui:app` or `ui:app` supersedes it — see ROADMAP phase 0. |
| `desktopApp` | JVM (Compose Desktop) | Entry point; native file access; embeds engine directly. Exists. |
| `webApp` | wasmJs/js (Compose) | Entry point; browser file upload/download; runs engine in wasm. Exists. |
| `androidApp` | Android (Compose) | Exists; tablet-oriented later. |
| `iosApp` | iOS | Exists; future. |

## Tooling / test modules (JVM-only, never shipped to wasm)

| Module | Type | Notes |
|---|---|---|
| `tools:oracle` | JVM test-fixtures | The **differential oracle harness**: runs `reference/jadx` as an oracle, compiles Java samples with the JDK compiler, executes `check()` round-trips, and diffs jadx output vs jadxmp output over the shared corpus. Depends on the jadx jars + `core:api`. JVM-only by nature. See TESTING-ORACLE.md. |
| `corpus/` (not a module) | resources | The fenced, jadx-derived test-input corpus (smali/dex/class/apk samples). Kept isolated for licensing clarity. |

## Dependency rules (enforced, not aspirational)

1. Engine modules (`core:*`) must configure and compile for **wasmJs**. CI builds `:core:xxx:compileKotlinWasmJs` for each. A dependency that breaks wasm is rejected.
2. No `core:*` module depends on a `ui:*` or `*App` module. Dependencies point **down** the table only.
3. `ui:*` depends on the engine **only through `core:api`** — never on `core:pipeline`/`core:ir` internals.
4. JVM-only capabilities (metadata decode, mapping formats, apksig) enter through interfaces defined in `core:api`, implemented in JVM-only modules injected by `desktopApp`/`androidApp`. The common core degrades gracefully without them.
5. `tools:oracle` and `corpus/` are test-scope only and never appear in an app's runtime classpath.
