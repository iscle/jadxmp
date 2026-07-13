package com.jadxmp.oracle

import com.jadxmp.api.Decompiler as JadxmpEngine
import com.jadxmp.api.DecompilerArgs
import com.jadxmp.api.OutputFormat

/**
 * The **Kotlin-output side** of jadxmp for the self-measurement scoreboard.
 *
 * This mirrors [JadxmpDecompiler], but asks core:api for **Kotlin** source instead of Java. Now that the
 * Kotlin backend (`core:codegen-kotlin`) and its core:api wiring (`OutputFormat.KOTLIN`) have landed,
 * [decompileKotlin] drives the real engine with `outputFormat = OutputFormat.KOTLIN`. The harness around it
 * ([main] in `KotlinScoreboardRunner`) scores whether that Kotlin recompiles with kotlinc — it never
 * reports a vacuous PASS for an absent backend.
 *
 * There is deliberately no `Decompiler` interface implementation here: that interface is the *differential*
 * contract (reference vs candidate), and Kotlin output has no reference side (jadx has no production Kotlin
 * backend). This is pure self-measurement, so it exposes a standalone [decompileKotlin] instead.
 */
class KotlinJadxmpDecompiler {

    val name: String = "jadxmp-kotlin"

    // NIT 6: no `errorMarkers` here. Self-measurement scores only the kotlinc-recompile signal; it never
    // computes the no-error text scan, so an error-marker set would be dead code. If a Kotlin no-error signal
    // is ever added, reintroduce jadxmp's markers (see ErrorMarkers.JADXMP) at that point.

    /**
     * Decompile [bytes] to **Kotlin** source by driving core:api with `outputFormat = OutputFormat.KOTLIN`,
     * mapping its result onto the oracle's shared [DecompiledClass]/[DecompilationResult] contract — the
     * exact same shape as [JadxmpDecompiler.decompile], only the requested language differs. The result also
     * carries jadxmp's own no-error signal via [DecompilationResult.reportedErrors] (`result.errorCount`), so
     * the scoreboard can exclude compile-but-jadxmp-flagged outputs from its evidenced-correct count.
     *
     * Always returns a result — even for empty/malformed input, where the engine degrades to zero classes
     * (never null). The scoreboard's `!= null` wiring probe therefore reads "wired".
     */
    fun decompileKotlin(name: String, bytes: ByteArray): DecompilationResult {
        val engine = JadxmpEngine(DecompilerArgs(outputFormat = OutputFormat.KOTLIN))
        engine.load(name, bytes)
        val result = engine.decompileAll()
        val classes = result.classes.map { DecompiledClass(it.fullName, it.code) }
        return DecompilationResult(inputName = name, classes = classes, reportedErrors = result.errorCount)
    }

    companion object {
        /**
         * Reflectively detect whether core:api has an `OutputFormat.KOTLIN` constant. Now that the backend
         * is wired this reports `true`; it is retained as the tripwire that would fire if the enum were ever
         * removed while this hook still referenced it — never to fabricate output.
         */
        fun isKotlinOutputWired(): Boolean = try {
            val enumClass = Class.forName("com.jadxmp.api.OutputFormat")
            enumClass.enumConstants?.any { (it as Enum<*>).name == "KOTLIN" } == true
        } catch (_: Throwable) {
            false
        }
    }
}
