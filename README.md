# jadxmp

**An experimental, from-scratch reimplementation of the [jadx](https://github.com/skylot/jadx) Android decompiler, written in Kotlin Multiplatform and Compose Multiplatform, targeting the desktop and the web browser.**

> ⚠️ **Experimental — not production ready.** jadxmp is under active development. Its output is measured continuously against jadx, but it does not yet match jadx across all inputs. Don't rely on it for anything important yet.

## What it is

jadxmp decompiles Android **DEX / APK** bytecode back into readable **Java** (and **Kotlin**) source. It is a clean-room redesign rather than a port: the entire decompilation engine lives in pure Kotlin `commonMain` with no JVM-only dependencies, so it compiles to **WebAssembly and runs entirely client-side in the browser** — no backend, nothing uploaded.

- **Java and Kotlin output** generated from a single shared intermediate representation.
- A **desktop app** (Compose Multiplatform, native file open) and a **web app** (in-browser decompile via Kotlin/Wasm) — the two primary targets. Mobile / tablet is planned.
- **Resource decoding** (ARSC, binary XML, `AndroidManifest.xml`) alongside code.

## Why a reimplementation?

jadx is a mature and excellent decompiler — but it is a JVM application. jadxmp exists to bring that capability to a **pure client-side web target** (Kotlin/Wasm) and a modern Compose UI, which required rethinking the engine to be free of `java.*`, reflection, threads, and service loaders in its core.

jadx is used here as a **reference oracle, not a source.** A differential test harness runs the real jadx over a test corpus and checks that jadxmp is at least as accurate. "Correct output" is defined as *"recompiles and matches the corpus,"* and it is measured on every change rather than assumed.

## Architecture at a glance

The engine is a pipeline of small, focused Gradle modules; dependencies point downward, and the UI touches the engine only through a single public facade.

| Module | Responsibility |
| --- | --- |
| `core:binary-io` | bytes, LEB128, MUTF-8, inflate, zip, filesystem (the only I/O module) |
| `core:input(-dex)` | parse DEX/APK → a normalized input model |
| `core:ir` | the intermediate representation: instructions, type lattice, region tree |
| `core:pipeline` | CFG, dominators, SSA, type inference, control-flow structuring |
| `core:codegen(-java/-kotlin)` | Java & Kotlin source backends |
| `core:resources` | ARSC + binary-XML / manifest decoding |
| `core:api` | the public facade (`Decompiler`) |
| `ui:app` | Compose Multiplatform UI |
| `desktopApp` / `webApp` | the platform shells |

Every `core:*` module compiles for JVM, JS, and Wasm.

## Building & running

Requires a recent JDK and the Android SDK (used by the DEX-parsing test tooling; point `local.properties` at it).

```bash
# Desktop app
./gradlew :desktopApp:run

# Web app — Wasm (faster, modern browsers)
./gradlew :webApp:wasmJsBrowserDevelopmentRun

# Web app — JS (slower, older browsers)
./gradlew :webApp:jsBrowserDevelopmentRun

# Run the engine tests
./gradlew allTests
```

## Status & accuracy

jadxmp performs an end-to-end decompile today and is graded continuously by a differential oracle against **jadx 1.5.6** over a branchy smali corpus. Many programs decompile to compilable, jadx-parity output; a long tail of harder control-flow structuring and code-reconstruction cases is still being closed. It is **not** yet a drop-in jadx replacement.

## Credits & license

- Reference decompiler and differential test oracle: **[jadx](https://github.com/skylot/jadx)** by Skylot, licensed under the **Apache License 2.0**. jadxmp is an independent clean-room reimplementation and does **not** incorporate jadx source code. Test inputs under `corpus/` are derived from jadx's test suite and remain subject to jadx's Apache License 2.0 — see [`NOTICE`](./NOTICE).
- jadxmp's own license is **to be determined** (all rights reserved for now).
