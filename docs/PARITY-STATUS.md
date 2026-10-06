# Parity and production readiness

Measured 2026-10-06. **Full jadx parity and production readiness are not achieved.**
The tracked Java differential gate now passes; substantial Kotlin and input-support work remains.

## Reference and architecture

The original checkout was cloned at jadx commit
`0e232fb3510ec86083af0055470163d3550957cd` (confirmed by its clone reflog).
The former Maven 1.5.6 oracle did not represent that exact baseline. `tools:jadx-reference` now
compiles the original core, ZIP, input API, DEX and Java-input plugin sources in place. Git revision/cleanliness
checks and a loaded-version test prevent silent baseline drift. Upstream remains read-only and
outside the clean-room multiplatform engine.

Durable instructions now live in `AGENTS.md`; `CLAUDE.md` points there. The pipeline → codegen
dependency was removed: shared source metadata belongs to `core:ir`, with identity-preserving
compatibility keys for callers. JVM verification tools remain isolated: the built desktop distribution
contains none of the oracle, reference, smali, D8, or Kotlin compiler tool jars.

## Latest combined validation (commit `5c3df54`)

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
Java regression. Hosted CI for `5c3df54` and `70659c1` completed successfully, including JVM/JS/Wasm and accuracy
gates, all four desktop platforms, and the gated web deployment. The JVM/JS/Wasm and accuracy
gates, packaging and web deployment also passed for `8e82fcd`.

## Exception-state validation after the combined snapshot

The protected-instruction SSA repair passes the full original-reference Java gate: **211 scored,
203 parity (202 evidenced, one tied failure), five improvements, zero regressions, two existing
divergences and one required invalid-input diagnostic**. The last category is explicitly approved
and hash-bound to the ART-rejected `TestTryCatchMultiException2` fixture; its failed compilation
remains visible. See [INVALID-BYTECODE.md](INVALID-BYTECODE.md). It is not output parity.

Protected throwing writes now commit their destination only on normal completion, so catch handlers
observe the pre-instruction definition. Execution tests cover failed array/call/wide writes, checked
casts, incoming parameters, loops, nested handlers and protected returns. Pipeline tests pass on
JVM, JS and Wasm; the targeted Java exception-state and result-type oracle tests pass. These checks
supplement the older combined snapshot above rather than replacing it with unrun suite totals.

## JVM-input reference preparation

The isolated adapter now includes the original Java-input plugin and its pinned raung-disasm
0.1.1 dependency. Both plugin service descriptors are merged deterministically. Three new reference
tests verify discovery of both plugins and direct class/class-only-JAR compilation and execution,
without D8 conversion. The existing DEX smoke test also passes. The new `core:input-jvm` foundation parses class envelopes, constant pools and raw attributes,
with common malformed-input tests and javac interoperability tests. It is not registered as an input
plugin yet. Straight-line primitive lowering now produces normalized register input, with real JVM
execution comparisons. Shared format-neutral nesting and JVM lexical metadata readers prepare the
class adapter; general bytecode lowering and class/JAR facade integration remain incomplete. The combined-suite
counts above precede these additional reference and parser tests.

## Kotlin validation after the combined snapshot

The latest 211-input Kotlin compilation run reports **83 compiling, 80 without decompiler error
markers, three compiling with error markers, 115 compiler errors and 13 empty outputs**. This is a
compilation measurement, not whole-corpus semantic equivalence. Relative to the array-nullability
snapshot (81 compiling, 76 unflagged), `others/TestIncorrectFieldSignature` and
`trycatch/TestFinally3` newly compile; no previously compiling input fails. Additional unflagged
gains include shared pipeline repairs and are not attributed solely to the Kotlin backend.

Reference parameters, fields, copied values and unknown external reference results now retain
nullable JVM behavior. Execution checks cover prefix/catch effects, receiver/argument order,
casts, identity, throws, monitors and Java/generated-Kotlin interface contracts. Data-class
reconstruction requires exact constructor/component/copy body proofs and compatible access/names;
static copy factories, renamed methods and nonpublic constructors remain explicit. Targeted runtime
and reflection checks verify those fallbacks. Dynamic reference-result nullability is explicit;
the earlier nonnull spelling did not reproduce a runtime failure in the compiled probe.

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

1. The known exception-SSA program-point bug is repaired and execution-tested. Broader transformation
   safety still needs work. Expression folding now preserves potentially throwing operations relative
   to calls and the evaluation order of folded arguments, including array-store operand order.
   Java/Kotlin execution tests check exception types and side-effect traces; these are not a general
   semantic proof of all transformations.
2. Kotlin has 128 inputs with compiler errors or no output, plus three compilable outputs carrying
   error markers. Broader generic/override nullability, boxed wrappers, Java varargs, class/field reconstruction and
   broader control-flow output need work. Compilation alone is insufficient evidence of correctness.
3. Most upstream Java `check()` fixtures have not been extracted. Whole-corpus original-versus-rebuilt
   execution coverage is missing; the smali gate still measures no-error and recompilation only.
4. `core:input-jvm` has parser/frame foundations, straight-line primitive normalization and lexical
   declaration metadata. General control-flow/reference lowering, remaining metadata, class adaptation
   and archive/facade integration are still required for class/JAR input support.
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
