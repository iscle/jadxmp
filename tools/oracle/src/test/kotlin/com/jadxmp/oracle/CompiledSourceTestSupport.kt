package com.jadxmp.oracle

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.ToolProvider
import org.junit.jupiter.api.Assertions.assertEquals

internal fun withCompiledClass(
    source: DecompiledClass,
    kotlin: Boolean,
    additionalClasspath: List<File> = emptyList(),
    action: (Class<*>) -> Unit,
) {
    withCompiledClasses(listOf(source), kotlin, additionalClasspath) { loader -> action(loader.loadClass(source.fullName)) }
}

internal fun withCompiledClasses(
    sources: List<DecompiledClass>,
    kotlin: Boolean,
    additionalClasspath: List<File> = emptyList(),
    action: (ClassLoader) -> Unit,
) {
    val dir = Files.createTempDirectory("jadxmp-compare-execution").toFile()
    try {
        val files = sources.map { source ->
            dir.resolve(source.fullName.replace('.', '/') + if (kotlin) ".kt" else ".java").apply {
                parentFile.mkdirs()
                writeText(source.source)
            }
        }
        val sourceText = sources.joinToString("\n\n") { it.source }
        val output = dir.resolve("classes").apply { mkdirs() }
        val diagnostics = ByteArrayOutputStream()
        if (kotlin) {
            val stdlib = File(Unit::class.java.protectionDomain.codeSource.location.toURI())
            val classpath = (additionalClasspath + stdlib).joinToString(File.pathSeparator) { it.absolutePath }
            val exit = K2JVMCompiler().exec(
                PrintStream(diagnostics), "-no-stdlib", "-no-reflect", "-classpath", classpath,
                "-jvm-target", "21", "-d", output.absolutePath, *files.map { it.absolutePath }.toTypedArray(),
            )
            assertEquals(ExitCode.OK, exit, "$diagnostics\n$sourceText")
        } else {
            val exit = ToolProvider.getSystemJavaCompiler().run(
                null, null, diagnostics, "-proc:none", "-d", output.absolutePath,
                "-classpath", additionalClasspath.joinToString(File.pathSeparator) { it.absolutePath },
                *files.map { it.absolutePath }.toTypedArray(),
            )
            assertEquals(0, exit, "$diagnostics\n$sourceText")
        }
        val urls = (listOf(output) + additionalClasspath).map { it.toURI().toURL() }.toTypedArray()
        URLClassLoader(urls, DecompiledClass::class.java.classLoader).use { loader ->
            action(loader)
        }
    } finally {
        dir.deleteRecursively()
    }
}
