package com.jadxmp.oracle

/** Incomplete or regressed measurements must never produce a green verification task. */
internal fun requireDifferentialParity(
    board: Scoreboard,
    discovered: Int,
    assemblyFailures: List<String>,
    referenceFailures: List<String>,
) {
    val problems = buildList {
        if (discovered == 0) add("no corpus inputs discovered")
        if (assemblyFailures.isNotEmpty()) add("${assemblyFailures.size} assembly failure(s)")
        if (referenceFailures.isNotEmpty()) add("${referenceFailures.size} reference failure(s)")
        if (board.samples.size != discovered) add("only ${board.samples.size}/$discovered samples scored")
        if (board.hasRegression()) add("regressions: ${board.regressions().joinToString { it.sample }}")
    }
    check(problems.isEmpty()) { "Differential accuracy gate failed: ${problems.joinToString("; ")}" }
}
