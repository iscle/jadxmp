package com.jadxmp.oracle

import java.nio.file.Files
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider

/** Outcome of the in-process recompile signal, with diagnostics for failure triage. */
data class RecompileResult(val success: Boolean, val diagnostics: List<String>)

/** Outcome of the (currently stubbed) execute-`check()` round-trip signal. */
sealed interface ExecuteCheckResult {
    /** The signal was not evaluated (no compiled original / no `check()` / not yet implemented). */
    data object NotEvaluated : ExecuteCheckResult

    /** `check()` ran on both original and decompiled-then-recompiled class with the given verdict. */
    data class Evaluated(val passed: Boolean) : ExecuteCheckResult
}

/**
 * The three **accuracy signals**, reused verbatim from jadx's own validation and independent of any
 * decompiler's internals, so they score reference and jadxmp output identically:
 *
 * 1. [noErrors] — output carries no `JADX ERROR` / `inconsistent` markers (the decompiler didn't give up).
 * 2. [recompiles] — the emitted Java is accepted by the in-process JDK compiler (semantic-adjacent).
 * 3. [executeCheck] — a sample's embedded `check()` passes on original *and* rebuilt class (the gold standard).
 *
 * All three take a [DecompiledClass] list (or one class) so they can score any [Decompiler]'s output.
 */
object AccuracySignals {

    /**
     * Signal 1 — no error [markers] in any class's source.
     *
     * **Empty output fails** (F2): a decompiler that produced zero classes did not cleanly decompile
     * the sample — treating that as a vacuous `all {}` pass would let a total decompilation failure be
     * scored as a clean no-error (and then mis-classified as PARITY). The [markers] are decompiler-
     * specific (see [ErrorMarkers] / [Decompiler.errorMarkers]); jadx's literal sentinels never appear
     * in jadxmp's clean-room output, so each side must scan its own set (F4).
     */
    fun noErrors(classes: List<DecompiledClass>, markers: List<String>): Boolean {
        if (classes.isEmpty()) return false
        return classes.all { cls -> markers.none { cls.source.contains(it) } }
    }

    /**
     * Signal 1 over a whole [result]: combines the decompiler's own **structured** error count
     * (`reportedErrors` — jadx's `getErrorsCount`, jadxmp's summed `ClassMetadata.errorCount` incl.
     * RenderabilityGuard-flagged methods) with the [text scan][noErrors]. Both must be clean. This is
     * how the candidate side gets a real, clean-room error signal (finding F4) rather than relying only
     * on jadx's literal marker strings, which jadxmp never emits.
     */
    fun noErrors(result: DecompilationResult, markers: List<String>): Boolean =
        result.reportedErrors == 0 && noErrors(result.classes, markers)

    /**
     * Signal 2 — feed all classes back to the JDK compiler in one unit and see if it accepts them.
     *
     * Sources are written to a throwaway temp source tree (classes reference each other, so they
     * must compile together) and compiled to a temp output dir. The current classpath is used, so
     * only standard-library references resolve; output referencing e.g. `android.*` will legitimately
     * fail here until an android.jar is added to [additionalClasspath] — that failure is a true signal,
     * not a harness bug.
     *
     * **A green exit is not enough** (F1): empty or comment-only Java compiles with exit 0 while
     * producing ZERO `.class` files, so a decompiler that emitted nothing would score a false PASS.
     * We therefore additionally require every expected top-level class to have produced its
     * `<simpleName>.class` in the output dir; a missing one fails the signal.
     *
     * Requires a **JDK** at runtime (`ToolProvider.getSystemJavaCompiler()` is null on a JRE); the
     * module pins a JDK toolchain for exactly this reason.
     */
    fun recompiles(classes: List<DecompiledClass>, additionalClasspath: List<java.io.File> = emptyList()): RecompileResult {
        if (classes.isEmpty()) return RecompileResult(false, listOf("no classes to compile"))
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: return RecompileResult(false, listOf("No system Java compiler available (need a JDK, not a JRE)"))

        val srcDir = Files.createTempDirectory("jadxmp-oracle-src").toFile()
        val outDir = Files.createTempDirectory("jadxmp-oracle-out").toFile()
        try {
            val sourceFiles = classes.map { cls ->
                val pkgPath = cls.fullName.substringBeforeLast('.', "").replace('.', '/')
                val dir = if (pkgPath.isEmpty()) srcDir else srcDir.resolve(pkgPath).apply { mkdirs() }
                dir.resolve("${cls.simpleName}.java").apply { writeText(cls.source) }
            }

            val diagnostics = DiagnosticCollector<JavaFileObject>()
            compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { fm ->
                fm.setLocation(StandardLocation.CLASS_OUTPUT, listOf(outDir))
                if (additionalClasspath.isNotEmpty()) {
                    fm.setLocation(StandardLocation.CLASS_PATH, additionalClasspath)
                }
                val units = fm.getJavaFileObjectsFromFiles(sourceFiles)
                val task = compiler.getTask(null, fm, diagnostics, listOf("-proc:none"), null, units)
                val compiledOk = task.call()
                val messages = diagnostics.diagnostics
                    .filter { it.kind == javax.tools.Diagnostic.Kind.ERROR }
                    .map { "${it.source?.name ?: "?"}:${it.lineNumber}: ${it.getMessage(null)}" }
                    .toMutableList()

                // F1: verify every expected top-level class actually produced a .class file. Guards
                // against empty / comment-only sources that "compile" to nothing.
                for (cls in classes) {
                    val pkgPath = cls.fullName.substringBeforeLast('.', "").replace('.', '/')
                    val classFile = if (pkgPath.isEmpty()) {
                        outDir.resolve("${cls.simpleName}.class")
                    } else {
                        outDir.resolve(pkgPath).resolve("${cls.simpleName}.class")
                    }
                    if (!classFile.isFile) {
                        messages += "no .class produced for ${cls.fullName} (empty or comment-only source?)"
                    }
                }
                return RecompileResult(compiledOk && messages.isEmpty(), messages)
            }
        } finally {
            srcDir.deleteRecursively()
            outDir.deleteRecursively()
        }
    }

    /**
     * Signal 3 — STUB. Execute the sample's embedded `check()` on the original compiled class and on
     * the decompiled-then-recompiled class; both must pass to prove semantic equivalence.
     *
     * Deferred because it needs the pre-compiled `check()`-bearing fixtures from `corpus/JAVA-SAMPLES.md`
     * (120 samples), which require the JVM compile helper this module will host. Returns
     * [ExecuteCheckResult.NotEvaluated] until then. When implemented, it must not throw on a failing
     * `check()` — a failure is a *signal value*, not an error.
     */
    @Suppress("UNUSED_PARAMETER")
    fun executeCheck(classes: List<DecompiledClass>): ExecuteCheckResult = ExecuteCheckResult.NotEvaluated
}
