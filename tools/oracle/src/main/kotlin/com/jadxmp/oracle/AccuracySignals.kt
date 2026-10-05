package com.jadxmp.oracle

import java.io.File

/** Outcome of the in-process recompile signal, with diagnostics for failure triage. */
data class RecompileResult(val success: Boolean, val diagnostics: List<String>)

/** Outcome of the execute-`check()` round-trip signal. */
sealed interface ExecuteCheckResult {
    /** The signal was not evaluated (no compiled original / original has no `check()`). */
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
    fun recompiles(classes: List<DecompiledClass>, additionalClasspath: List<File> = emptyList()): RecompileResult =
        JavaCompilation.compile(classes, additionalClasspath).use { it.result }

    /**
     * Signal 3 requires an original source fixture: both its compiled check and the rebuilt check
     * must pass in fresh JVMs. Smali-only samples have no original JVM artifact, so stay unevaluated.
     * A missing rebuilt check, compilation error, crash or timeout is a failure, never a skipped pass.
     */
    fun executeCheck(
        classes: List<DecompiledClass>,
        original: JavaCheckFixture? = null,
    ): ExecuteCheckResult {
        if (original == null) return ExecuteCheckResult.NotEvaluated
        val originalCheck = runOriginalCheck(original)
        if (originalCheck == CheckStatus.MISSING_CHECK) return ExecuteCheckResult.NotEvaluated
        if (originalCheck != CheckStatus.PASSED) return ExecuteCheckResult.Evaluated(false)
        JavaCompilation.compile(classes, original.classpath).use { rebuilt ->
            if (!rebuilt.result.success) return ExecuteCheckResult.Evaluated(false)
            return ExecuteCheckResult.Evaluated(
                CheckExecutor.run(original.checkClass, listOf(rebuilt.output) + original.classpath, original.timeoutMillis) == CheckStatus.PASSED,
            )
        }
    }
}

/** Source fixtures are trusted test inputs; execution is process-isolated, not sandboxed. */
data class JavaCheckFixture(
    val classes: List<DecompiledClass>,
    val checkClass: String,
    val classpath: List<File> = emptyList(),
    val timeoutMillis: Long = 5_000,
)

internal fun runOriginalCheck(original: JavaCheckFixture): CheckStatus =
    JavaCompilation.compile(original.classes, original.classpath, release = 11).use { compiled ->
        if (!compiled.result.success) CheckStatus.FAILED
        else CheckExecutor.run(original.checkClass, listOf(compiled.output) + original.classpath, original.timeoutMillis)
    }
