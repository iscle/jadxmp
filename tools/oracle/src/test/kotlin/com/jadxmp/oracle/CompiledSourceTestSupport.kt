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

internal fun withCompiledClass(source: DecompiledClass, kotlin: Boolean, action: (Class<*>) -> Unit) {
    val dir = Files.createTempDirectory("jadxmp-compare-execution").toFile()
    try {
        val file = dir.resolve("${source.simpleName}.${if (kotlin) "kt" else "java"}")
        file.writeText(source.source)
        val output = dir.resolve("classes").apply { mkdirs() }
        val diagnostics = ByteArrayOutputStream()
        if (kotlin) {
            val stdlib = File(Unit::class.java.protectionDomain.codeSource.location.toURI())
            val exit = K2JVMCompiler().exec(
                PrintStream(diagnostics), "-no-stdlib", "-no-reflect", "-classpath", stdlib.absolutePath,
                "-jvm-target", "21", "-d", output.absolutePath, file.absolutePath,
            )
            assertEquals(ExitCode.OK, exit, "$diagnostics\n${source.source}")
        } else {
            val exit = ToolProvider.getSystemJavaCompiler().run(
                null, null, diagnostics, "-proc:none", "-d", output.absolutePath, file.absolutePath,
            )
            assertEquals(0, exit, "$diagnostics\n${source.source}")
        }
        URLClassLoader(arrayOf(output.toURI().toURL()), DecompiledClass::class.java.classLoader).use { loader ->
            action(loader.loadClass(source.fullName))
        }
    } finally {
        dir.deleteRecursively()
    }
}
