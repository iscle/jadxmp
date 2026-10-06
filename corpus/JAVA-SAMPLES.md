# Embedded upstream Java samples

`tools:oracle:upstreamJavaInventory` now extracts inputs from the read-only original
jadx checkout at `0e232fb3510ec86083af0055470163d3550957cd`:

```sh
./gradlew :tools:oracle:upstreamJavaInventory
```

This is an **original-fixture viability inventory**, not a decompilation scoreboard.
The existing Java/Kotlin execution gate and smali differential gate are unchanged.

## Extraction and provenance

The JVM-only extractor uses the JDK's public Java syntax-tree API and source positions,
so braces in comments, strings and text blocks cannot truncate a sample. It supports one
public static `TestCls` class directly inside a nongeneric outer class. The sample block,
including annotations and nested members, is preserved verbatim inside a new public
outer wrapper with the original package/name. The wrapper omits the upstream harness
superclass and all other outer members. Dependencies on omitted members remain visible
as compilation failures; this is not a claim of equivalence for reflective wrapper behavior.

Explicit imports are retained when their simple names occur in the sample syntax tree;
wildcard imports are retained conservatively. The one import mapping is recorded explicitly:
`jadx.tests.api.utils.assertj.JadxAssertions.assertThat` becomes
`org.assertj.core.api.Assertions.assertThat`. AssertJ **3.27.7** matches the original
baseline's test dependency and belongs only to `tools:oracle`. Incompatible custom assertion
methods fail compilation visibly. No upstream harness implementation is copied.

Generated sources/classes live in temporary workspaces, not the clean-room engine. Every
input Java file receives a tab-separated report row containing its original SHA-256,
extracted-source SHA-256 when available, status, import mappings and compiler diagnostics.
The runner verifies the original Git revision and clean checkout before reading samples.

## Measured inventory

The 2026-10-06 run scans **612** integration Java files at the original pin. With javac
`--release 11`, an explicit AssertJ-only fixture classpath and isolated JVM checks:

| Result | Files |
| --- | ---: |
| Extracted, compiled, embedded check passed | 112 |
| Extracted and compiled, no eligible embedded check | 319 |
| Extracted, compilation failed | 12 |
| Extracted and compiled, embedded check failed | 1 |
| No direct nested TestCls | 163 |
| Unsupported TestCls form | 5 |

The failed check is `others/TestMethodParametersAttribute.java`: its upstream test requests
`-parameters`, which this initial uniform compilation profile does not supply. The failure
stays visible. Other unsupported dependencies include sibling fixture classes, Android/test
annotations, upstream implementation types, logging and custom assertion methods.

## Expanded decompilation measurement

```sh
./gradlew :tools:oracle:upstreamJavaRoundTrips
# Optional focused investigation; selected/discovered counts stay explicit:
./gradlew :tools:oracle:upstreamJavaRoundTrips -Djadxmp.upstream.filter=arith/
```

The expanded run on engine commit `121c666` retains **all 612 original rows**. It compiles
and converts each eligible original with the same javac/AssertJ profile and pinned D8 **9.1.31**,
then measures pinned reference Java, candidate Java and candidate Kotlin. All **431** eligible
originals converted to DEX. Results from 2026-10-06:

| Output | No reported errors / 431 | Recompiles / 431 | Passing rebuilt checks / 112 | Failed rebuilt checks / 112 |
| --- | ---: | ---: | ---: | ---: |
| Original pinned jadx Java | 431 | 411 | 108 | 4 |
| jadxmp Java | 401 | 289 | 67 | 45 |
| jadxmp Kotlin | 295 | 211 | 32 | 80 |

There are **143 Java cases where the reference passes a signal the candidate fails**.
These are newly exposed parity failures, not exceptions or passing parity. For example,
`arith/TestPrimitivesNegate.java` recompiles as Java but fails its embedded check; its Kotlin
output fails compilation. The 319 originals without eligible checks remain **not evaluated**
for execution. Failed original compilation/checks and unsupported extraction remain visible
outside the 431 measured outputs; they cannot become decompiler passes.

The task writes `tools/oracle/build/reports/upstream-java-roundtrips.tsv`, including every input
hash, extraction transformation, raw signal, compiler status and diagnostic. Recoverable decompiler
errors (including assertions and stack overflows) become failed signals; cancellation, interruption
and fatal VM termination propagate. The report is marked **INCOMPLETE before baseline verification**,
flushes each finished row, and becomes COMPLETE only after every selected input is recorded once.
A failed or interrupted run therefore cannot retain an old completed report as its result.

This additive measurement does not relax the existing smali or three-source execution gates.
Its successful task exit means measurement completed, **not parity or production readiness**.
CI retains the full report and run log. Only embedded checks run in time-limited child JVMs;
extraction, D8, decompilation and compilation run in-process, so interruption can leave an explicitly
incomplete report. This trusted upstream corpus workflow is not a sandbox for hostile code.

Compiler profiles beyond this uniform release-11 run, unsupported sample forms, the newly exposed
Java/Kotlin failures and broader behavioral coverage remain required work. Checks assert specific
cases and do not prove whole-program equivalence or preservation of every upstream source assertion.
