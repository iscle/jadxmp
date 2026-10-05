package com.jadxmp.oracle

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal enum class CheckStatus { PASSED, FAILED, MISSING_CHECK, TIMEOUT }

/** A fresh JVM contains static state, System.exit and hangs; this is not an OS sandbox. */
internal object CheckExecutor {
    fun run(className: String, classpath: List<File>, timeoutMillis: Long = 5_000, kotlinCompanion: Boolean = false): CheckStatus {
        require(timeoutMillis > 0) { "check timeout must be positive" }
        val workspace = Files.createTempDirectory("jadxmp-check").toFile()
        val marker = workspace.resolve("result")
        var process: Process? = null
        try {
            val java = File(System.getProperty("java.home"), "bin/java" + if (File.separatorChar == '\\') ".exe" else "")
            // Gradle's test worker java.class.path does not contain the test's actual classloader URLs.
            val launcherClasspath = listOf(CheckProcess::class.java, Unit::class.java)
                .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }.distinct()
            process = ProcessBuilder(
                java.absolutePath, "-Xmx128m", "-ea", "-cp", launcherClasspath.joinToString(File.pathSeparator),
                CheckProcess::class.java.name, className, classpath.joinToString(File.pathSeparator) { it.absolutePath },
                marker.absolutePath, kotlinCompanion.toString(),
            ).directory(workspace).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) return CheckStatus.TIMEOUT
            // Exit 0 alone is insufficient: fixture System.exit(0) must not fabricate a passing check.
            if (process.exitValue() != 0 || !marker.isFile) return CheckStatus.FAILED
            return when (marker.readText()) {
                "passed" -> CheckStatus.PASSED
                "missing" -> CheckStatus.MISSING_CHECK
                else -> CheckStatus.FAILED
            }
        } finally {
            process?.let { child ->
                child.descendants().forEach { it.destroyForcibly() }
                if (child.isAlive) {
                    child.destroyForcibly()
                    child.waitFor(1, TimeUnit.SECONDS)
                }
            }
            workspace.deleteRecursively()
        }
    }
}
