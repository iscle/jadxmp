# Parity and production readiness

Measured 2026-10-06. **Full jadx parity and production readiness are not achieved.**
The tracked Java differential gate now passes; substantial Kotlin and input-support work remains.

## Reference and architecture

The original checkout was cloned at jadx commit
`0e232fb3510ec86083af0055470163d3550957cd` (confirmed by its clone reflog).
The former Maven 1.5.6 oracle did not represent that exact baseline. `tools:jadx-reference` now
compiles the original core, ZIP, input API and DEX plugin sources in place. Git revision/cleanliness
checks and a loaded-version test prevent silent baseline drift. Upstream remains read-only and
outside the clean-room multiplatform engine.

Durable instructions now live in `AGENTS.md`; `CLAUDE.md` points there. The pipeline → codegen
dependency was removed: shared source metadata belongs to `core:ir`, with identity-preserving
compatibility keys for callers. JVM verification tools remain isolated: the built desktop distribution
contains none of the oracle, reference, smali, D8, or Kotlin compiler tool jars.

## Latest combined validation

| Check | Result |
| --- | --- |
| Core JVM tests | 1,108 passed, zero failures/errors/skips |
| Core JS Node tests | 1,043 passed, zero failures/errors/skips |
| Core Wasm Node tests | 1,043 passed, zero failures/errors/skips |
| UI JVM tests | 387 passed, zero failures/errors/skips |
| JVM oracle tests | 64 passed, zero failures/errors/skips |
| Java differential, 211 assembled inputs | 204 parity, 5 improvements, zero regressions, 2 pre-existing documented divergences |
| Kotlin compilation, same 211 inputs | 75 compile; 123 compiler errors; 13 no output |
| Kotlin compilation without reported decompiler errors | 72 of 211 |
| Original source semantic gate | All 3 fixtures pass Java reference/candidate and Kotlin candidate checks |
| Desktop distributable | Builds locally; runtime UI behavior was not manually verified in this batch |

Java parity is **signal parity**, not proof that every sample compiles or behaves correctly:
203 parity cases share at least one passing signal; one is tied failure. The Java gate exits
successfully with zero assembly/reference failures. The two existing allowlisted cases remain visible
in its report: `TestLoopRestore3` and `TestInsnsBeforeThis`.

Compared with commit `29e21c9`, the one Java regression is fixed, Kotlin compilation improves from
72 to 75 inputs, and unflagged Kotlin compilation improves from 69 to 72. Hosted CI on the preceding
commits ran JVM/JS/Wasm tests and correctly blocked packaging and web deployment on the then-unfixed
Java regression. A hosted result for this final combined repair is still pending.

## Correctness repairs and execution evidence

- All five DEX comparison kinds retain their operand kind and NaN bias through decode and CFG
  cloning. Both emitters preserve signed zero and materialize operands to avoid repeated effects.
- Java assignments and comparisons reconcile coalesced Boolean registers with their numeric 0/1
  values. Qualified library members such as `Thread.yield()` keep their legal contextual names.
- Kotlin repairs cover primitive conversions, reference identity, JVM member projection, exact
  CharSequence dispatch, numeric promotions, boundary literals, typed null overload arguments,
  and literal-null throws. General boxed-wrapper and nullable-signature reconstruction remains open.
- Split/shared exception handlers now structure while preserving which exceptions reach each handler. Catch aliases
  preserve precise rethrow typing. Proven impossible handler edges are removed conservatively;
  unknown, potentially throwing operations and unprotected gaps keep their semantics.
- A proven null monitor outside protected ranges becomes an explicit NPE path, preserving prefix
  effects. This fixes `TestNestedSynchronize`, whose previous output silently returned. Unknown
  locks retain conservative fallback behavior.
- LEB128 decoding rejects oversized 32-bit payloads while retaining legal padding and extrema.

The semantic harness compiles trusted original Java source to DEX using the baseline's pinned D8
version, recompiles each generated language, and runs the same `check()` in fresh JVM processes.
The three source fixtures cover integer overflow/division/remainder, loops, NaNs, infinities and
signed zeros. Targeted runtime tests additionally verify overload selection, evaluation order,
reference identity, nested catches, suppression and exception identity. Missing rebuilt checks,
false returns, compiler failures, timeouts and early `System.exit(0)` cannot count as passing checks.
These are execution checks for the asserted cases, not whole-corpus equivalence proofs.

## Remaining production blockers

1. Exception SSA still has a known program-point bug: a handler can receive the block-end definition
   of a register assigned after a throwing instruction. `SsaTryCatchTest` documents the wrong current
   behavior. Correct exceptional transfer and dead-definition safety before trusting all SSA rewrites.
2. Kotlin has 136 inputs with compiler errors or no output, plus three compilable outputs carrying
   error markers. Nullable signatures, boxed wrappers, Java varargs, class/field reconstruction and
   broader control-flow output need work. Compilation alone is insufficient evidence of correctness.
3. Most upstream Java `check()` fixtures have not been extracted. Whole-corpus original-versus-rebuilt
   execution coverage is missing; the smali gate still measures no-error and recompilation only.
4. `core:input-jvm` is absent; full class/JAR input parity is not implemented.
5. ktlint, detekt, ABI validation and Kover remain planned. Broader real-APK, performance, robustness,
   GUI behavior and packaged-application validation are also required.

## Reproduce

```sh
./gradlew jvmTest :tools:oracle:test jsNodeTest wasmJsNodeTest
./gradlew :tools:oracle:javaFixtureScoreboard
./gradlew :tools:oracle:smaliScoreboard :tools:oracle:kotlinScoreboard --continue --console=plain
./gradlew :desktopApp:createDistributable
```

CI retains accuracy logs, enforces Java differential and both-language source semantic gates, and
requires JVM/JS/Wasm tests before desktop packaging or web deployment. Kotlin's broad compilation
scoreboard remains informational so its known failures remain visible.

## Independent review

Every implementation above passed an independent adversarial review with zero remaining must-fix
findings. Findings were reproduced, fixed and reviewed again, including CharSequence dispatch and
null/index ordering, narrow Kotlin comparisons, legal Java compilation-unit names, unsafe try-range
widening over direct/wrapped string loads and monitor exits, and test entry-point mistakes. The final
combined local validation passed after those reviews; remaining blockers are explicit above.
