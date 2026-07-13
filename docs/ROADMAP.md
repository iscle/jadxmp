# jadxmp — Roadmap

Phased delivery. Each phase ends with something **buildable, tested on all engine targets, and measured against the oracle** where applicable. Phases are sequenced by dependency; within a phase, modules can be built in parallel by different agents.

Legend: 🧱 foundation · 🧠 engine · 🎨 UI · 🔬 tests/tooling

## Phase 0 — Foundation & scaffolding 🧱
- Version-catalog entries: kotlinx-io, kotlinx-serialization, kotlinx-coroutines, ktlint, detekt, binary-compat-validator, Kover.
- A convention plugin (or shared build logic) for "engine KMP library" so every `core:*` module gets identical targets (jvm/wasmJs/js/android), `commonTest` wiring, and quality-gate hookup.
- Create `core:binary-io` with a real, unit-tested `ByteReader` (LEB128 + MUTF-8) — the first proof the toolchain and the "wasm-safe" discipline work end to end.
- Stand up `tools:oracle` skeleton + `corpus/` with the smali inputs imported. CI matrix (jvm/wasmJs/js test + JVM oracle).
- **Exit:** `./gradlew :core:binary-io:allTests` green on all targets; oracle harness runs (even if it only reports "0 samples decompiled yet").

## Phase 1 — Read the world 🧱🧠🔬
- `core:binary-io`: DEFLATE + ZIP via the Kompress KMP library, wrapped in our own zip-slip/zip-bomb security guard (we don't reimplement compression).
- `core:input` SPI finalized.
- `core:input-dex`: full DEX parser → input model (multi-dex, v41 containers, debug info, annotations). Validated by round-trip tests against the dex fixtures.
- `core:ir`: instruction IR + `ArgType` lattice (with an exhaustive lattice unit-test spec) + node model.
- **Exit:** any `.dex`/`.apk` in the corpus loads into the IR; `ArgType` lattice fully specified and tested.

## Phase 2 — Decompile a method 🧠🔬
- `core:pipeline`: IR decode → blocks/CFG → dominators/frontier → SSA (φ placement + rename). Exact SSA shape locked by tests.
- First **type inference**: constraint propagation over the lattice; backtracking-search fallback; heuristic repair.
- `core:codegen` metadata plumbing + `core:codegen-java` for straight-line and simple-branch methods.
- **Exit:** simple methods (arith, field/array access, calls, if/else) decompile to recompilable Java; oracle shows PARITY on the `arith`/`invoke`/`arrays`/`conditions` corpus slices.

## Phase 3 — Structured control flow 🧠🔬 (the hard part)
- Region structuring: if/else, loops (for/while/for-each), switch (incl. string-switch & enum-switch), try/catch, **finally extraction**, synchronized — with the non-crashing irreducible-graph fallback.
- Expression shaping: inline/shrink, simplify, constructor/enum/anonymous-class reconstruction.
- Variable naming & declaration scoping.
- **Exit:** the `loops`/`switches`/`trycatch`/`enums`/`inner` corpus slices reach PARITY; zero oracle REGRESSIONs overall. This is the milestone where jadxmp is "a real decompiler."

## Phase 4 — Resources & the rest of input 🧠
- `core:resources`: ARSC + binary-XML + manifest decode + resource-id resolution.
- `core:input-jvm`: `.class`/`.jar` parsing (enables non-Android Java decompiling and more test inputs).
- `core:api`: full facade, scheduler, save/stream, plugin registry.
- **Exit:** open an APK, browse decompiled classes + decoded resources + manifest, save a project — headless.

## Phase 5 — Desktop GUI (MVP) 🎨
Tier-1 GUI from the analysis: project open, class/resource **tree**, **code viewer** (metadata-driven highlighting), **tabs**, **jump-to-definition** + back/forward, **find usages**, **search** (class/method/field/code/resource), **smali view**, resource/image/hex viewing, settings + project persistence, background jobs/progress.
- **Exit:** desktopApp is a usable decompiler competitive with jadx-gui's core loop.

## Phase 6 — Web target 🎨🧱
- Bring the engine up on **wasmJs** in the browser (it already compiles there from Phase 1; here we wire the real UI + browser file upload/download + in-memory FS).
- Address wasm perf/memory (single-threaded scheduling, chunked/yielding decompilation).
- **Exit:** webApp decompiles an uploaded APK fully client-side, no backend.

## Phase 7 — Kotlin output & differentiators 🧠
- `core:codegen-kotlin`: idiomatic Kotlin reconstruction (coroutine state-machine → `suspend`, `when`, data classes, null-safety), gated by Kotlin-compiler recompile + `check()` execution.
- Deobfuscation/rename mapping model (persistent, importable). Kotlin `@Metadata` recovery (common port).
- **Exit:** Kotlin output that recompiles for the corpus; a differentiator no other decompiler offers well.

## Phase 8 — Mobile & polish 🎨
- Tablet-optimized Compose layouts (androidApp); iosApp bring-up.
- Tier-2/3 GUI features (rename/comments UI, split Java↔smali sync, graphs via a non-Graphviz renderer, themes).

## Ongoing across all phases
- Oracle scoreboard tracked every phase; **zero regressions** is a standing gate.
- Each open jadx issue we intend to beat becomes a tracked corpus sample the moment we touch its area.
