# jadxmp — Testing & the Accuracy Oracle

The project promise is **"at least as accurate as jadx."** Because we chose a *clean-room redesign* rather than a port, accuracy cannot be assumed from shared code — it must be **measured continuously**. This document defines how.

## 1. The three accuracy signals (reused from jadx, decompiler-agnostic)

jadx's own test suite validates output three ways. All three are independent of jadx internals, so we reuse them verbatim against jadxmp output:

1. **No-error**: decompiled output contains no `JADX ERROR` / `inconsistent` markers and no error attributes on nodes. (Sanity — the decompiler didn't give up.)
2. **Recompiles**: the decompiled Java is fed back to the JDK compiler (in-memory). If it doesn't compile, the decompilation is wrong. (Strong, semantic-adjacent.)
3. **Executes identically**: for samples carrying an embedded `check()` method (119 in jadx), `check()` is run on both the *original* compiled class and the *decompiled-then-recompiled* class. Both must pass. (The gold standard — proves semantic equivalence, not just plausible text.)

For the **Kotlin** backend, signals (2)/(3) use the Kotlin compiler and the same `check()` execution, giving Kotlin output a real correctness gate too.

## 2. Two complementary test layers

### Layer A — jadxmp's own multiplatform unit/integration tests (`commonTest`)
Each engine module ships `kotlin.test` tests in `commonTest` so they run on **every target** (jvm, wasmJs, js). These are fast, hermetic, and the primary developer feedback loop. Categories mirror jadx's: loops, conditions, switches, try/catch, types/generics, inner/anonymous classes, enums, invoke/lambda, arrays, arithmetic, names/variables, inline, deobf.

We port the *assertion style*, not the framework: a tiny Kotlin `CodeAssert` with `containsOne(s)`, `countString(n, s)`, `containsLine(indent, line)`, `oneOf(...)` — ~150 lines, pure string logic — reproducing jadx's `JadxCodeAssertions`. Tests read like:
```kotlin
decompile(TestBreakInLoop::class).assertCode()
    .containsOne("for (int i = 0; i < a.length; i++) {")
    .containsOne("break;")
    .countString(0, "else")
```
Java-source samples are stored as small `.smali`/`.class`/`.dex` fixtures in `corpus/` (not embedded as compilable nested classes, since `commonTest` can't run javac). Where a Java sample is needed, it is pre-compiled once by `tools:oracle` and checked in as a `.class`/dex fixture.

### Layer B — the differential oracle (`tools:oracle`, JVM-only)
This is what makes "at least as accurate" enforceable. A JVM-only harness that, for every input in the shared corpus:
1. Runs **reference jadx** (`reference/jadx` as a library) → reference output + which of the 3 signals it passes.
2. Runs **jadxmp** (`core:api`) → our output + which signals it passes.
3. Compares, and classifies each sample as: **PARITY** (we pass every signal jadx passes), **REGRESSION** (jadx passed a signal we fail), or **IMPROVEMENT** (we pass a signal jadx fails).
4. Produces a scoreboard (counts per category, plus per-sample diffs).

The gate: **zero REGRESSIONs** on the tracked corpus. A change that introduces a regression fails CI. IMPROVEMENTs are celebrated and, once stable, promoted into Layer A as new expectations. Textual diffs are advisory (formatting differs by design); the *signals* are the gate.

## 3. The corpus

Sources, all copied into a fenced `corpus/` tree (kept isolated for licensing clarity — see decisions):
- **221 `.smali`** inputs from `jadx-core/src/test/smali/**` — language-neutral, drop-in.
- **9 `.raung`** inputs.
- Binary samples (`hello.dex`, sample APKs) from `jadx-core/src/test/resources/`.
- The **~466 embedded Java `TestCls` samples** — extracted mechanically (each is a uniform `public static class TestCls` block) and pre-compiled by `tools:oracle` into `.class`/dex fixtures, preserving any `check()` method.

Corpus growth: every bug we fix and every open-jadx-issue we address adds a new sample with an inline expectation, so the suite encodes our accuracy frontier, not just jadx's.

## 3a. The adversarial review gate (required for every implementation)

No module implementation is "done" until it has passed **at least one adversarial review** by an agent *other than the one that wrote it*. The reviewer's job is to break the code, not bless it. It specifically hunts for:
- **Correctness bugs** — off-by-one in binary parsing, sign-extension/endianness errors, wrong lattice merges, incorrect dominator/SSA/φ placement, mis-structured control flow, operator-precedence bugs in codegen.
- **Silent code loss** — any transform that can drop or reorder semantics without preserving them (a cardinal-rule violation).
- **Weak or fake tests** — tests that assert nothing meaningful, tautologies, over-mocked paths, happy-path-only coverage, or expectations that were reverse-engineered from buggy output. The reviewer must confirm the tests would actually *fail* if the code were wrong.
- **Portability violations** — `java.*`/`javax.*`/reflection/threads sneaking into `commonMain`, or an API that isn't on wasmJs.
- **Robustness gaps** — malformed/truncated/hostile input that crashes instead of degrading; missing cancellation checks in hot loops.

Process — **review/fix/re-review until clean, no exceptions:**
1. The implementer finishes and self-verifies (tests green on jvm + wasm).
2. A fresh reviewer agent is spawned with the diff/module and the mandate above; it reports findings ranked by severity.
3. If there are any must-fix findings, the implementer fixes them and re-verifies.
4. **Every fix triggers a new adversarial review of the revised code** — by an agent other than the one that wrote the fix (resuming the prior reviewer is fine; it retains context and re-runs its own independent checks). This repeats: fix → review → fix → review …
5. The module counts as **landed only when an adversarial review pass completes with zero must-fix findings.** A maintainer/self "quick check" NEVER substitutes for that final clean review — code changed in response to a review must always be reviewed again. **Never leave code unreviewed.**

Record the full review/fix chain and the final clean verdict in the module's milestone report. The `/code-review` skill may be used as an additional automated pass, but does not replace the adversarial agent review.

## 4. What each specialized agent must do

Every module-owning agent (see `.claude/agents/`) is **test-first**:
- New behavior lands with `commonTest` tests (Layer A) in the same change.
- When a module reaches a milestone, run the relevant slice of the oracle (Layer B) and report the PARITY/REGRESSION/IMPROVEMENT scoreboard in the summary.
- Never weaken a test to make it pass. A red oracle regression is a real accuracy loss and must be fixed or explicitly escalated.

## 5. Coverage & CI

- **Kover** measures per-module coverage; the analysis "brains" (`core:pipeline` type-inference and region-structuring) carry the highest bars.
- CI matrix builds and runs `commonTest` on jvm + wasmJs + js for every `core:*` module (proving portability *and* correctness on all targets), then runs `tools:oracle` on JVM as the regression gate.
- The oracle scoreboard is published as a build artifact so accuracy trends are visible over time.
