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
  Hosted CI confirms the known differential regression fails the build; the web workflow now also
  depends on the test gates instead of deploying independently.

## Last full-corpus measurements (commit `29e21c9`)

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

## Subsequent semantic gate

The oracle now implements original-versus-rebuilt `check()` execution for Java and Kotlin using
fresh JVM processes. All three original source fixtures pass no-error, recompile and execution checks
against the pinned reference Java, candidate Java, and candidate Kotlin output. The cases cover
integer overflow/division/remainder, loops, NaNs, infinities and signed zeros. New unit tests also
ensure missing checks, false returns, compiler failures, timeouts and early `System.exit(0)` cannot
be reported as passing execution. This is targeted coverage, not whole-corpus equivalence.

Those round trips exposed and fixed Java assignment/comparison coercion for coalesced Boolean
locals; Kotlin assignment/comparison/literal repairs and JVM-member projection are being validated
in the same gate. `javaFixtureScoreboard` is required by CI. The full-corpus numbers above are
historical and must be remeasured after the current catch-flow and Kotlin batches.

## Blocking work

1. `trycatch/TestUnreachableCatch.smali`: the original reference passes both no-error and recompile;
   jadxmp failed both in the measurement above. Investigation traced the fallback to shared-handler
   structuring and catch rethrow aliases; the unresolved-phi diagnostic was misleading. A repair passes
   the targeted try/catch gate but still requires full-corpus validation and independent review.
   Preserve every potentially reachable handler and exception-path behavior.
2. Kotlin still has 139 inputs with compiler errors or no output, plus three outputs that compile
   but carry decompiler error markers. Work remains on nullability, Java/Kotlin type projection,
   class/field reconstruction, and control-flow output. Factory casts added here are deliberately
   limited to exact known non-null factories, not a general nullable-wrapper solution.
3. Whole-corpus original-versus-rebuilt execution coverage is still missing. The new execution
   infrastructure and three source fixtures establish a working gate; most upstream Java checks
   have not yet been extracted and integrated.
4. `core:input-jvm` is planned but absent; full class/JAR input parity is not implemented.
5. ktlint, detekt, ABI validation and Kover are documented goals, not configured gates. Production
   readiness also needs broader real-APK, performance and robustness evidence.

## Reproduce

```sh
./gradlew jvmTest :tools:oracle:test jsNodeTest wasmJsNodeTest
./gradlew :tools:oracle:javaFixtureScoreboard
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
