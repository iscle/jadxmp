# Parity and production readiness

Measured 2026-10-06. **Full jadx parity and production readiness are not achieved.**
The tracked Java differential gate now passes; substantial Kotlin and input-support work remains.

## Expanded original-source round trips (engine `30fbba0`)

All 612 pinned integration Java files now receive a result row. Of 431 eligible compiled
originals, reference Java recompiles 411, candidate Java 289, and candidate Kotlin 212.
Of 112 passing original checks, rebuilt reference Java passes 108, candidate Java 69,
and candidate Kotlin 33. The other 319 compiled originals have no evaluated execution check.
All original extraction, compilation and check failures remain in the report.

This still exposes **141 Java reference-better cases beyond the existing smali gate**. Those failures
remain production blockers. The additive reporting task records them without changing the
existing gates or declaring parity; CI keeps its full evidence. See
[../corpus/JAVA-SAMPLES.md](../corpus/JAVA-SAMPLES.md) for the profile, limits and reproduction.

The primitive-array constant repair raises Java check passes from 67 to 69, Kotlin check passes
from 32 to 33, and Kotlin compilations from 211 to 212, with no measured signal losses.
`TestPrimitivesNegate` now passes in both languages; `TestFloatValue` gains Java execution while
its Kotlin output still fails on unavailable external generic declaration information. Shared raw
constants receive independent typed array-store literals, preserving float/int and double/long
bit interpretations. Equal-bit acyclic phi paths and copies are covered; general mixed primitive
SSA views remain incomplete. Independent tests cover all five pipeline targets and seven runtime
cases, including negative zero, null/bounds failures and reference-array store exceptions.

## Explicit external metadata profile (engine `a6b9d40`)

The separate opt-in `fixture-classpath-v1` run also covers all **612 inputs**. With the validated
AssertJ declaration catalog supplied only to candidate Kotlin, **219 of 431 eligible outputs compile
and 40 of 112 rebuilt checks pass**, compared with **212 compilations and 33 passing checks** in the
recorded metadata-free profile above. No measured signals are lost. All input/extracted-source hashes,
original/preparation classifications, reference Java signals and candidate Java signals match that
metadata-free report; the 319 compiled originals without checks remain not evaluated.

Both compilation and execution improve for these seven original fixtures:
`arrays/TestArrayFillNegative`, `code/TestArrayAccessReorder`, `others/TestDeboxing`,
`others/TestFieldInitOrder`, `others/TestFloatValue`, `trycatch/TestLoopInTryCatch2` and
`trycatch/TestTryCatchInIf`. Raw external types such as `AbstractFloatAssert<*>` now have the arity
required by Kotlin. Bare constructor/static/class-literal names and generated erased declarations stay
unchanged. This does not enable source-generic or bridge reconstruction.

The profile reads the same `assertj-core-3.27.7.jar` used by original/rebuilt compilation: SHA-256
`c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f` (1,399,383 bytes).
It admits 839 of 842 declarations; six recorded diagnostics exclude three unsupported helper classes,
and the versioned module descriptor is explicitly skipped. Every artifact, admission diagnostic and
skip is retained in the report. See [GENERIC-METADATA.md](GENERIC-METADATA.md) for limits and reproduction.

The default remains **metadata-free**. These are profile-assisted gains, not a replacement of the
metadata-free totals or the enforced gates. Independent validation passed 203 Kotlin backend tests on
each of JVM, JS Node/browser and Wasm Node/browser, plus 15 focused oracle tests. The full owner suite
passed 159 oracle tests, both enforced Java gates with zero regressions, and the unchanged default
Kotlin smali result of 83/209 compiling and 80 unflagged.

## Expanded measurement after native array reads (engine `20014ae`)

Hosted CI measured all 612 original-source rows with `metadata_profile=none`. Candidate Java
now compiles **292 of 431** eligible inputs, up from 289; **138 reference-better Java cases** remain,
down from 141. The new compilations are `TestNullInline`, `TestWrongCode2` and
`TestTypeResolver24`. The pure-null throw repair included with native array reads makes these
outputs legal Java. None has an evaluated original execution check, so these gains establish
compilation only. All 612 provenance, extraction, preparation and transformation records are
unchanged; every other reference/Java/Kotlin signal matches the preceding `141ca21` report.
Java execution remains 69/112 passing original checks; Kotlin remains 212/431 compilations
and 33/112 passing checks. Full parity and production readiness remain unachieved.

## Expanded Kotlin inheritance measurement (engine `cb02fc1`)

Hosted CI again completed all 612 rows with `metadata_profile=none`. Kotlin compiles
**228 of 431** eligible inputs, up from 212, and passes **37 of 112** original checks, up from 33.
The four new execution passes are `TestDefConstructorNotRemoved`, `TestFieldInit3`,
`TestOverridePrivateMethod` and `TestOverrideStaticMethod`. The other twelve new compilations
have no evaluated original check. Five additional outputs lose an unsupported diagnostic but
still fail compilation; those are not compilation or execution gains. Kotlin's no-error total
rises from 295 to 312, with no measured signal losses.

All 612 input/extraction hashes, classifications, preparation and transformation records match
`20014ae`, as do every reference and candidate Java signal. Java remains at 292 compilations,
69 passing checks and 138 reference-better cases. This is a metadata-free measurement of the
array-store and plain-inheritance batch; it precedes the later allocation, null-throw and overload
fixes. It neither combines the opt-in metadata profile above nor declares whole-corpus parity.

## Source overload binding

Shared source binding now retains exact loaded overload descriptors when source arguments have
narrower reference types, with bounded per-output analysis. The pinned original overload check
and targeted execution matrices compare both languages against original behavior. A separate
missing-parent control repairs Java's failure signal but records Kotlin's pre-existing linkage
limitation explicitly; it is not counted as parity. See [INVOCATION-BINDING.md](INVOCATION-BINDING.md).
These targeted checks do not replace the full-corpus counts above with an unmeasured new total.

## Latest fixture-context and forwarding validation

The smali gate covers **211 physical sources in 209 fixture contexts**: **201 parity
(200 with a shared passing signal, one tied failure), five improvements, zero regressions,
two existing divergences and one required invalid-input diagnostic**. There are zero assembly
or reference failures. Kotlin compiles **83 of 209 contexts**, including **80 without reported
or context errors**, three flagged compilations, 113 compiler failures and 13 empty outputs.
These counts use a different denominator from the historical per-file snapshots below.

The original pinned `TestMethodInline` test loads A, B and C together. The harness now assembles
that exact, hash-verified group for both decompilers, compiles every output and requires all three
top-level classes. Every other source remains a singleton context. Both reports retain standalone
A/B/C measurements and compiler diagnostics: isolated B still fails candidate compilation because
C is absent. No dependency stub or new exception hides that result. The complete original context
also passes execution checks for reference Java, candidate Java and candidate Kotlin.

Synthetic forwarding now preserves declaring-class initialization and access boundaries. Calls
can simplify only to resolved public static targets in the same declaring class. Changing an inherited
symbolic owner also requires public declaring/enclosing classes. Forwarding declarations remain
available to inherited and unseen callers. Runtime tests
cover parent/default-interface/owner/target initialization order, failed initialization and retries,
inherited references and private accessors. These checks do not prove general reflection,
method-handle or stack-trace equivalence for method reconstruction.

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

A concrete method whose body cannot be loaded now retains its error diagnostic and emits a throwing
fallback instead of an empty body. Failed constructors and class initializers cannot silently return
successfully; healthy sibling methods remain usable where the partial class recompiles. These
flagged fallbacks are diagnostics, not recovered behavior or semantic parity. Existing errored bodies
are retained, including the required invalid-return fixture and its failed compilation.

## JVM-input reference preparation

The isolated adapter now includes the original Java-input plugin and its pinned raung-disasm
0.1.1 dependency. Both plugin service descriptors are merged deterministically. Three new reference
tests verify discovery of both plugins and direct class/class-only-JAR compilation and execution,
without D8 conversion. The existing DEX smoke test also passes. The new `core:input-jvm` foundation parses class envelopes, constant pools and raw attributes,
with common malformed-input tests and javac interoperability tests. `JvmInput.loadClass` now exposes
a class-only adapter usable through an explicitly supplied facade plugin. Primitive arithmetic,
branches, loops and switches pass native javac bytes through the complete pipeline and both emitters,
then recompile and execute numeric edge cases without D8. The adapter retains lexical metadata, typed
field constants and lazy per-method failure isolation; unsupported semantic attributes are diagnosed
instead of silently dropped. Independent tests pass on JVM/JS/Wasm. General bytecode lowering, archive
loading remains incomplete. Default registry integration now recognizes single native class files. The combined-suite counts above precede
these additional reference and native-input tests.

## Kotlin validation after the combined snapshot

The latest 211-input Kotlin compilation run reports **84 compiling, 81 without decompiler error
markers, three compiling with error markers, 114 compiler errors and 13 empty outputs**. This is a
compilation measurement, not whole-corpus semantic equivalence. The boxed-reference batch newly
compiles `invoke/TestConstructorWithMoves.smali`, with no previously compiling input lost. The
preceding reference-nullability batch gained `others/TestIncorrectFieldSignature` and
`trycatch/TestFinally3`; additional unflagged gains include shared pipeline repairs.

All eight JVM wrapper types retain reference declarations, constructors and identity. External Java
wrapper parameters/results and arrays use nullable Kotlin boundary casts without reboxing or array
copies; generated declarations keep their exact reference types. Exact numeric accessor projection
preserves virtual dispatch. Independent JVM/JS/Wasm backend tests and three compiled execution
matrices cover nulls, overloads, constructors, fields, arrays, custom Number dispatch and import
collisions. These checks supplement the corpus compilation measurement.

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
   semantic proof of all transformations. Synchronized synthetic forwarders now retain their monitors,
   and the initialization/access repair above retains unsafe forwarding calls and all forwarding
   declarations. Broader method reconstruction still requires semantic proofs.
2. Kotlin has 126 fixture contexts with compiler errors or no output, plus three compilable outputs carrying
   error markers. Broader generic/override nullability, Java varargs, class/field reconstruction and
   broader control-flow output need work. Compilation alone is insufficient evidence of correctness.
3. Expanded upstream round trips expose 141 Java reference-better cases, with only 69 Java and
   33 Kotlin passing rebuilt checks out of 112 passing originals at engine `30fbba0`. These failures,
   unsupported extractions and compiler profiles remain unresolved. The smali gate still measures
   no-error and recompilation only.
4. `core:input-jvm` has parser/frame foundations, primitive normal-flow lowering, lexical declaration
   metadata and typed field constants. Reference/exception lowering, constructors, remaining metadata,
   archive loading is still required for full class/JAR support; single-class default registration is implemented.
   Kotlin now preserves instance/static method monitor identity and volatile/transient backing-field
   flags in targeted runtime tests, including exceptional monitor release and serialization. Synthetic
   enum modifier combinations that Kotlin cannot regenerate remain explicit errors; broader modifier
   and JVM ABI preservation still need coverage.
5. ktlint, detekt, ABI validation and Kover remain planned. Broader real-APK, performance, robustness,
   GUI behavior and packaged-application validation are also required.
6. Declaration annotations, generic signatures and other source metadata need end-to-end
   reconstruction. The input SPI carries annotations, but ModelBuilder currently consumes them only
   for legacy DEX enclosure; a successfully compiled output does not prove metadata preservation.

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

## Upstream Java fixture inventory

The JVM-only `upstreamJavaInventory` now parses the original pinned integration sources,
preserves embedded input classes and checks, and reports every file with source hashes.
Of 612 files, 112 extracted originals pass their embedded checks, 319 compile without an
eligible check, 12 fail compilation, one fails its check, 163 lack a direct TestCls and five
have unsupported sample forms. The check failure requires an upstream compiler profile
(`-parameters`) not yet implemented by this inventory. No failures are omitted.

The subsequent expanded measurement above runs these eligible originals through both decompilers
and both candidate languages. Existing parity gates remain unchanged; new failures remain visible.
See [../corpus/JAVA-SAMPLES.md](../corpus/JAVA-SAMPLES.md) for extraction transformations,
assertion dependency isolation and remaining work.

## Root-pass lifecycle validation

Registered root passes now execute once per load, before the deobfuscation alias snapshot
and before lazy class processing. Previously the facade registered these passes but never
called them. Root failures remain visible in diagnostics and count once in both sequential
and parallel aggregate results, including empty models. Cancelled root preparation exposes
neither the prior model nor a partially prepared replacement; both cancellation types propagate.

Lifecycle checks cover reloads, repeated cached renders, output-format changes, ordering,
failure continuation and cancellation identity. The four missing-body runtime tests now also
assert that their parameter-name collision setup actually ran; all pass with that setup active.
The isolated API/pipeline JVM, JavaScript and Wasm tests, existing three-source execution gate
and 211-input Java differential gate pass. Independent review completed with no remaining
must-fix findings. These checks do not establish whole-project production readiness.

Input loading now propagates both coroutine and pipeline cancellation unchanged, including
cancellation from plugin recognition, lazy loader probes and raw descriptor indexing. Input
indexes, resources, the prepared model and aliases are published together only after preparation
succeeds. Cancellation during model or root preparation leaves class, member, smali and resource
views empty; a later healthy load succeeds. Common tests cover replacing an already loaded input,
exception identity and ordinary parser failures retaining their diagnostics. This is a load-stage
guarantee; it does not claim cancellation coverage for every parser or rendering operation.
