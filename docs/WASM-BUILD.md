# Kotlin/Wasm incremental build workaround

`gradle.properties` disables Kotlin/Wasm incremental compilation with
`kotlin.incremental.wasm=false`. Kotlin 2.4.0 can fail while linking changed test
executables after a standard-library call disappears. This matches
[KT-85270](https://youtrack.jetbrains.com/issue/KT-85270). Kotlin documents this
[Wasm-specific switch](https://kotlinlang.org/docs/wasm-configuration.html#kotlin-wasm-incremental-compilation).
Gradle build/configuration caches, JVM and JS incremental compilation, and all
Wasm tests remain enabled. Wasm rebuilds may take longer.

## Reproduction and evidence

The failure occurred in `core:codegen:compileTestDevelopmentExecutableKotlinWasmJs`
after source changes, with `NoSuchElementException` at
`DefinedDeclarationsResolver.resolve:55` for an `ic#69:kotlin.text/indexOf` key
(a later occurrence referenced `replace`). An isolated project with Kotlin 2.4.0,
Gradle 9.1.0, `wasmJs { nodejs() }`, and `kotlin("test")` reproduces the same crash:

1. Put a `kotlin.test.Test` in `commonTest` that calls
   `assertEquals(0, "x".indexOf('x'))`.
2. Run `./gradlew clean compileTestDevelopmentExecutableKotlinWasmJs
   -Pkotlin.incremental.wasm=true --no-build-cache --no-configuration-cache`.
3. Replace the assertion with `assertEquals(0, 0)` and repeat the command **without
   `clean`**. The linker fails with the missing `indexOf` key.
4. Set `-Pkotlin.incremental.wasm=false` and rebuild. Restore and remove the call
   repeatedly, without cleaning; compilation succeeds. Run `wasmJsNodeTest` as
   an execution control.

Disabling the Gradle caches in both failing runs isolates the Kotlin/Wasm
incremental linker as the failing layer. An unchanged retry can also succeed;
neither a successful retry nor a clean build proves the defect is fixed. Do not
add automatic retries that conceal a failed build.

## Removing the workaround

After a Kotlin upgrade, run the restore/remove sequence above with Wasm
incremental compilation enabled, preserving each command's exit status. Also
run changed-source `:core:codegen:allTests` and the full engine Wasm tests without
cleaning between edits. Remove the property only when these checks pass and the
upstream fix is verified. Keep the original jadx oracle pin unchanged.
