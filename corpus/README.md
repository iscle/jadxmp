# corpus/ — the fenced, jadx-derived accuracy corpus

This directory holds **test inputs**, not code. It is deliberately kept as a
top-level, non-Gradle tree (it is *not* a module and is never on any app's
runtime classpath) so that its licensing provenance stays isolated and obvious.

## Provenance

Imported inputs here are **derived from [skylot/jadx](https://github.com/skylot/jadx)**,
which is licensed under the **Apache License 2.0**. The files were copied verbatim
from `reference/jadx/jadx-core/src/test/` (the read-only design/test oracle checked
out under `reference/`). They are language-neutral decompiler *inputs* (Dalvik
smali, JVM-bytecode raung, and compiled `.dex`/`.apk` binaries) — not jadx source
code. No jadx Java source is copied into `com.jadxmp.*`.

Because these are Apache-2.0 licensed third-party artifacts, they are fenced here
rather than intermingled with the clean-room engine sources. Keep it that way:

- Do **not** move these files into `core:*`/`ui:*`/`tools:*` source trees.
- Do **not** copy jadx's Java *test-harness* source (`IntegrationTest`,
  `JadxCodeAssertions`, …) in here. jadxmp reimplements the assertion *style* from
  scratch in `core:test-support`; only the neutral *inputs* live in this corpus.
- New jadxmp-authored samples (regression fixtures for bugs we fix) are welcome,
  but label them clearly as jadxmp-original in `MANIFEST.md`.

Original jadxmp regression fixtures are identified in `MANIFEST.md`, including the Java sources.

## Layout

| Path | Contents |
|---|---|
| `smali/` | Dalvik smali sources, grouped by construct (`loops/`, `trycatch/`, …). Drop-in inputs for the DEX front-end once `core:input-dex` can assemble smali or once they are pre-assembled to `.dex`. |
| `java/` | Original trusted Java source fixtures compiled to DEX at runtime by the JVM oracle; both generated languages must pass their original `check()`. |
| `raung/` | JVM-bytecode ("raung") sources — inputs for the `.class`/JVM front-end. |
| `binary/` | Ready-to-run compiled binaries: `hello.dex` (single `HelloWorld` class) and `app-with-fake-dex.apk` (small APK with resources). These are consumed directly by `tools:oracle` today. |

## Consumers

- **`tools:oracle`** (JVM-only) assembles smali and compares the pinned reference and
  candidate output. It also compiles `java/` with javac/D8 and executes semantic round trips.
  JVM/raung front-end coverage is still pending.
- **`core:*` `commonTest`** consumes small pre-compiled `.dex`/`.class` fixtures
  produced from this corpus (multiplatform tests cannot run an assembler/compiler,
  so binaries are checked in).

See `MANIFEST.md` for the catalogue and `JAVA-SAMPLES.md` for the plan to extract
and pre-compile jadx's ~454 embedded Java `TestCls` samples.
