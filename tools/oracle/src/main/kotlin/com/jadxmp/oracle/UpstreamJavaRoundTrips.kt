package com.jadxmp.oracle

import java.security.MessageDigest

internal data class OutputEvidence(
    val signals: SignalScore,
    val compilerStatus: String,
    val diagnostics: List<String>,
)

internal data class UpstreamRoundTripRow(
    val input: String,
    val inputHash: String,
    val extractedHash: String?,
    val transformations: List<String>,
    val originalStatus: String,
    val diagnostics: List<String>,
    val preparationFailure: String? = null,
    val reference: OutputEvidence? = null,
    val java: OutputEvidence? = null,
    val kotlin: OutputEvidence? = null,
)

/** Expanded coverage measurement; it does not replace or relax the established accuracy gates. */
internal class UpstreamJavaRoundTrips(
    private val reference: Decompiler = ReferenceDecompiler(),
    private val candidate: Decompiler = JadxmpDecompiler(),
    private val kotlin: (String, ByteArray) -> DecompilationResult = KotlinJadxmpDecompiler()::decompileKotlin,
) {
    fun measure(name: String, bytes: ByteArray): UpstreamRoundTripRow =
        measure(name, bytes.toString(Charsets.UTF_8)).copy(inputHash = hash(bytes))

    fun measure(name: String, text: String): UpstreamRoundTripRow {
        val extraction = try { UpstreamJavaExtractor.extract(text) } catch (failure: Throwable) {
            rethrowFatal(failure)
            return UpstreamRoundTripRow(name, hash(text), null, emptyList(), "EXTRACTION_FAILED", listOf(failure.toString()))
        }
        val sample = extraction.sample
        val original = sample?.let {
            try { UpstreamJavaInventory.validate(it) } catch (failure: Throwable) {
                rethrowFatal(failure)
                return UpstreamRoundTripRow(name, hash(text), hash(it.source.source), it.transformations,
                    "ORIGINAL_MEASUREMENT_FAILED", extraction.diagnostics + failure.toString())
            }
        }
        val row = UpstreamRoundTripRow(name, hash(text), sample?.let { hash(it.source.source) },
            sample?.transformations.orEmpty(), original?.status?.name ?: extraction.status.name,
            extraction.diagnostics + original?.diagnostics.orEmpty())
        if (sample == null || original?.status !in setOf(OriginalFixtureStatus.CHECK_PASSED, OriginalFixtureStatus.COMPILED_NO_CHECK)) return row
        val fixture = JavaCheckFixture(listOf(sample.source), sample.checkClass, UpstreamJavaInventory.fixtureClasspath)
        val dex = try { JavaFixtureCompiler.dex(fixture) } catch (failure: Throwable) {
            rethrowFatal(failure)
            return row.copy(preparationFailure = failure.toString())
        }
        val check = original?.status == OriginalFixtureStatus.CHECK_PASSED
        fun measureOutput(markers: List<String>, isKotlin: Boolean, decompile: () -> DecompilationResult): OutputEvidence = try {
            output(decompile(), markers, fixture, check, isKotlin)
        } catch (failure: Throwable) {
            rethrowFatal(failure)
            OutputEvidence(SignalScore(false, false, if (check) false else null), "MEASUREMENT_FAILED", listOf(failure.toString()))
        }
        return row.copy(
            reference = measureOutput(reference.errorMarkers, false) { reference.decompile(name, dex) },
            java = measureOutput(candidate.errorMarkers, false) { candidate.decompile(name, dex) },
            kotlin = measureOutput(ErrorMarkers.JADXMP, true) { kotlin(name, dex) },
        )
    }

    companion object {
        // An input-triggered assertion, linkage failure or exhausted recursion is a failed
        // measurement. Cancellation and process/heap termination must still stop the runner.
        private fun rethrowFatal(failure: Throwable) {
            if (failure is java.util.concurrent.CancellationException || failure is com.jadxmp.pipeline.pass.CancellationSignal ||
                failure is InterruptedException ||
                failure is ThreadDeath || failure is VirtualMachineError && failure !is StackOverflowError) throw failure
        }

        /** All output classes are compiled together; a missing rebuilt check is always failure. */
        fun output(result: DecompilationResult, markers: List<String>, original: JavaCheckFixture,
            hasCheck: Boolean, kotlin: Boolean,
        ): OutputEvidence {
            val clean = AccuracySignals.noErrors(result, markers)
            val diagnostics = mutableListOf<String>()
            if (result.reportedErrors != 0) diagnostics += "decompiler reported ${result.reportedErrors} error(s)"
            for (cls in result.classes) {
                diagnostics += cls.source.lineSequence().filter { line -> markers.any { it in line } }
                    .map { "${cls.fullName}: $it" }.toList()
            }
            if (kotlin) {
                val compile = KotlinAccuracySignals.recompiles(result.classes, original.classpath)
                val execution = if (hasCheck) KotlinAccuracySignals.executeCheck(result.classes, original) else ExecuteCheckResult.NotEvaluated
                return OutputEvidence(SignalScore(clean, compile.success, evaluated(execution)), compile.status.name,
                    diagnostics + compile.errors + compile.warnings + checkDiagnostics(execution))
            }
            val compile = AccuracySignals.recompiles(result.classes, original.classpath)
            val execution = if (hasCheck) AccuracySignals.executeCheck(result.classes, original) else ExecuteCheckResult.NotEvaluated
            return OutputEvidence(SignalScore(clean, compile.success, evaluated(execution)),
                if (compile.success) "COMPILED" else "COMPILE_FAILED", diagnostics + compile.diagnostics + checkDiagnostics(execution))
        }
        private fun checkDiagnostics(result: ExecuteCheckResult): List<String> =
            if (result == ExecuteCheckResult.Evaluated(false)) listOf("rebuilt check() did not pass (including missing, failed or timed-out checks)") else emptyList()

        private fun evaluated(result: ExecuteCheckResult): Boolean? = when (result) {
            ExecuteCheckResult.NotEvaluated -> null
            is ExecuteCheckResult.Evaluated -> result.passed
        }
        private fun hash(text: String): String = hash(text.toByteArray(Charsets.UTF_8))
        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
