package com.jadxmp.oracle

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Shared provenance guard for tools that read original upstream fixture sources. */
internal object PinnedReference {
    fun verify(reference: File) {
        check(git(reference, "rev-parse", "HEAD") == ReferenceDecompiler.DEFAULT_JADX_VERSION) { "Reference checkout differs from the original pin" }
        check(git(reference, "status", "--porcelain", "--untracked-files=all").isEmpty()) { "Reference checkout must be clean" }
    }

    private fun git(directory: File, vararg arguments: String): String {
        val output = Files.createTempFile("jadxmp-reference-git", ".txt").toFile()
        var process: Process? = null
        try {
            process = ProcessBuilder(listOf("git", "-C", directory.absolutePath) + arguments)
                .redirectOutput(output).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) { "Reference Git verification failed" }
            return output.readText().trim()
        } finally {
            process?.let { if (it.isAlive) it.destroyForcibly() }
            output.delete()
        }
    }
}
