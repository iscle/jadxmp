package com.jadxmp.oracle

import org.assertj.core.api.Assertions
import java.io.File
import java.security.MessageDigest

internal enum class OriginalFixtureStatus { COMPILE_FAILED, COMPILED_NO_CHECK, CHECK_PASSED, CHECK_FAILED, CHECK_MISSING, CHECK_TIMEOUT }

internal data class OriginalFixtureResult(val status: OriginalFixtureStatus, val diagnostics: List<String> = emptyList())

/** Original-input validation only. These results make no claim about either decompiler. */
internal object UpstreamJavaInventory {
    val fixtureClasspath: List<File> = listOf(File(Assertions::class.java.protectionDomain.codeSource.location.toURI()))

    fun validate(sample: ExtractedJavaSample): OriginalFixtureResult {
        JavaCompilation.compile(listOf(sample.source), fixtureClasspath, release = 11).use { compiled ->
            if (!compiled.result.success) return OriginalFixtureResult(OriginalFixtureStatus.COMPILE_FAILED, compiled.result.diagnostics)
            if (!sample.hasCheck) return OriginalFixtureResult(OriginalFixtureStatus.COMPILED_NO_CHECK)
            val status = when (CheckExecutor.run(sample.checkClass, listOf(compiled.output) + fixtureClasspath)) {
                CheckStatus.PASSED -> OriginalFixtureStatus.CHECK_PASSED
                CheckStatus.FAILED -> OriginalFixtureStatus.CHECK_FAILED
                CheckStatus.MISSING_CHECK -> OriginalFixtureStatus.CHECK_MISSING
                CheckStatus.TIMEOUT -> OriginalFixtureStatus.CHECK_TIMEOUT
            }
            return OriginalFixtureResult(status)
        }
    }

    fun verifyReference(reference: File) = PinnedReference.verify(reference)
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun cell(value: String): String = value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")

/** Exhaustive inventory of the pinned integration sources; unsupported inputs stay in the report. */
fun main() {
    val reference = Corpus.root().parentFile.resolve("reference/jadx")
    UpstreamJavaInventory.verifyReference(reference)
    val base = reference.resolve("jadx-core/src/test/java/jadx/tests/integration")
    check(base.isDirectory) { "Missing upstream integration sources: $base" }
    val files = base.walkTopDown().filter { it.isFile && it.extension == "java" }.sortedBy { it.path }.toList()
    check(files.isNotEmpty()) { "No upstream Java inputs" }
    println("Original fixture inventory; baseline=${ReferenceDecompiler.DEFAULT_JADX_VERSION}; javac release=11; AssertJ=3.27.7")
    println("This measures original fixture viability, NOT decompilation parity.")
    println("input\tinput_sha256\textracted_sha256\tstatus\ttransformations\tdiagnostics")
    val counts = sortedMapOf<String, Int>()
    for (file in files) {
        val bytes = file.readBytes()
        val extraction = UpstreamJavaExtractor.extract(bytes.toString(Charsets.UTF_8))
        val sample = extraction.sample
        val validation = sample?.let { UpstreamJavaInventory.validate(it) }
        val status = validation?.status?.name ?: extraction.status.name
        counts[status] = (counts[status] ?: 0) + 1
        println(listOf(
            file.relativeTo(base).invariantSeparatorsPath, sha256(bytes),
            sample?.let { sha256(it.source.source.toByteArray(Charsets.UTF_8)) }.orEmpty(), status,
            sample?.transformations?.joinToString("; ").orEmpty(),
            (extraction.diagnostics + validation?.diagnostics.orEmpty()).joinToString("; "),
        ).joinToString("\t") { cell(it) })
    }
    println("TOTAL=${files.size} ${counts.entries.joinToString { "${it.key}=${it.value}" }}")
}
