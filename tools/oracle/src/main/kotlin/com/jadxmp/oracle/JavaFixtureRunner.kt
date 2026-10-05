package com.jadxmp.oracle

/** Semantic gate for trusted standalone corpus/java sources; no source fixture is silently skipped. */
fun main() {
    val base = Corpus.root().resolve("java")
    val inputs = base.walkTopDown().filter { it.isFile && it.extension == "java" }.sortedBy { it.path }.toList()
    check(inputs.isNotEmpty()) { "No Java source fixtures under $base" }
    val reference = ReferenceDecompiler()
    val candidate = JadxmpDecompiler()
    val kotlin = KotlinJadxmpDecompiler()
    val board = Scoreboard()
    val failures = mutableListOf<String>()
    println("=== Java fixture execution scoreboard (${reference.name}) ===")
    for (file in inputs) {
        val name = file.relativeTo(base).invariantSeparatorsPath
        val cls = DecompiledClass(name.removeSuffix(".java").replace('/', '.'), file.readText())
        val original = JavaCheckFixture(listOf(cls), cls.fullName)
        try {
            // A broken/missing original check invalidates measurement; it cannot make both sides tie.
            check(AccuracySignals.executeCheck(original.classes, original) == ExecuteCheckResult.Evaluated(true)) {
                "original check() did not pass"
            }
            val dex = JavaFixtureCompiler.dex(original)
            val ref = reference.decompile(name, dex)
            val cand = runCatching { candidate.decompile(name, dex) }
                .getOrElse { DecompilationResult(name, emptyList(), reportedErrors = 1) }
            val refScore = SignalScore.of(ref, reference.errorMarkers, original = original)
            val candScore = SignalScore.of(cand, candidate.errorMarkers, original = original)
            board.add(SampleResult(name, refScore, candScore))
            println("$name: reference=$refScore candidate=$candScore")
            // This is also a positive correctness gate, not just parity with a failing reference.
            if (!candScore.noErrors || !candScore.recompiles || candScore.executesCheck != true) {
                failures += "$name: candidate does not pass all three signals"
            }
            val kotlinResult = kotlin.decompileKotlin(name, dex)
            val kotlinCompile = KotlinAccuracySignals.recompiles(kotlinResult.classes)
            val kotlinCheck = KotlinAccuracySignals.executeCheck(kotlinResult.classes, original)
            val kotlinClean = AccuracySignals.noErrors(kotlinResult, ErrorMarkers.JADXMP)
            println("$name: Kotlin no-error=$kotlinClean recompile=${kotlinCompile.status} execution=$kotlinCheck")
            if (!kotlinClean || !kotlinCompile.success || kotlinCheck != ExecuteCheckResult.Evaluated(true)) {
                failures += "$name: Kotlin does not pass all three signals"
            }
        } catch (e: Exception) {
            failures += "$name: ${e.message}"
        }
    }
    println(board.render())
    failures.forEach { System.err.println(it) }
    check(failures.isEmpty()) { "Java fixture semantic gate failed: ${failures.joinToString()}" }
    requireDifferentialParity(board, inputs.size, emptyList(), emptyList())
}
