# Parity and production readiness

Measured 2026-10-06. **Full jadx parity and production readiness are not achieved.**
The current working changes improve correctness and make validation failures enforceable.

## Reference

The original checkout was cloned at jadx commit
`0e232fb3510ec86083af0055470163d3550957cd` (confirmed by its clone reflog).
The previous Maven 1.5.6 oracle did not represent that exact baseline. The oracle now compiles the
original core, ZIP, input API and DEX plugin sources in place, in `tools:jadx-reference`, without
modifying upstream or adding upstream code to the multiplatform engine. Git revision/cleanliness
checks and a loaded-version test prevent silent baseline drift.

## Changes in this iteration

- Migrated durable instructions from `CLAUDE.md` to canonical `AGENTS.md`; `CLAUDE.md` points there.
- Removed the pipeline → codegen dependency: canonical source metadata belongs to `core:ir`, while
  `CodegenKeys` remains an identity-preserving compatibility facade for emitters and callers.
- Preserved all five DEX comparison kinds through decode and CFG cloning. Java and Kotlin output
  now handle NaNs, signed zeros, and long extrema correctly. Materialized operands prevent duplicated
  calls/reads; compiler-execution tests also verify precedence and side-effect ordering.
- Fixed Kotlin Boolean-to-numeric conversion and bitwise context handling, Long shift distances,
  and exact non-null Integer/Character factory-result reconciliation. General boxing/nullability
  remains incomplete. Nine previously failing fixtures in the targeted slice now compile.
- Added CI engine tests on JVM, JS and Wasm, pinned-reference oracle tests, an enforced Java
  differential gate, and retained accuracy reports. Kotlin's broad scoreboard remains informational.
  Hosted CI was not run locally; the corresponding Gradle tasks and workflow shell logic were tested.

## Latest measurements

| Check | Result |
| --- | --- |
| Core JVM tests | 1,072 passed, zero failures/errors/skips |
| Core JS Node tests | 1,007 passed, zero failures/errors/skips |
| Core Wasm Node tests | 1,007 passed, zero failures/errors/skips |
| JVM oracle tests | 41 passed, zero failures/errors/skips |
| Java differential, 211 assembled inputs | 203 parity, 5 improvements, 1 regression, 2 existing documented divergences |
| Kotlin compilation, same 211 inputs | 72 compile; 126 compiler errors; 13 no output |
| Kotlin compilation without reported decompiler errors | 69 of 211 |

Java parity is **signal parity**, not proof that every sample compiles or behaves correctly:
202 parity cases share at least one passing signal; one is tied failure. The full Java gate correctly
returns a nonzero exit status. There are no assembly or reference failures.

For the targeted `arith,arrays,conditions` slice, Kotlin compilation improved from **10/31** to
**20/32**. The denominator increased by one for the new comparison fixture. Outputs that both compile
and report no decompiler errors improved from **8/31** to **18/32**. The original small-slice Java
measurement used the old release; the full Java numbers above use the original pinned commit.

## Blocking work

1. `trycatch/TestUnreachableCatch.smali`: the original reference passes both no-error and recompile;
   jadxmp fails both. Its nested resource/exception flow reaches the unresolved-phi fallback. Fix
   exception-aware SSA destruction and structuring with semantic tests; do not remove handlers or
   suppress the regression. CI desktop packaging is now blocked by this actual gate failure.
2. Kotlin still has 139 inputs with compiler errors or no output, plus three outputs that compile
   but carry decompiler error markers. Work remains on nullability, Java/Kotlin type projection,
   class/field reconstruction, and control-flow output. Factory casts added here are deliberately
   limited to exact known non-null factories, not a general nullable-wrapper solution.
3. `AccuracySignals.executeCheck` is still a stub. The new targeted execution tests verify the
   changes above, but the whole corpus does not yet have original-versus-rebuilt execution coverage.
4. `core:input-jvm` is planned but absent; full class/JAR input parity is not implemented.
5. ktlint, detekt, ABI validation and Kover are documented goals, not configured gates. Production
   readiness also needs broader real-APK, performance and robustness evidence.

## Reproduce

```sh
./gradlew jvmTest :tools:oracle:test jsNodeTest wasmJsNodeTest
# Intentionally exits nonzero until the remaining Java regression is fixed; continue prints Kotlin too.
./gradlew :tools:oracle:smaliScoreboard :tools:oracle:kotlinScoreboard --continue --console=plain
./gradlew :tools:oracle:smaliScoreboard -Djadxmp.smali.categories=trycatch -Djadxmp.smali.dump=TestUnreachableCatch
./gradlew :tools:oracle:kotlinScoreboard -Djadxmp.smali.categories=arith,arrays,conditions -Djadxmp.kotlin.diagnostics=true
```

Local full report: `build/reports/oracle/parity.log` (generated, not checked in).

## Independent review

A separate reviewer passed the metadata boundary repair, original-reference adapter, comparison
changes, differential gate and CI wiring. Review caught a comparison test entry-point typo and a
Kotlin Long shift-count coercion bug; both were corrected, retested and re-reviewed. The final Kotlin
review also verified Boolean bitwise context and evaluation count. Final reviews reported zero
must-fix findings in these changes. The corpus/readiness blockers above remain explicit and unresolved.
