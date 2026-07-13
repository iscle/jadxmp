package com.jadxmp.oracle

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.nio.file.Files

/**
 * Three-way outcome of the **kotlinc** recompile signal. This is a *self-measurement* of jadxmp's own
 * Kotlin backend (does the code we emit compile?), NOT a differential vs jadx — jadx's Kotlin output is
 * experimental/absent, so there is nothing to diff against. See [KotlinAccuracySignals].
 *
 * The status mirrors [AccuracySignals]'s ERROR-vs-WARN split but keeps three explicit outcomes so the
 * scoreboard can report "compiles clean" separately from "compiles with warnings":
 * - [CLEAN] — kotlinc accepted the source with no diagnostics of WARNING severity or above.
 * - [WARNINGS] — kotlinc produced bytecode but emitted warnings (still a PASS, exactly as the Java signal
 *   ignores warnings: warnings are surfaced for triage but do not fail the recompile gate).
 * - [ERRORS] — kotlinc reported at least one error; the emitted Kotlin is wrong. A FAIL.
 * - [NO_OUTPUT] — kotlinc exited without an error but produced no `.class` for an expected class (empty or
 *   comment-only source). A FAIL — the F1 guard that stops a decompiler emitting nothing from scoring a
 *   vacuous pass, mirrored from [AccuracySignals.recompiles].
 * - [UNAVAILABLE] — the Kotlin compiler could not be invoked (embeddable not resolvable / linkage error).
 *   NOT a pass and NOT a recompile failure — the caller must treat it as a logged SKIP so a missing
 *   compiler never masquerades as either a PASS or a REGRESSION.
 */
enum class KotlinRecompileStatus { CLEAN, WARNINGS, ERRORS, NO_OUTPUT, UNAVAILABLE }

/** Outcome of the kotlinc recompile signal, with diagnostics split by severity for failure triage. */
data class KotlinRecompileResult(
    val status: KotlinRecompileStatus,
    val errors: List<String>,
    val warnings: List<String>,
) {
    /**
     * True only when kotlinc produced bytecode without errors (warnings allowed, mirroring the Java
     * signal). [KotlinRecompileStatus.UNAVAILABLE] is deliberately NOT a success — an un-invokable
     * compiler is a skip, never a false pass.
     */
    val success: Boolean get() = status == KotlinRecompileStatus.CLEAN || status == KotlinRecompileStatus.WARNINGS

    /** True when the signal could not be measured at all (compiler absent) and must be reported as a SKIP. */
    val skipped: Boolean get() = status == KotlinRecompileStatus.UNAVAILABLE
}

/**
 * The **kotlinc recompile signal** — the Kotlin twin of [AccuracySignals.recompiles].
 *
 * ## Why this is self-measurement, not a differential
 * jadx does not have a production Kotlin backend, so — unlike the Java signal, which is scored on BOTH
 * jadx and jadxmp output and compared — this signal is only ever run on **jadxmp's** Kotlin output. It
 * answers one question: *does the Kotlin we generate actually compile?* There is no reference side to
 * diff against; a REGRESSION/IMPROVEMENT verdict is not defined here. The scoreboard that consumes this
 * ([main] in `KotlinScoreboardRunner`) reports raw compiles-clean / warnings / errors counts, not
 * PARITY/REGRESSION.
 *
 * ## Compiler invocation strategy: `kotlin-compiler-embeddable` (in-process)
 * We invoke [K2JVMCompiler] from `org.jetbrains.kotlin:kotlin-compiler-embeddable` directly rather than
 * shelling out to a `kotlinc` CLI on `PATH`/`KOTLIN_HOME`. Rationale (chosen for robustness +
 * self-containment, the same reasons the Java signal uses the in-process `javax.tools` compiler rather
 * than a `javac` binary):
 * - **Hermetic & version-pinned**: the compiler is a declared build dependency pinned to the project's
 *   Kotlin version, so the signal measures the same language the engine targets — not whatever `kotlinc`
 *   a CI box happens to have (or lack). No `KOTLIN_HOME` provisioning, no PATH lookup, no process spawn.
 * - **Structured diagnostics**: a [MessageCollector] hands back each diagnostic with its
 *   [CompilerMessageSeverity], giving the clean ERROR-vs-WARNING split for free — no fragile stderr
 *   scraping of a CLI's human-readable output.
 * - **Graceful degradation, narrowly scoped**: if the embeddable jar is not on the classpath, the first
 *   reference to its types throws a [LinkageError] (`NoClassDefFoundError`) — or a [ClassNotFoundException]
 *   on a reflective load — which [recompiles] catches and turns into [KotlinRecompileStatus.UNAVAILABLE], a
 *   logged SKIP. The catch is deliberately narrow: a kotlinc *internal* crash on pathological decompiled
 *   input is NOT a skip (that would hide a real "jadxmp emits uncompilable Kotlin" failure inside the
 *   excluded-from-denominator UNAVAILABLE bucket) — it is scored [KotlinRecompileStatus.ERRORS].
 *
 * ## android.jar
 * Kotlin decompiled from Android dex references `android.*` just like the Java output does, so the caller
 * passes [AndroidSdk.recompileClasspath] as [additionalClasspath] exactly as the Java scoreboard does;
 * without it, `android.*` refs fail to resolve and the signal reports honest errors rather than a masked
 * pass.
 */
object KotlinAccuracySignals {

    /**
     * Feed every [classes] source back to the Kotlin compiler in one module and report the outcome.
     *
     * Sources are written to a throwaway temp tree (classes reference each other, so they must compile
     * together) and compiled to a temp output dir. [additionalClasspath] (android.jar + anything else)
     * plus the Kotlin standard library are put on the compile classpath; `-no-stdlib`/`-no-reflect` are
     * set and the stdlib jar located from this process's own classpath, so resolution never depends on
     * the embeddable jar's internal layout. If the stdlib jar cannot be located, the signal SKIPs
     * ([KotlinRecompileStatus.UNAVAILABLE]) rather than fabricating unresolved-symbol errors.
     *
     * Empty [classes] fails as [KotlinRecompileStatus.NO_OUTPUT] (F2: a decompiler that produced nothing
     * did not cleanly decompile). Only a [LinkageError]/[ClassNotFoundException] (compiler absent) degrades
     * to [KotlinRecompileStatus.UNAVAILABLE] (skip); a compiler-internal crash on our output is scored
     * [KotlinRecompileStatus.ERRORS], never a skip.
     */
    fun recompiles(
        classes: List<DecompiledClass>,
        additionalClasspath: List<File> = emptyList(),
    ): KotlinRecompileResult {
        if (classes.isEmpty()) {
            return KotlinRecompileResult(KotlinRecompileStatus.NO_OUTPUT, listOf("no classes to compile"), emptyList())
        }
        return catchingUnavailable { compileWithEmbeddable(classes, additionalClasspath) }
    }

    /**
     * Run [compile], mapping ONLY a compiler-absent / linkage-mismatch failure to
     * [KotlinRecompileStatus.UNAVAILABLE] (a SKIP): [LinkageError] (`NoClassDefFoundError` if the embeddable
     * jar is missing, or an API/ABI mismatch) and [ClassNotFoundException] (a reflective compiler-class load
     * failing). Every OTHER Throwable — including a kotlinc INTERNAL crash on pathological decompiled input —
     * is deliberately NOT caught here and propagates, so the scoreboard can never drop a genuine "jadxmp
     * emits uncompilable Kotlin" failure into the excluded-from-denominator UNAVAILABLE bucket (MUST-FIX 1).
     * (In the real flow such a crash is caught closer in, at [K2JVMCompiler.exec], and scored ERRORS.)
     *
     * Package-visible for test: exercised directly to prove the catch is narrow.
     */
    internal fun catchingUnavailable(compile: () -> KotlinRecompileResult): KotlinRecompileResult =
        try {
            compile()
        } catch (e: LinkageError) {
            unavailable(e)
        } catch (e: ClassNotFoundException) {
            unavailable(e)
        }

    private fun unavailable(t: Throwable): KotlinRecompileResult = KotlinRecompileResult(
        KotlinRecompileStatus.UNAVAILABLE,
        listOf("kotlin-compiler-embeddable could not be invoked: ${t::class.simpleName}: ${t.message}"),
        emptyList(),
    )

    /** The actual embeddable invocation, isolated so [recompiles]'s catch can turn a linkage error into a SKIP. */
    private fun compileWithEmbeddable(
        classes: List<DecompiledClass>,
        additionalClasspath: List<File>,
    ): KotlinRecompileResult {
        // SHOULD-FIX 3: if kotlin-stdlib is not a locatable jar (e.g. a classes-dir layout), compiling with
        // -no-stdlib would fabricate a mass "unresolved Int/String" failure across every sample. That is not
        // a real accuracy signal, so SKIP honestly (UNAVAILABLE) instead of scoring phantom errors.
        val stdlib = stdlibJar()
            ?: return KotlinRecompileResult(
                KotlinRecompileStatus.UNAVAILABLE,
                listOf(
                    "kotlin-stdlib jar not locatable on the harness classpath (classes-dir layout?); " +
                        "cannot compile with -no-stdlib without fabricating unresolved-symbol errors — skipping",
                ),
                emptyList(),
            )

        val srcDir = Files.createTempDirectory("jadxmp-oracle-kt-src").toFile()
        val outDir = Files.createTempDirectory("jadxmp-oracle-kt-out").toFile()
        try {
            val sourceFiles = classes.map { cls ->
                val pkgPath = cls.fullName.substringBeforeLast('.', "").replace('.', '/')
                val dir = if (pkgPath.isEmpty()) srcDir else srcDir.resolve(pkgPath).apply { mkdirs() }
                dir.resolve("${cls.simpleName}.kt").apply { writeText(cls.source) }
            }

            val errors = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            val collector = object : MessageCollector {
                override fun clear() = Unit
                override fun hasErrors(): Boolean = errors.isNotEmpty()
                override fun report(
                    severity: CompilerMessageSeverity,
                    message: String,
                    location: CompilerMessageSourceLocation?,
                ) {
                    val where = location?.let { "${it.path}:${it.line}: " } ?: ""
                    when {
                        severity.isError -> errors += "$where$message"
                        severity == CompilerMessageSeverity.WARNING ||
                            severity == CompilerMessageSeverity.STRONG_WARNING -> warnings += "$where$message"
                        else -> Unit // INFO / LOGGING / OUTPUT — not part of the pass/fail signal.
                    }
                }
            }

            // stdlib was located above; pass it explicitly with -no-stdlib so resolution never depends on the
            // embeddable jar's internal layout.
            val classpathEntries = additionalClasspath + stdlib
            val arguments = K2JVMCompilerArguments().apply {
                freeArgs = sourceFiles.map { it.absolutePath }
                destination = outDir.absolutePath
                classpath = classpathEntries.joinToString(File.pathSeparator) { it.absolutePath }
                noStdlib = true
                noReflect = true
                jvmTarget = "21" // match the module's JDK toolchain / the engine's Java 21 bytecode.
            }
            // MUST-FIX 1 (crash path): a compiler-absent/linkage problem is a SKIP and must reach recompiles()'
            // narrow catch, so LinkageError/ClassNotFoundException are rethrown. But a kotlinc INTERNAL crash
            // on our pathological decompiled Kotlin is a genuine failure of OUR output — score it ERRORS, never
            // swallow it as a skip.
            val exitCode = try {
                K2JVMCompiler().exec(collector, Services.EMPTY, arguments)
            } catch (e: LinkageError) {
                throw e
            } catch (e: ClassNotFoundException) {
                throw e
            } catch (t: Throwable) {
                errors += "kotlinc crashed on generated source: ${t::class.simpleName}: ${t.message}"
                ExitCode.INTERNAL_ERROR
            }

            // F1: a green exit with zero .class files is a false pass (empty / comment-only source). Require
            // every expected top-level class to have produced its <simpleName>.class, exactly as the Java
            // signal does. Missing output with no compiler error is NO_OUTPUT.
            //
            // NIT 5: a Kotlin unit of ONLY top-level functions (no class declaration) compiles to a
            // `<Name>Kt.class` file-facade, so this `<simpleName>.class` check could false-NO_OUTPUT it. Every
            // decompiled unit today carries a class declaration, so this holds; revisit once the Kotlin backend
            // lands and we see whether `fullName` encodes the JVM facade name for such units.
            val missing = classes.filter { cls ->
                val pkgPath = cls.fullName.substringBeforeLast('.', "").replace('.', '/')
                val classFile = if (pkgPath.isEmpty()) {
                    outDir.resolve("${cls.simpleName}.class")
                } else {
                    outDir.resolve(pkgPath).resolve("${cls.simpleName}.class")
                }
                !classFile.isFile
            }
            val missingMsgs = missing.map { "no .class produced for ${it.fullName} (empty or comment-only source?)" }

            // SHOULD-FIX 4: fold a non-OK ExitCode into the failure decision (defense-in-depth parity with the
            // Java twin's `compiledOk`). A non-OK exit with no captured error diagnostic still fails as ERRORS.
            val compiledOk = exitCode == ExitCode.OK
            val exitMsgs =
                if (!compiledOk && errors.isEmpty()) listOf("kotlinc exited non-OK ($exitCode) with no error diagnostic") else emptyList()

            val status = when {
                errors.isNotEmpty() -> KotlinRecompileStatus.ERRORS
                !compiledOk -> KotlinRecompileStatus.ERRORS
                missing.isNotEmpty() -> KotlinRecompileStatus.NO_OUTPUT
                warnings.isNotEmpty() -> KotlinRecompileStatus.WARNINGS
                else -> KotlinRecompileStatus.CLEAN
            }
            return KotlinRecompileResult(status, errors + exitMsgs + missingMsgs, warnings)
        } finally {
            srcDir.deleteRecursively()
            outDir.deleteRecursively()
        }
    }

    /** The kotlin-stdlib jar on this process's classpath (the jar that provides [Unit]), or null in a dev/dir layout. */
    private fun stdlibJar(): File? = try {
        val location = Unit::class.java.protectionDomain?.codeSource?.location ?: return null
        File(location.toURI()).takeIf { it.exists() }
    } catch (_: Throwable) {
        null
    }
}
