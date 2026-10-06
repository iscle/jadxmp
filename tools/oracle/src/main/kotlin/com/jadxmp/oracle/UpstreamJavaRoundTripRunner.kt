package com.jadxmp.oracle

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

internal object UpstreamRoundTripReport {
    fun table(rows: List<UpstreamRoundTripRow>): String = buildString {
        appendLine("input\tinput_sha256\textracted_sha256\toriginal_status\tpreparation\ttransformations\treference\tjava\tkotlin\tdiagnostics")
        for (row in rows) {
            fun evidence(value: OutputEvidence?): String = value?.let {
                "no_error=${it.signals.noErrors},compile=${it.signals.recompiles},check=${it.signals.executesCheck ?: "NOT_EVALUATED"},compiler=${it.compilerStatus}"
            } ?: "NOT_MEASURED"
            val diagnostics = row.diagnostics + listOfNotNull(row.preparationFailure) +
                listOf("reference" to row.reference, "java" to row.java, "kotlin" to row.kotlin).flatMap { (name, result) ->
                    result?.diagnostics.orEmpty().map { "$name: $it" }
                }
            appendLine(listOf(row.input, row.inputHash, row.extractedHash.orEmpty(), row.originalStatus,
                if (row.preparationFailure != null) "FAILED" else if (row.reference != null) "READY" else "NOT_ELIGIBLE",
                row.transformations.joinToString("; "), evidence(row.reference), evidence(row.java), evidence(row.kotlin),
                diagnostics.joinToString("; ")).joinToString("\t", transform = ::cell))
        }
    }

    fun summary(rows: List<UpstreamRoundTripRow>, discovered: Int, filter: String?, metadataEvidence: List<String> = emptyList()): String = buildString {
        appendLine("=== Expanded upstream Java round-trip measurement ===")
        appendLine("status=COMPLETE")
        append(metadataHeader(metadataEvidence))
        appendLine("baseline=${ReferenceDecompiler.DEFAULT_JADX_VERSION}; javac release=11; AssertJ=3.27.7; D8=9.1.31")
        appendLine(JavaCompilation.compilerProfile)
        appendLine("discovered=$discovered selected=${rows.size} scope=${filter ?: "ALL"}")
        appendLine("This expands measured coverage; it does not replace or relax the existing accuracy gates.")
        appendLine("original: " + rows.groupingBy { it.originalStatus }.eachCount().toSortedMap().entries.joinToString { "${it.key}=${it.value}" })
        appendLine("DEX preparation failures=${rows.count { it.preparationFailure != null }}")
        for ((name, select) in listOf<Pair<String, (UpstreamRoundTripRow) -> OutputEvidence?>>(
            "reference Java" to { it.reference }, "candidate Java" to { it.java }, "candidate Kotlin" to { it.kotlin })) {
            val outputs = rows.mapNotNull(select)
            appendLine("$name: measured=${outputs.size}, no_error=${outputs.count { it.signals.noErrors }}, " +
                "compiles=${outputs.count { it.signals.recompiles }}, " +
                "checks_pass=${outputs.count { it.signals.executesCheck == true }}, checks_fail=${outputs.count { it.signals.executesCheck == false }}, " +
                "checks_not_evaluated=${outputs.count { it.signals.executesCheck == null }}")
        }
        val gaps = rows.filter { row -> row.reference != null && row.java != null && regressedSignalNames(row.reference.signals, row.java.signals).isNotEmpty() }
        appendLine("Java cases where reference passes a candidate-failing signal=${gaps.size}")
        for (row in gaps) appendLine("  ${row.input}: ${regressedSignalNames(row.reference!!.signals, row.java!!.signals).joinToString()}")
        appendLine("Compilation and shared passing signals are not whole-corpus semantic equivalence.")
    }

    fun metadataHeader(evidence: List<String>): String =
        evidence.ifEmpty { listOf("metadata_profile=none") }.joinToString("\n", postfix = "\n", transform = ::cell)

    private fun cell(value: String): String = value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")
}

/** Flush each row before starting the next input; interrupted runs cannot leave stale success. */
internal class UpstreamRoundTripJournal(
    private val report: File,
    private val discovered: Int,
    selected: List<String>,
    private val filter: String?,
    private val metadataEvidence: List<String> = emptyList(),
) {
    private val expected = selected.toSet()
    private val rows = linkedMapOf<String, UpstreamRoundTripRow>()

    companion object {
        fun markStarted(report: File) {
            report.parentFile?.mkdirs()
            report.writeText("status=INCOMPLETE phase=VERIFY_BASELINE\n")
        }
    }

    init {
        require(selected.isNotEmpty() && expected.size == selected.size) { "Empty or duplicate fixture selection" }
        report.parentFile?.mkdirs()
        report.writeText("status=INCOMPLETE discovered=$discovered selected=${selected.size}\n" +
            UpstreamRoundTripReport.metadataHeader(metadataEvidence) +
            UpstreamRoundTripReport.table(emptyList()))
    }

    fun record(row: UpstreamRoundTripRow) {
        check(row.input in expected && row.input !in rows) { "Unexpected or duplicate fixture: ${row.input}" }
        report.appendText(UpstreamRoundTripReport.table(listOf(row)).substringAfter('\n'))
        rows[row.input] = row
    }

    fun finish(): String {
        check(rows.keys == expected) { "Incomplete fixture measurement: ${rows.size}/${expected.size}" }
        val summary = UpstreamRoundTripReport.summary(rows.values.toList(), discovered, filter, metadataEvidence)
        val completed = File.createTempFile("upstream-complete-", ".tsv", report.absoluteFile.parentFile)
        try {
            completed.writeText(summary + "\n" + UpstreamRoundTripReport.table(rows.values.toList()))
            try {
                Files.move(completed.toPath(), report.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(completed.toPath(), report.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            completed.delete()
        }
        return summary
    }
}

/** Measures every selected original source, including extraction/compilation/preparation failures. */
fun main() {
    val report = File(checkNotNull(System.getProperty("jadxmp.upstream.report")) { "Missing report path" })
    UpstreamRoundTripJournal.markStarted(report)
    val reference = Corpus.root().parentFile.resolve("reference/jadx")
    PinnedReference.verify(reference)
    val base = reference.resolve("jadx-core/src/test/java/jadx/tests/integration")
    check(base.isDirectory) { "Missing original integration sources" }
    val all = base.walkTopDown().filter { it.isFile && it.extension == "java" }.sortedBy { it.path }.toList()
    check(all.isNotEmpty()) { "No original source fixtures discovered" }
    val filter = System.getProperty("jadxmp.upstream.filter")?.trim()?.takeIf { it.isNotEmpty() }
    val selected = all.filter { filter == null || it.relativeTo(base).invariantSeparatorsPath.contains(filter) }
    check(selected.isNotEmpty()) { "No original source fixtures selected by $filter" }
    val profile = when (val requested = System.getProperty("jadxmp.upstream.metadata", "none")) {
        "none" -> null
        ClasspathMetadataProfile.ID -> ClasspathMetadataProfile.load(UpstreamJavaInventory.fixtureClasspath)
        else -> error("Unknown metadata profile: $requested")
    }
    val journal = UpstreamRoundTripJournal(report, all.size,
        selected.map { it.relativeTo(base).invariantSeparatorsPath }, filter, profile?.evidence.orEmpty())
    val runner = UpstreamJavaRoundTrips(kotlin = KotlinJadxmpDecompiler(profile?.index)::decompileKotlin)
    selected.forEachIndexed { index, file ->
        val name = file.relativeTo(base).invariantSeparatorsPath
        println("Measuring ${index + 1}/${selected.size}: $name")
        journal.record(runner.measure(name, file.readBytes()))
    }
    print(journal.finish())
    println("Full input hashes, transformations and failure diagnostics: ${report.absolutePath}")
}
