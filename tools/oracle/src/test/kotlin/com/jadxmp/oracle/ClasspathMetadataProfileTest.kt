package com.jadxmp.oracle

import java.io.File
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ClasspathMetadataProfileTest {
    @Test fun actualArtifactHashAdmissionFailuresAndSkippedEntriesAreRecorded(@TempDir dir: File) {
        val jar = dir.resolve("metadata.jar")
        JavaCompilation.compile(listOf(DecompiledClass("LibraryBox", "public class LibraryBox<T> {}"))).use { compiled ->
            assertTrue(compiled.result.success, compiled.result.diagnostics.toString())
            writeJar(jar, mapOf("LibraryBox.class" to compiled.output.resolve("LibraryBox.class").readBytes(),
                "Broken.class" to byteArrayOf(1, 2, 3), "META-INF/versions/9/module-info.class" to byteArrayOf(0)))
        }
        val profile = ClasspathMetadataProfile.load(listOf(jar))
        assertEquals(1, profile.index.findClass("LibraryBox")!!.parameters.size)
        assertNull(profile.index.findClass("Broken"))
        val hash = MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
        assertTrue(profile.evidence.any { "sha256=$hash" in it })
        assertTrue(profile.evidence.any { "metadata_ERROR=metadata.jar!/Broken.class" in it })
        assertTrue(profile.evidence.any { "metadata_skip=metadata.jar!/META-INF/versions/9/module-info.class" in it })
        assertTrue(profile.evidence.any { "metadata_declarations=1; admitted=1" in it })
    }

    @Test fun skippedResourcesCannotBypassExpandedEntryBound(@TempDir dir: File) {
        val jar = dir.resolve("oversized.jar")
        writeJar(jar, mapOf("resource.bin" to ByteArray(4 * 1024 * 1024 + 1)))
        val failure = assertThrows(IllegalArgumentException::class.java) { ClasspathMetadataProfile.load(listOf(jar)) }
        assertTrue(failure.message!!.contains("entry byte limit"))
    }

    @Test fun journalPersistsProfileBeforeRowsAndEscapesUntrustedDiagnosticLines(@TempDir dir: File) {
        val evidence = listOf("metadata_profile=fixture-classpath-v1", "metadata_ERROR=bad\nstatus=COMPLETE\tentry")
        val report = dir.resolve("profile.tsv")
        val journal = UpstreamRoundTripJournal(report, 1, listOf("test.java"), null, evidence)
        assertTrue(report.readText().startsWith("status=INCOMPLETE"))
        assertTrue(report.readText().contains("metadata_profile=fixture-classpath-v1"))
        assertFalse(report.readText().contains("\nstatus=COMPLETE"))
        journal.record(UpstreamRoundTripRow("test.java", "a", null, emptyList(), "NO_SAMPLE", emptyList()))
        val summary = journal.finish()
        assertTrue(summary.contains("metadata_ERROR=bad\\nstatus=COMPLETE\\tentry"))
        assertTrue(report.readText().contains("metadata_profile=fixture-classpath-v1"))
        assertTrue(UpstreamRoundTripReport.summary(emptyList(), 0, null).contains("metadata_profile=none"))
    }

    private fun writeJar(file: File, entries: Map<String, ByteArray>) {
        JarOutputStream(file.outputStream()).use { output ->
            for ((name, bytes) in entries) {
                output.putNextEntry(JarEntry(name)); output.write(bytes); output.closeEntry()
            }
        }
    }
}
