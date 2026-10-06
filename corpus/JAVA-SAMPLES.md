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

These **112 passing original checks are not 112 decompilation passes**. Next work is profile
support and source → DEX → reference/candidate Java/Kotlin → compile/check execution, with
all new failures reported. Only the three original standalone fixtures currently belong to
`javaFixtureScoreboard`; whole-corpus execution parity is still unmeasured.
