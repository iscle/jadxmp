package com.jadxmp.oracle

/**
 * The **branchy differential scoreboard**: assembles the jadx-derived `corpus/smali` tree to dex and
 * runs the jadx-1.5.6 reference vs jadxmp (`core:api`) over every sample, scoring the three accuracy
 * signals and classifying each as PARITY / REGRESSION / IMPROVEMENT. This is where Phase-3 control-flow
 * structuring is actually measured against jadx.
 *
 * Run via the `smaliScoreboard` Gradle task (fresh classpath, JDK toolchain for the recompile signal).
 * Restrict to categories with `-Djadxmp.smali.categories=conditions,loops,switches,trycatch`.
 *
 * Honesty notes:
 * - Assembly failures are counted and listed, never silently dropped.
 * - A jadxmp crash on a sample is caught and scored as a total failure (zero classes → signal-1 fail),
 *   which is a REGRESSION wherever jadx succeeded — exactly what the gate must catch.
 * - Many smali samples reference android/library or sibling types absent from the recompile classpath; that
 *   makes BOTH sides fail signal-2 equally (→ PARITY). The differential is what matters.
 */
fun main() {
    val categories = System.getProperty("jadxmp.smali.categories")
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        ?: emptySet()

    val reference: Decompiler = ReferenceDecompiler()
    val candidate: Decompiler = JadxmpDecompiler()

    // Diagnostic aid (trust the gate by inspecting it): -Djadxmp.smali.dump=<substring> prints both
    // decompilers' output and recompile diagnostics for the matching sample(s), then still scores them.
    val dumpMatch = System.getProperty("jadxmp.smali.dump")?.takeIf { it.isNotBlank() }

    // android.jar on the recompile classpath so `android.*` refs resolve — otherwise both sides fail
    // recompile for a framework reason and the signal can't discriminate (masking regressions as parity).
    val recompileClasspath = AndroidSdk.recompileClasspath()
    System.err.println(
        if (recompileClasspath.isEmpty()) {
            "WARNING: android.jar not found — recompile signal is blind on android.* samples (tied parity). " +
                "Set -Djadxmp.android.jar or ANDROID_HOME."
        } else {
            "recompile classpath: ${recompileClasspath.joinToString { it.name }}"
        },
    )

    val inputs = Corpus.smaliInputs(categories)
    if (inputs.isEmpty()) {
        println("No smali inputs found under ${Corpus.smaliDir()} (categories=${categories.ifEmpty { "ALL" }})")
        return
    }

    val board = Scoreboard()
    val assemblyFailed = mutableListOf<String>()
    val referenceFailed = mutableListOf<String>()

    for (smali in inputs) {
        val sample = Corpus.smaliSampleName(smali)
        val category = Corpus.categoryOf(smali)

        val asm = SmaliAssembler.assemble(smali)
        if (!asm.ok) {
            assemblyFailed += "$sample (${asm.error})"
            continue
        }
        val dex = asm.dex!!

        // Reference must succeed to form a differential; a reference crash is logged and the sample skipped.
        val refResult = runCatching { reference.decompile(sample, dex) }.getOrElse {
            referenceFailed += "$sample (${it.message ?: it.toString()})"
            continue
        }
        val refScore = SignalScore.of(refResult, reference.errorMarkers, recompileClasspath)

        // A candidate crash is a total decompilation failure (per fault-isolation it should not happen,
        // but if it does it must score as failure, not abort the run).
        val candResult = runCatching { candidate.decompile(sample, dex) }
            .getOrElse { DecompilationResult(inputName = sample, classes = emptyList(), reportedErrors = 1) }
        val candScore = SignalScore.of(candResult, candidate.errorMarkers, recompileClasspath)

        if (dumpMatch != null && sample.contains(dumpMatch)) {
            dumpSample(sample, refResult, refScore, candResult, candScore, recompileClasspath)
        }

        board.add(SampleResult(sample = sample, reference = refScore, candidate = candScore, category = category))
    }

    print(renderSmaliReport(board, inputs.size, assemblyFailed, referenceFailed))
}

private fun dumpSample(
    sample: String,
    ref: DecompilationResult,
    refScore: SignalScore,
    cand: DecompilationResult,
    candScore: SignalScore,
    recompileClasspath: List<java.io.File>,
) {
    fun section(title: String, result: DecompilationResult, score: SignalScore) {
        println("========== $title :: $sample ==========")
        println("signals: no-error=${score.noErrors} recompiles=${score.recompiles}  (reportedErrors=${result.reportedErrors}, classes=${result.classes.size})")
        // Use the SAME recompile classpath (android.jar) the scored signal used, so the printed
        // diagnostics are honest — otherwise the dump shows phantom `package android.* does not exist`
        // errors even when the real signal PASSED, misleading agents root-causing regressions.
        val recompile = AccuracySignals.recompiles(result.classes, recompileClasspath)
        if (!recompile.success) println("recompile diagnostics: ${recompile.diagnostics}")
        for (c in result.classes) {
            println("---- ${c.fullName} ----")
            println(c.source)
        }
    }
    section("jadx (reference)", ref, refScore)
    section("jadxmp (candidate)", cand, candScore)
    println("========== end dump :: $sample ==========")
}

/** Which signals jadx passed but jadxmp failed on one sample (the regressed signals). */
private fun regressedSignals(ref: SignalScore, cand: SignalScore): List<String> =
    regressedSignalNames(ref, cand).toList()

/** Which signals jadxmp passed but jadx failed on one sample (the improved signals). */
private fun improvedSignals(ref: SignalScore, cand: SignalScore): List<String> = buildList {
    if (!ref.noErrors && cand.noErrors) add(SignalNames.NO_ERROR)
    if (!ref.recompiles && cand.recompiles) add(SignalNames.RECOMPILES)
    if (ref.executesCheck == false && cand.executesCheck == true) add(SignalNames.EXEC_CHECK)
}

private fun renderSmaliReport(
    board: Scoreboard,
    totalDiscovered: Int,
    assemblyFailed: List<String>,
    referenceFailed: List<String>,
): String = buildString {
    val scored = board.samples
    appendLine("=== jadxmp smali differential scoreboard (jadx-1.5.6 vs jadxmp core:api) ===")
    appendLine("smali files discovered : $totalDiscovered")
    appendLine("assembled + scored     : ${scored.size}")
    appendLine("assembly failures      : ${assemblyFailed.size}")
    appendLine("reference failures     : ${referenceFailed.size}")
    appendLine()

    val verdicts = board.verdictCounts()
    val evidenced = board.evidencedParityCount()
    val tied = board.tiedParityCount()
    appendLine("overall verdicts:")
    appendLine("  parity (total) : ${verdicts[Verdict.PARITY] ?: 0}")
    appendLine("    evidenced    : $evidenced   (a signal both pass — real equivalence evidence)")
    appendLine("    tied-fail    : $tied   (every signal fails on both sides — INDETERMINATE, not evidence)")
    appendLine("  regression     : ${verdicts[Verdict.REGRESSION] ?: 0}")
    appendLine("  improvement    : ${verdicts[Verdict.IMPROVEMENT] ?: 0}")
    appendLine("  exp-divergence : ${verdicts[Verdict.EXPECTED_DIVERGENCE] ?: 0}   (allowlisted jadx-bugs; excluded from gate)")
    appendLine()

    // Per-category table: parity split into evidenced (P!) and tied-fail (P?); xdiv = expected divergences.
    appendLine("per-category (evidenced-parity / tied-parity / regression / improvement / expected-divergence):")
    appendLine("  %-14s %6s %6s %7s %7s %7s".format("category", "par!", "par?", "regr", "impr", "xdiv"))
    scored.groupBy { it.category ?: "?" }.toSortedMap().forEach { (cat, rows) ->
        val pe = rows.count { it.isEvidencedParity }
        val pt = rows.count { it.verdict == Verdict.PARITY && !it.isEvidencedParity }
        val r = rows.count { it.verdict == Verdict.REGRESSION }
        val i = rows.count { it.verdict == Verdict.IMPROVEMENT }
        val x = rows.count { it.verdict == Verdict.EXPECTED_DIVERGENCE }
        appendLine("  %-14s %6d %6d %7d %7d %7d".format(cat, pe, pt, r, i, x))
    }
    appendLine()

    val regressions = board.regressions()
    appendLine(if (board.hasRegression()) "GATE: FAIL — ${regressions.size} regression(s)" else "GATE: PASS — zero regressions")
    appendLine()

    if (regressions.isNotEmpty()) {
        appendLine("REGRESSIONS (jadx passes a signal jadxmp fails) — the accuracy-gap worklist:")
        regressions
            .sortedBy { it.sample }
            .forEach { r ->
                val signals = regressedSignals(r.reference, r.candidate!!).joinToString(", ")
                appendLine("  ${r.sample.padEnd(40)} fails: $signals")
            }
        appendLine()
    }

    val divergences = board.expectedDivergences()
    if (divergences.isNotEmpty()) {
        appendLine(
            "EXPECTED DIVERGENCES (allowlisted: jadx passes by MISCOMPILING, jadxmp faithfully fails — " +
                "excluded from the gate, NOT hidden):",
        )
        divergences
            .sortedBy { it.sample }
            .forEach { r ->
                val entry = DocumentedDivergences.forSample(r.sample)
                val signals = regressedSignals(r.reference, r.candidate!!).joinToString(", ")
                appendLine("  ${r.sample.padEnd(40)} expected fails: $signals")
                entry?.rationale?.let { appendLine("      why jadx is buggy: $it") }
            }
        appendLine()
    }

    val stale = board.staleAllowlistEntries()
    if (stale.isNotEmpty()) {
        appendLine(
            "STALE ALLOWLIST ENTRIES (jadxmp now passes an allowlisted signal — remove from " +
                "DocumentedDivergences):",
        )
        stale.sortedBy { it.sample }.forEach { appendLine("  ${it.sample}  (documented: ${it.signals.sorted()})") }
        appendLine()
    }

    val improvements = scored.filter { it.verdict == Verdict.IMPROVEMENT }
    if (improvements.isNotEmpty()) {
        appendLine("IMPROVEMENTS (jadxmp passes a signal jadx fails):")
        improvements
            .sortedBy { it.sample }
            .forEach { r ->
                val signals = improvedSignals(r.reference, r.candidate!!).joinToString(", ")
                appendLine("  ${r.sample.padEnd(40)} gains: $signals")
            }
        appendLine()
    }

    if (assemblyFailed.isNotEmpty()) {
        appendLine("ASSEMBLY FAILURES (excluded from scoring):")
        assemblyFailed.sorted().forEach { appendLine("  $it") }
        appendLine()
    }
    if (referenceFailed.isNotEmpty()) {
        appendLine("REFERENCE FAILURES (jadx itself errored; sample skipped):")
        referenceFailed.sorted().forEach { appendLine("  $it") }
        appendLine()
    }
}
