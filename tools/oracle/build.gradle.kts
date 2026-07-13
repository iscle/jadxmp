// tools:oracle — the differential accuracy harness (JVM-only, never shipped to an app).
//
// Runs reference jadx (from Maven Central) as the accuracy oracle over the shared corpus,
// scores the three accuracy signals (no-error / recompiles / executes-check), and — once
// core:api exists — diffs jadxmp output against it as PARITY / REGRESSION / IMPROVEMENT.
//
// This is a plain kotlin("jvm") module ON PURPOSE: it uses javax.tools (the in-process JDK
// compiler) and the jadx JVM libraries, neither of which exist on wasm/js. It is test-scope
// only and must never appear on an app's runtime classpath. See docs/TESTING-ORACLE.md.
plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    // A JDK toolchain (not a JRE) is required at runtime: the "recompiles" signal calls
    // javax.tools.ToolProvider.getSystemJavaCompiler(), which is null on a JRE. Pinned to 21 to match
    // the engine's default `jvm()` bytecode (core:api and its deps compile to Java 21 class files),
    // which this module loads at runtime through core:api.
    jvmToolchain(21)
}

// jadx reference oracle — pinned to the latest stable release on Maven Central.
// Declared directly here (not in the shared multiplatform version catalog) because these are
// JVM-only libraries that never participate in a wasm/js build; keeping them out of libs keeps
// the engine catalog wasm-clean.
val jadxVersion = "1.5.6"
val smaliVersion = "3.0.9"
// The kotlinc recompile signal is invoked in-process via kotlin-compiler-embeddable, pinned to the SAME
// Kotlin version the engine targets (libs.versions.kotlin) so the signal measures the language jadxmp
// actually generates for. Declared here (not in libs.versions.toml) for the same documented reason as the
// jadx/smali libs above: it is a JVM-only tool dependency that must never reach a wasm/js engine build,
// and keeping it out of the shared catalog keeps that catalog wasm-clean (CLAUDE.md rule 1).
// Version is read from the catalog's `kotlin` entry so the signal can never silently desync from the
// engine's Kotlin version on a bump (SHOULD-FIX 2). Read via the VersionCatalogsExtension API rather than
// the `libs.versions.kotlin` type-safe accessor, which does not resolve in this project's script scope.
val kotlinCompilerVersion =
    extensions.getByType<VersionCatalogsExtension>().named("libs").findVersion("kotlin").get().requiredVersion

dependencies {
    // (a) reference decompiler + (its) dex/apk front-end.
    implementation("io.github.skylot:jadx-core:$jadxVersion")
    implementation("io.github.skylot:jadx-dex-input:$jadxVersion")

    // (b) the jadxmp candidate under test — the whole point of the differential harness.
    implementation(projects.core.api)

    // (c) smali assembler — turns the corpus/smali/** tree into dex fixtures for the differential run.
    // Same version (and default API level 27) jadx itself uses, so our dex matches jadx's smali tests.
    // guava is pinned to the version smali expects (jadx does the same) to avoid a stale transitive one.
    implementation("com.android.tools.smali:smali:$smaliVersion")
    implementation("com.google.guava:guava:33.6.0-jre")

    // (d) Kotlin compiler for the kotlinc recompile signal (the Kotlin twin of the javax.tools Java signal).
    // In-process K2JVMCompiler → hermetic, version-pinned, structured diagnostics. JVM-only, tool-scope.
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinCompilerVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Run the differential accuracy scoreboard (reference jadx vs the jadxmp candidate) over
// corpus/binary. Needs a JDK (the recompile signal uses javax.tools).
tasks.register<JavaExec>("scoreboard") {
    group = "verification"
    description = "Print the PARITY/REGRESSION/IMPROVEMENT scoreboard over corpus/binary."
    mainClass.set("com.jadxmp.oracle.OracleRunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    // Accuracy must be measured against a FRESHLY-BUILT candidate: a stale core:api / binary-io jar
    // once produced a phantom REGRESSION. Always re-run (never serve an up-to-date cache), and opt out
    // of the configuration cache so the runtime classpath is recomputed from a current build each time.
    outputs.upToDateWhen { false }
    notCompatibleWithConfigurationCache("accuracy scoreboard must run against a freshly-built candidate")
}

// Assemble corpus/smali/** to dex and run the branchy jadx-vs-jadxmp differential — the Phase-3
// control-flow accuracy measurement. Restrict to categories with:
//   ./gradlew :tools:oracle:smaliScoreboard -Djadxmp.smali.categories=conditions,loops,switches,trycatch
tasks.register<JavaExec>("smaliScoreboard") {
    group = "verification"
    description = "Assemble corpus/smali to dex and print the jadx-vs-jadxmp differential scoreboard."
    mainClass.set("com.jadxmp.oracle.SmaliScoreboardRunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    // Forward an optional category filter / sample dump selector from the Gradle JVM into the run JVM.
    System.getProperty("jadxmp.smali.categories")?.let { systemProperty("jadxmp.smali.categories", it) }
    System.getProperty("jadxmp.smali.dump")?.let { systemProperty("jadxmp.smali.dump", it) }
    // Same freshness discipline as `scoreboard`: always re-run against a freshly-built candidate.
    outputs.upToDateWhen { false }
    notCompatibleWithConfigurationCache("accuracy scoreboard must run against a freshly-built candidate")
}

// Assemble corpus/smali/** to dex and run jadxmp's KOTLIN output through the kotlinc recompile signal.
// SELF-measurement only (does jadxmp's Kotlin compile?) — NOT a differential vs jadx, which has no
// production Kotlin backend. Dormant (logs a single SKIP) until core:api exposes OutputFormat.KOTLIN; see
// KotlinScoreboardRunner / KotlinJadxmpDecompiler. Restrict to categories with:
//   ./gradlew :tools:oracle:kotlinScoreboard -Djadxmp.smali.categories=conditions,loops
tasks.register<JavaExec>("kotlinScoreboard") {
    group = "verification"
    description = "Assemble corpus/smali to dex and print the jadxmp Kotlin-output kotlinc-recompile self-scoreboard."
    mainClass.set("com.jadxmp.oracle.KotlinScoreboardRunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    System.getProperty("jadxmp.smali.categories")?.let { systemProperty("jadxmp.smali.categories", it) }
    // Same freshness discipline: always re-run against a freshly-built candidate.
    outputs.upToDateWhen { false }
    notCompatibleWithConfigurationCache("accuracy scoreboard must run against a freshly-built candidate")
}
