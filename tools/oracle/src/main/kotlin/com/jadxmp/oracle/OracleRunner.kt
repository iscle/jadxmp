package com.jadxmp.oracle

/**
 * Scoreboard runner: decompiles every runnable corpus binary with **both** the reference oracle (jadx)
 * and the jadxmp candidate ([JadxmpDecompiler] → `core:api`), scores the three accuracy signals for
 * each, and prints the PARITY / REGRESSION / IMPROVEMENT scoreboard.
 *
 * Run with a JDK (the recompile signal needs `javax.tools`). Point at the corpus via the working
 * directory or `-Djadxmp.corpus=/path/to/corpus`.
 */
fun main() {
    val reference: Decompiler = ReferenceDecompiler()
    val candidate: Decompiler = JadxmpDecompiler()

    // android.jar on the recompile classpath so android.* refs resolve (else the signal can't discriminate).
    val recompileClasspath = AndroidSdk.recompileClasspath()

    val inputs = Corpus.binaryInputs()
    if (inputs.isEmpty()) {
        println("No corpus binaries found under ${Corpus.binaryDir()}")
        return
    }

    val board = Scoreboard()
    for (input in inputs) {
        val refResult = runCatching { reference.decompileFile(input) }
        if (refResult.isFailure) {
            System.err.println("reference failed on ${input.name}: ${refResult.exceptionOrNull()?.message}")
            continue
        }
        val refScore = SignalScore.of(refResult.getOrThrow(), reference.errorMarkers, recompileClasspath)

        // A candidate crash is itself a failure, not a skip: score it as all-signals-failed so a total
        // decompilation blow-up shows as a REGRESSION rather than silently vanishing from the board.
        val candResult = runCatching { candidate.decompileFile(input) }
        val candScore = candResult.fold(
            onSuccess = { SignalScore.of(it, candidate.errorMarkers, recompileClasspath) },
            onFailure = { t ->
                System.err.println("candidate failed on ${input.name}: ${t.message}")
                SignalScore(noErrors = false, recompiles = false, executesCheck = null)
            },
        )
        board.add(SampleResult(sample = input.name, reference = refScore, candidate = candScore))
    }

    println(board.render())
}
