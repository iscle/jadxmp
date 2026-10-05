# AGENTS.md — jadxmp

Kotlin Multiplatform + Jetpack Compose **clean-room rewrite of the [jadx](https://github.com/skylot/jadx) decompiler**. Primary targets: **desktop and web**; mobile (tablets) later.

## Read this first
- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** — how the system is structured and why. Start here.
- **[docs/MODULE-LAYOUT.md](docs/MODULE-LAYOUT.md)** — the Gradle modules and their dependency rules.
- **[docs/ROADMAP.md](docs/ROADMAP.md)** — phased plan; what to build next.
- **[docs/TESTING-ORACLE.md](docs/TESTING-ORACLE.md)** — how we prove "at least as accurate as jadx".
- **[docs/CONVENTIONS.md](docs/CONVENTIONS.md)** — coding style & the multiplatform rules.

## The four rules that override everything
1. **The engine is `commonMain` and must compile for wasmJs.** No `java.*`/`javax.*`, no `ServiceLoader`, no reflection, no threads/`synchronized` in `core:*` common code. Filesystem/bytes go through `core:binary-io` only. Platform needs use `expect/actual` or injected interfaces. (This is why the rewrite exists — the browser target is client-side, no backend.)
2. **Accuracy is measured, never assumed.** We redesigned rather than ported, so every change is gated by the differential oracle (`tools:oracle`) and `commonTest`. Zero oracle **REGRESSIONs**. Never weaken a test.
3. **jadx is an oracle, not a source.** `reference/jadx` is cloned read-only at **`0e232fb3510ec86083af0055470163d3550957cd`**, the original project baseline. `tools:jadx-reference` verifies the clean checkout and builds the JVM oracle directly from that commit. The pin lives in `tools/jadx-reference/baseline.properties`; do not substitute a Maven release or advance it without an explicit baseline migration. Do **not** copy its code into `com.jadxmp.*`. "Correct output" = "what makes the corpus pass".
4. **Fault isolation & no silent code loss.** One bad method must never crash a file; a transform that can't stay correct must bail to uglier-but-correct output.

## Layout at a glance
```
core:binary-io   bytes, LEB128, MUTF8, inflate, zip, FileSystem (kotlinx-io) — the only IO module
core:input(-dex)  parse DEX/APK → normalized input model (JVM input is planned)
core:ir          IR: InsnNode, ArgType lattice, node model, region tree
core:pipeline    pass framework + CFG/SSA/type-inference/structuring/naming (the engine)
core:codegen(-java/-kotlin)  CodeWriter + metadata; Java & Kotlin backends
core:resources   ARSC + binary-XML/manifest decode
core:api         public facade: Decompiler, args, coroutine scheduler, plugin registry
ui:app           Compose Multiplatform UI (commonMain); depends ONLY on core:api
desktopApp/webApp/androidApp/iosApp   platform shells
tools:oracle     JVM-only reference + javac/kotlinc harness; targeted execution tests
tools:jadx-reference  isolated build of the original pinned upstream source
corpus/          fenced jadx-derived test inputs (licensing-isolated)
```
Dependencies point **down**. UI touches the engine only via `core:api`. Every `core:*` module builds for jvm+wasmJs+js.

## Decisions already made (don't relitigate)
Pure-KMP core (wasm in browser) · clean-room redesign (jadx = oracle) · **both** Java & Kotlin output · IO = **kotlinx-io** · namespace = **`com.jadxmp`** · planned quality gates = ktlint + detekt + binary-compat-validator + Kover (not yet configured) · license = TBD (keep `corpus/` fenced). Durable context lives in this file and docs/.

## Build & test
```bash
./gradlew :core:binary-io:allTests        # run a module's tests on all targets
./gradlew :core:ir:compileKotlinWasmJs     # prove wasm-compatibility of a module
# ktlint/detekt/ABI/Kover are planned; no tasks are currently configured
./gradlew :tools:oracle:smaliScoreboard  # enforced Java differential gate
./gradlew :tools:oracle:kotlinScoreboard # informational Kotlin compilation report
./gradlew :desktopApp:run                  # launch the desktop app
```
(Some tasks land as their phase is implemented — see ROADMAP.)

## Working with specialized agents
Module ownership is split across subagents defined in `.claude/agents/` (dex input, IR, SSA/type-inference, control-flow structuring, codegen, resources, Compose UI, oracle tests, adversarial reviewer). When implementing a module, prefer delegating to its owning agent so context stays focused; give each agent the relevant docs/ files and the corpus slice it must satisfy. Every coding agent is **test-first** and reports its oracle scoreboard at milestones.

**Adversarial review is mandatory and iterative.** Every implementation gets an adversarial review by a *different* agent (see `.claude/agents/adversarial-reviewer.md` and docs/TESTING-ORACLE.md §3a). **Every fix made in response to a review triggers a fresh review of the revised code** — fix → review → fix → review — and a module counts as landed only when a review pass completes with zero must-fix findings. A self "quick check" never substitutes for that final clean review. **Never leave changed code unreviewed.** Note: `.claude/agents/` contains briefs for available general agents. Follow the current environment’s agent APIs; these briefs are documentation, not registered agent types.

## House style
Immutable-leaning Kotlin, official style, small deliberate public APIs (`internal` by default), comment the *why*/invariants. See docs/CONVENTIONS.md before writing engine code.

## Readiness and architecture
- Treat full jadx parity and production readiness as measured goals, never as claims inferred from a green build. Report remaining compiler failures, semantic gaps, unsupported input, and unrun checks honestly.
- Preserve module boundaries: `tools:oracle` and `tools:jadx-reference` are JVM-only verification tools and must never appear on an app or engine runtime classpath. Keep upstream sources outside `com.jadxmp.*`.
- Prefer shared typed IR semantics over backend guesses. Preserve bytecode distinctions (including floating-point NaN behavior) through decode, transforms, and both emitters.
- Compiler acceptance alone is insufficient for semantic changes: include execution checks for edge cases and side-effect ordering.
- Preserve pre-existing user edits. Do not alter the read-only reference checkout to make a test pass.
