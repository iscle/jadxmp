# Extraction plan — jadx's embedded Java `TestCls` samples

jadx's richest accuracy inputs are **not** loose files: they are Java classes
embedded inside its JUnit integration tests under
`reference/jadx/jadx-core/src/test/java/jadx/tests/integration/**`. Each test file
holds one uniform inner class:

```java
public class TestBreakInLoop extends IntegrationTest {
    public static class TestCls {          //  <-- the sample under decompilation
        public int f;
        public void test(int[] a, int b) { ... }
    }

    @Test
    public void test() {                   //  <-- the expectation (assertions)
        JadxAssertions.assertThat(getClassNode(TestCls.class))
            .code()
            .containsOne("for (int i = 0; i < a.length; i++) {")
            ...;
    }
}
```

At import time this checkout contains:

- **454** files with a `public static class TestCls` block (the samples).
- **120** of those also define a `public void check()` method — the executable
  round-trip oracle: `check()` must pass on both the original compiled class and
  the decompiled-then-recompiled class (accuracy signal 3, "executes identically").

These are **not** copied into `corpus/` yet, because a raw `.java` snippet is not a
decompiler input — it must be compiled to `.class`/`.dex` first, and neither this
corpus nor the multiplatform `commonTest` sources can run `javac`. Compilation is
owned by **`tools:oracle`** (JVM-only), which already has the in-process JDK
compiler wired up.

## Planned mechanical extraction (owned by `tools:oracle`)

Do this once a JVM compile helper lands (`tools:oracle` already has `javax.tools`):

1. **Walk** `reference/jadx/jadx-core/src/test/java/jadx/tests/integration/**`.
2. For each file, **extract** the `public static class TestCls { … }` block. It is
   uniform (always that exact declaration), so a brace-matched slice from the
   `public static class TestCls` token to its closing brace is sufficient; no full
   Java parser is needed. Preserve any nested `check()` method verbatim.
3. **Wrap** the block into a compilable top-level unit: emit
   `class TestCls { … }` (optionally under a per-sample package derived from the
   jadx test package) into a temp source tree. Carry over the small set of
   imports each sample uses (most use none; a few use `java.util.*`).
4. **Compile** to `.class` with `javax.tools.ToolProvider.getSystemJavaCompiler()`
   (the same in-memory compiler the recompile signal uses).
5. **Dex** the `.class` (via jadx's own dex-input path is not needed for input;
   for a `.dex` fixture, use `d8`/`dx` if available, else keep the `.class` and let
   the JVM front-end consume it). Store the resulting binary under a new
   `corpus/java-fixtures/<group>/<Name>.{class,dex}` tree.
6. **Capture the expectation**: the sample's `@Test` body is the human-authored
   accuracy expectation. Where a sample carries `check()`, also emit a tiny runner
   descriptor so the oracle can invoke `check()` on both originals and rebuilds.

## Why deferred

- Keeps this corpus purely declarative (inputs only, no build step here).
- Compilation policy (JDK level, `-parameters`, debug info on/off) belongs with the
  oracle so it matches jadx's own fixture generation and stays reproducible.
- The 120 `check()`-bearing samples are the highest-value slice; extract those
  first when the helper lands, then the remaining 334.

Until then, the directly-usable binaries live in `corpus/binary/`, and the smali /
raung trees cover the language-neutral construct matrix.
</content>
