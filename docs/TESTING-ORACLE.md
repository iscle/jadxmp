# jadxmp — Testing & the Accuracy Oracle

The project promise is **"at least as accurate as jadx."** Because we chose a *clean-room redesign* rather than a port, accuracy cannot be assumed from shared code — it must be **measured continuously**. This document defines how.

## 1. The three accuracy signals (reused from jadx, decompiler-agnostic)

jadx's own test suite validates output three ways. All three are independent of jadx internals, so we reuse them verbatim against jadxmp output:

1. **No-error**: decompiled output contains no `JADX ERROR` / `inconsistent` markers and no error attributes on nodes. (Sanity — the decompiler didn't give up.)
2. **Recompiles**: the decompiled Java is fed back to the JDK compiler in a temporary workspace. If it doesn't compile, the decompilation is wrong. (Strong, semantic-adjacent.)
3. **Executes identically**: for samples carrying an embedded `check()` method (119 in jadx), `check()` is run on both the *original* compiled class and the *decompiled-then-recompiled* class. Both must pass. (Execution evidence for the cases asserted by each check, not a proof for all possible inputs.)

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
Multiplatform tests consume IR or precompiled bytecode because `commonTest` cannot run javac. Trusted source fixtures in `corpus/java` are compiled and converted to DEX at test time by the JVM-only oracle using D8 pinned to the original jadx baseline's version.

### Layer B — the differential oracle (`tools:oracle`, JVM-only)
This is what makes "at least as accurate" enforceable. A JVM-only harness that, for every input in the shared corpus:
1. Runs **reference jadx** (original commit `0e232fb3510ec86083af0055470163d3550957cd`, built from `reference/jadx` by the isolated JVM-only `tools:jadx-reference` module) → reference output + which of the 3 signals it passes.
2. Runs **jadxmp** (`core:api`) → our output + which signals it passes.
3. Compares, and classifies each sample as: **PARITY** (we pass every signal jadx passes), **REGRESSION** (jadx passed a signal we fail), or **IMPROVEMENT** (we pass a signal jadx fails).
4. Produces a scoreboard (counts per category, plus per-sample diffs).

The gate: **zero REGRESSIONs** on the tracked corpus. A change that introduces a regression fails CI. IMPROVEMENTs are celebrated and, once stable, promoted into Layer A as new expectations. Textual diffs are advisory (formatting differs by design); the *signals* are the gate.

One explicitly approved, ART-verified invalid fixture has a separate **EXPECTED_DIAGNOSTIC**
contract: an exact source hash and invalid-return diagnostic are mandatory, while its failed
compilation remains visible. Missing evidence, extra failures, changed signals or silently
"successful" output fail the gate. This is neither parity nor an extension of the two existing
documented divergences. See [INVALID-BYTECODE.md](INVALID-BYTECODE.md) for the evidence and policy.

## 3. The corpus

Sources, all copied into a fenced `corpus/` tree (kept isolated for licensing clarity — see decisions):
- **210 imported `.smali`** inputs from `jadx-core/src/test/smali/**` — language-neutral, drop-in.
- **9 `.raung`** inputs.
- Binary samples (`hello.dex`, sample APKs) from `jadx-core/src/test/resources/`.
- **Three original Java fixtures** under `corpus/java/semantics`: arithmetic, loops, and floating-point comparisons. Each has an executable `check()`.
- `upstreamJavaInventory` parses all pinned integration Java sources and reports extracted-original compilation/check viability. Its first run finds 112 passing original checks; these are not decompilation coverage. See [../corpus/JAVA-SAMPLES.md](../corpus/JAVA-SAMPLES.md) for the complete denominator and visible failures.

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

- **Kover** coverage and enforced coverage thresholds are planned, not yet configured.
- CI matrix builds and runs `commonTest` on jvm + wasmJs + js for every `core:*` module (proving portability *and* correctness on all targets), then runs `tools:oracle` on JVM as the regression gate.
- The oracle scoreboard is published as a build artifact so accuracy trends are visible over time.

## Original baseline and current limits

The canonical pin is `tools/jadx-reference/baseline.properties`. The reference adapter verifies
both the exact Git HEAD and a clean working tree on every build, including cached builds. It compiles
only the original core, zip, input API, DEX and Java-input plugin sources in place, with dependencies matching
that commit, leaving the reference checkout read-only. A runtime test verifies the loaded jadx
manifest version agrees with the pin. The former Maven 1.5.6 oracle was a different baseline.
The adapter merges both input-plugin service descriptors so class/JAR support cannot silently hide
the DEX provider. Direct javac class and class-only JAR round trips verify Java input without D8;
this prepares the reference side and does not imply candidate JVM input is implemented.

`smaliScoreboard` is an enforced differential gate: after printing the full report it fails the
Gradle process for regressions, assembly/reference failures, or missing/incomplete input. CI runs it
and retains the log even on failure. `kotlinScoreboard` remains an informational compilation report;
its known failures are not suppressed or presented as production readiness.

`javaFixtureScoreboard` is an enforced source → javac → D8 → decompile → recompile →
`check()` gate for both Java and Kotlin output. Each trusted `corpus/java` source must have a
passing original check; failed compilation, a missing rebuilt check, process failure, timeout, or
false return fails the gate. Checks run in fresh JVM processes with isolated class loaders, a memory
limit and timeout. This is process isolation for trusted fixtures, not an OS sandbox for untrusted code.
Java reference and candidate outputs are also compared against the exact pinned oracle.

`AccuracySignals.executeCheck` and `KotlinAccuracySignals.executeCheck` accept an explicit original
fixture. Inputs without an original check remain **not evaluated**, never counted as semantic passes.
The smali scoreboard therefore still measures two signals. The three current source fixtures and
targeted execution tests do not establish whole-corpus semantic parity.

CI retains the reports and blocks desktop packaging and web deployment on the test and accuracy
gates. Web deployment is a reusable workflow checked out at the tested commit; manual runs must
start through the build workflow and pass the same gates. Remaining scoreboard failures and missing
whole-corpus execution/lint/ABI/coverage gates must be resolved before claiming production readiness.
Readiness measurements and remaining work are recorded in `docs/PARITY-STATUS.md`.
