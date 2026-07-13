package com.jadxmp.oracle

/**
 * The **Kotlin self-measurement scoreboard**: assembles the jadx-derived `corpus/smali` tree to dex, runs
 * it through jadxmp's **Kotlin** output ([KotlinJadxmpDecompiler]), and scores whether that Kotlin
 * recompiles with [KotlinAccuracySignals] (kotlinc via `kotlin-compiler-embeddable`).
 *
 * ## This is SELF-measurement, NOT a differential vs jadx
 * Unlike [SmaliScoreboardRunner] (jadx-1.5.6 vs jadxmp, scored PARITY/REGRESSION/IMPROVEMENT), there is no
 * reference side here: jadx has no production Kotlin backend to diff against. The only question this board
 * answers is *does jadxmp's Kotlin output compile?* — reported as raw compiles-clean / compiles-with-warnings
 * / errors counts. No verdict is computed against jadx.
 *
 * ## Ready but dormant until core:api exposes Kotlin
 * The Kotlin backend + its core:api wiring land in parallel. Until [KotlinJadxmpDecompiler.decompileKotlin]
 * produces output, this runner logs a single clear SKIP ("Kotlin output not yet wired in core:api — harness
 * ready") and exits — it never reports a vacuous PASS for an absent backend. Once `OutputFormat.KOTLIN`
 * exists and the integration point in [KotlinJadxmpDecompiler] is filled in, this board activates with no
 * further change.
 *
 * Run via the `kotlinScoreboard` Gradle task. Restrict to categories with
 * `-Djadxmp.smali.categories=conditions,loops,switches,trycatch`.
 *
 * Honesty notes (mirroring [SmaliScoreboardRunner]):
 * - Assembly failures are counted and listed, never silently dropped.
 * - A jadxmp crash on a sample is caught and scored as a total failure (zero classes → NO_OUTPUT), never a
 *   silent pass.
 * - If kotlinc itself cannot be invoked, the sample is scored UNAVAILABLE (a logged SKIP), never a PASS.
 */
fun main() {
    val jadxmpKotlin = KotlinJadxmpDecompiler()

    // Fail loudly on a FALSE SKIP: if core:api grew OutputFormat.KOTLIN but the decompileKotlin hook was
    // not updated, decompileKotlin still returns null and we'd silently skip a backend that is actually
    // available — hiding real Kotlin recompile regressions. Detect and warn (do not crash the build).
    val wired = KotlinJadxmpDecompiler.isKotlinOutputWired()

    // Probe with one representative sample: if the Kotlin path is not wired, decompileKotlin returns null.
    val categories = System.getProperty("jadxmp.smali.categories")
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        ?: emptySet()
    val inputs = Corpus.smaliInputs(categories)
    if (inputs.isEmpty()) {
        println("No smali inputs found under ${Corpus.smaliDir()} (categories=${categories.ifEmpty { "ALL" }})")
        return
    }

    // Determine wiring from the actual hook (not just the reflection probe): once decompileKotlin is filled
    // in, it returns a non-null DecompilationResult even for empty input (load degrades to zero classes),
    // so a null here unambiguously means "not wired". This avoids depending on a corpus sample assembling.
    val wiredHook = runCatching { jadxmpKotlin.decompileKotlin("wiring-probe", ByteArray(0)) }.getOrNull() != null

    if (!wiredHook) {
        println("=== jadxmp Kotlin self-measurement scoreboard ===")
        println("SKIP: Kotlin output not yet wired in core:api — harness ready.")
        println(
            "  ${inputs.size} smali sample(s) are staged and will be scored for kotlinc-recompile the moment",
        )
        println("  OutputFormat.KOTLIN lands and KotlinJadxmpDecompiler.decompileKotlin is filled in.")
        if (wired) {
            System.err.println(
                "WARNING (false skip): core:api exposes OutputFormat.KOTLIN but " +
                    "KotlinJadxmpDecompiler.decompileKotlin still returns null. Wire the integration point " +
                    "so the now-available Kotlin backend is actually measured, otherwise real regressions hide.",
            )
        }
        return
    }

    // ---- Active path: core:api Kotlin output exists; measure kotlinc-recompile per sample. ----------
    // android.jar on the recompile classpath so android.* refs in decompiled Kotlin resolve (else kotlinc
    // fails for a framework reason and the signal is blind, exactly as in the Java scoreboard).
    val recompileClasspath = AndroidSdk.recompileClasspath()
    System.err.println(
        if (recompileClasspath.isEmpty()) {
            "WARNING: android.jar not found — kotlinc recompile signal is blind on android.* samples. " +
                "Set -Djadxmp.android.jar or ANDROID_HOME."
        } else {
            "kotlinc recompile classpath: ${recompileClasspath.joinToString { it.name }}"
        },
    )

    val assemblyFailed = mutableListOf<String>()
    val byStatus = linkedMapOf<KotlinRecompileStatus, MutableList<String>>()
    KotlinRecompileStatus.entries.forEach { byStatus[it] = mutableListOf() }
    // Samples whose Kotlin recompiles (clean/warn) BUT which jadxmp itself declared wrong
    // (reportedErrors>0: a `// JADXMP ERROR` marker, unstructured control flow, a dropped try-catch handler).
    // These compile yet are NOT evidenced-correct — exactly the masked-false-parity the Java board guards
    // against by pairing the recompile signal with jadxmp's own no-error signal (rule 2).
    val flaggedButRecompiles = mutableListOf<String>()

    for (smali in inputs) {
        val sample = Corpus.smaliSampleName(smali)
        val asm = SmaliAssembler.assemble(smali)
        if (!asm.ok) {
            assemblyFailed += "$sample (${asm.error})"
            continue
        }
        // A jadxmp crash is a total failure, scored as an empty result (→ NO_OUTPUT), never an abort.
        val result = runCatching { jadxmpKotlin.decompileKotlin(sample, asm.dex!!) }
            .getOrElse { DecompilationResult(inputName = sample, classes = emptyList(), reportedErrors = 1) }
        val recompile = KotlinAccuracySignals.recompiles(result.classes, recompileClasspath)
        byStatus.getValue(recompile.status) += sample
        // Cross-check the recompile PASS against jadxmp's own no-error signal: a sample that compiles but
        // carries jadxmp-reported errors is compile-but-flagged, never an evidenced Kotlin win.
        if (recompile.success && result.reportedErrors > 0) flaggedButRecompiles += sample
    }

    print(renderKotlinReport(inputs.size, assemblyFailed, byStatus, flaggedButRecompiles))
}

private fun renderKotlinReport(
    totalDiscovered: Int,
    assemblyFailed: List<String>,
    byStatus: Map<KotlinRecompileStatus, List<String>>,
    flaggedButRecompiles: List<String>,
): String = buildString {
    val clean = byStatus[KotlinRecompileStatus.CLEAN].orEmpty()
    val warnings = byStatus[KotlinRecompileStatus.WARNINGS].orEmpty()
    val errors = byStatus[KotlinRecompileStatus.ERRORS].orEmpty()
    val noOutput = byStatus[KotlinRecompileStatus.NO_OUTPUT].orEmpty()
    val unavailable = byStatus[KotlinRecompileStatus.UNAVAILABLE].orEmpty()
    val scored = clean.size + warnings.size + errors.size + noOutput.size + unavailable.size
    val pass = clean.size + warnings.size

    appendLine("=== jadxmp Kotlin self-measurement scoreboard (kotlinc recompile — NOT a diff vs jadx) ===")
    appendLine("smali files discovered : $totalDiscovered")
    appendLine("assembled + scored     : $scored")
    appendLine("assembly failures      : ${assemblyFailed.size}")
    appendLine()
    appendLine("kotlinc recompile outcomes (self-measurement of jadxmp Kotlin output):")
    appendLine("  compiles clean         : ${clean.size}")
    appendLine("  compiles with warnings : ${warnings.size}   (still PASS — warnings do not fail the signal)")
    appendLine("  errors                 : ${errors.size}")
    appendLine("  no output (empty src)  : ${noOutput.size}")
    appendLine("  compiler unavailable   : ${unavailable.size}   (SKIP — kotlinc not invokable, NOT a pass)")
    val measured = scored - unavailable.size
    // Split the recompile PASS by jadxmp's own no-error signal, mirroring the Java board's
    // evidenced-vs-tied distinction: a headline "evidenced correct Kotlin" number must exclude outputs
    // jadxmp itself flagged (reportedErrors>0), otherwise a compile-but-wrong sample masks as a win.
    val flaggedCount = flaggedButRecompiles.size
    val evidenced = pass - flaggedCount
    appendLine("  ---")
    appendLine("  recompiles (clean+warn): $pass / $measured measured  (excludes ${unavailable.size} skipped)")
    appendLine(
        "    of which jadxmp-flagged: $flaggedCount   " +
            "(reportedErrors>0 / carry a // JADXMP ERROR marker — compile but NOT evidenced-correct)",
    )
    appendLine(
        "  evidenced Kotlin (compiles AND zero jadxmp-reported errors): $evidenced / $measured measured",
    )
    appendLine()

    if (flaggedButRecompiles.isNotEmpty()) {
        appendLine(
            "COMPILES BUT JADXMP-FLAGGED (recompiles yet jadxmp reported errors — EXCLUDED from evidenced):",
        )
        flaggedButRecompiles.sorted().forEach { appendLine("  $it") }
        appendLine()
    }

    if (unavailable.isNotEmpty()) {
        appendLine("NOTE: kotlinc could not be invoked for ${unavailable.size} sample(s); those are SKIPPED, not scored.")
        appendLine()
    }
    if (errors.isNotEmpty()) {
        appendLine("KOTLIN RECOMPILE ERRORS (jadxmp Kotlin output does not compile) — the accuracy-gap worklist:")
        errors.sorted().forEach { appendLine("  $it") }
        appendLine()
    }
    if (noOutput.isNotEmpty()) {
        appendLine("NO OUTPUT (jadxmp emitted nothing compilable):")
        noOutput.sorted().forEach { appendLine("  $it") }
        appendLine()
    }
    if (assemblyFailed.isNotEmpty()) {
        appendLine("ASSEMBLY FAILURES (excluded from scoring):")
        assemblyFailed.sorted().forEach { appendLine("  $it") }
        appendLine()
    }
}
