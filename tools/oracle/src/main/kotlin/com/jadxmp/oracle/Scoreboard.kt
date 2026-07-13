package com.jadxmp.oracle

/**
 * How one sample's decompilation compares to the reference:
 * - [PARITY] — jadxmp passes every signal jadx passes (the gate's steady state).
 * - [REGRESSION] — jadx passed a signal jadxmp fails (a real accuracy loss; the CI gate is zero of these).
 * - [IMPROVEMENT] — jadxmp passes a signal jadx fails (celebrated; promoted into Layer A once stable).
 */
enum class Verdict { PARITY, REGRESSION, IMPROVEMENT }

/** Pass/fail of the three accuracy signals for one decompiler on one sample. */
data class SignalScore(
    val noErrors: Boolean,
    val recompiles: Boolean,
    /** `null` = signal not evaluated (e.g. execute-`check()` is still stubbed). */
    val executesCheck: Boolean?,
) {
    companion object {
        /**
         * Score a decompilation with the reusable [AccuracySignals] functions. [markers] is the
         * producing decompiler's own signal-1 marker set (`decompiler.errorMarkers`), so the no-error
         * scan is meaningful for both reference and clean-room candidate output.
         *
         * [recompileClasspath] MUST include android.jar for the recompile signal to discriminate on the
         * many samples that reference `android.*` — otherwise both sides fail recompile for a framework
         * reason and the signal is useless (see [AndroidSdk]).
         */
        fun of(
            result: DecompilationResult,
            markers: List<String>,
            recompileClasspath: List<java.io.File> = emptyList(),
        ): SignalScore = SignalScore(
            noErrors = AccuracySignals.noErrors(result, markers),
            recompiles = AccuracySignals.recompiles(result.classes, recompileClasspath).success,
            executesCheck = when (val r = AccuracySignals.executeCheck(result.classes)) {
                is ExecuteCheckResult.Evaluated -> r.passed
                ExecuteCheckResult.NotEvaluated -> null
            },
        )

        /** A signal both decompilers PASS — positive evidence of parity (vs an all-tied-fail tie). */
        fun sharedPass(ref: SignalScore, cand: SignalScore): Boolean =
            (ref.noErrors && cand.noErrors) ||
                (ref.recompiles && cand.recompiles) ||
                (ref.executesCheck == true && cand.executesCheck == true)
    }
}

/**
 * One row of the scoreboard: the reference score and (once jadxmp is wired) the candidate score.
 * While [candidate] is null the run is reference-only and [verdict] is null.
 */
data class SampleResult(
    val sample: String,
    val reference: SignalScore,
    val candidate: SignalScore?,
    /** Optional grouping key (smali construct category); null for the flat binary run. */
    val category: String? = null,
) {
    val verdict: Verdict? get() = candidate?.let { classify(reference, it) }

    /**
     * True when this PARITY is backed by a shared PASS (real evidence), false for a *tied-fail* parity
     * where every comparable signal fails on BOTH sides — an INDETERMINATE tie, not evidence. Keeping
     * these apart stops a masked regression (jadx clean, jadxmp garbage, both fail recompile for lack of
     * a dependency) from hiding inside the parity count. Only meaningful when [verdict] == [Verdict.PARITY].
     */
    val isEvidencedParity: Boolean
        get() = verdict == Verdict.PARITY && candidate?.let { SignalScore.sharedPass(reference, it) } == true

    companion object {
        /**
         * Compare candidate to reference. Only signals **both** decompilers evaluated (non-null on
         * each side) count, so a still-stubbed signal never fabricates a verdict.
         *
         * A REGRESSION (candidate worse on any signal) DOMINATES an IMPROVEMENT, so an IMPROVEMENT
         * verdict already guarantees the candidate is not worse on any other signal — e.g. a no-error
         * "win" that also fails recompile where jadx passes is scored REGRESSION, not IMPROVEMENT.
         */
        fun classify(ref: SignalScore, cand: SignalScore): Verdict {
            val pairs = buildList {
                add(ref.noErrors to cand.noErrors)
                add(ref.recompiles to cand.recompiles)
                if (ref.executesCheck != null && cand.executesCheck != null) {
                    add(ref.executesCheck to cand.executesCheck)
                }
            }
            val regression = pairs.any { (r, c) -> r && !c }
            val improvement = pairs.any { (r, c) -> !r && c }
            return when {
                regression -> Verdict.REGRESSION // a regression dominates: the gate must catch it
                improvement -> Verdict.IMPROVEMENT
                else -> Verdict.PARITY
            }
        }
    }
}

/** Accumulates [SampleResult]s and renders the scoreboard artifact (counts + per-signal tallies). */
class Scoreboard {
    private val results = mutableListOf<SampleResult>()

    fun add(result: SampleResult) {
        results += result
    }

    val samples: List<SampleResult> get() = results.toList()

    /** True once every candidate score is present and no sample regressed — the CI gate condition. */
    fun hasRegression(): Boolean = results.any { it.verdict == Verdict.REGRESSION }

    /** Count of each verdict across all samples that have a candidate score. */
    fun verdictCounts(): Map<Verdict, Int> =
        results.mapNotNull { it.verdict }.groupingBy { it }.eachCount()

    /** Samples jadxmp regressed on — jadx passed a signal jadxmp fails. The accuracy-gap worklist. */
    fun regressions(): List<SampleResult> = results.filter { it.verdict == Verdict.REGRESSION }

    /** PARITY samples backed by a shared PASS (real evidence of equivalence). */
    fun evidencedParityCount(): Int = results.count { it.isEvidencedParity }

    /** PARITY samples where every comparable signal fails on both sides — an indeterminate tie. */
    fun tiedParityCount(): Int = results.count { it.verdict == Verdict.PARITY && !it.isEvidencedParity }

    private fun countPass(select: (SignalScore) -> Boolean?): Int = results.count { select(it.reference) == true }

    fun render(): String = buildString {
        val n = results.size
        appendLine("=== jadxmp accuracy scoreboard ===")
        appendLine("samples: $n")
        appendLine()
        appendLine("reference (jadx) signal pass counts:")
        appendLine("  no-error   : ${countPass { it.noErrors }} / $n")
        appendLine("  recompiles : ${countPass { it.recompiles }} / $n")
        val evaluatedCheck = results.count { it.reference.executesCheck != null }
        appendLine("  exec-check : ${countPass { it.executesCheck }} / $evaluatedCheck evaluated (stub)")
        appendLine()

        val haveCandidate = results.any { it.candidate != null }
        if (!haveCandidate) {
            appendLine("candidate (jadxmp): not wired yet (core:api pending) — reference-only run.")
        } else {
            val byVerdict = results.mapNotNull { it.verdict }.groupingBy { it }.eachCount()
            appendLine("verdicts:")
            for (v in Verdict.entries) {
                appendLine("  ${v.name.lowercase().padEnd(11)}: ${byVerdict[v] ?: 0}")
            }
            appendLine()
            appendLine(if (hasRegression()) "GATE: FAIL (regressions present)" else "GATE: PASS (zero regressions)")
        }
        appendLine()
        appendLine("per-sample:")
        for (r in results) {
            val ref = r.reference
            val flags = "err=${mark(ref.noErrors)} rec=${mark(ref.recompiles)} chk=${markN(ref.executesCheck)}"
            val verdict = r.verdict?.let { " -> $it" } ?: ""
            appendLine("  ${r.sample.padEnd(28)} [$flags]$verdict")
        }
    }

    private fun mark(b: Boolean): String = if (b) "PASS" else "FAIL"
    private fun markN(b: Boolean?): String = b?.let { mark(it) } ?: "n/a "
}
