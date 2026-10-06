package com.jadxmp.oracle

import java.io.File
import java.nio.file.Files
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider

/** Owns compiled fixture classes until the caller finishes recompilation/execution/DEX conversion. */
internal class JavaCompilation private constructor(
    private val workspace: File,
    val output: File,
    val result: RecompileResult,
) : AutoCloseable {
    override fun close() {
        workspace.deleteRecursively()
    }

    companion object {
        // Package descriptors are real expected binary units even when they carry no annotations.
        // This applies symmetrically to originals, reference output and candidate output. It does
        // not bypass the exact expected-class check below (empty/comment-only units still fail).
        private val baseOptions = listOf("-proc:none", "-Xpkginfo:always")
        val compilerProfile: String get() = "javac options=" + baseOptions.joinToString(" ")

        fun compile(
            classes: List<DecompiledClass>,
            classpath: List<File> = emptyList(),
            release: Int? = null,
        ): JavaCompilation {
            val workspace = Files.createTempDirectory("jadxmp-java-compile").toFile()
            val output = workspace.resolve("classes").apply { mkdirs() }
            fun failure(message: String) = JavaCompilation(workspace, output, RecompileResult(false, listOf(message)))
            if (classes.isEmpty()) return failure("no classes to compile")
            val compiler = ToolProvider.getSystemJavaCompiler()
                ?: return failure("No system Java compiler available (need a JDK, not a JRE)")
            try {
                val sourceDir = workspace.resolve("src").apply { mkdirs() }
                // Names originate in bytecode. Never let a malformed binary name escape the source tree.
                val invalid = classes.firstOrNull { cls ->
                    cls.fullName.split('.').any { part ->
                        part.isEmpty() || part.any { it in "/\\:<>\"|?*" || Character.isISOControl(it) }
                    }
                }
                if (invalid != null) return failure("Invalid source class name: ${invalid.fullName}")
                val sourceFiles = classes.map { cls ->
                    sourceDir.resolve(cls.fullName.replace('.', '/') + ".java").apply {
                        parentFile.mkdirs()
                        writeText(cls.source)
                    }
                }
                val diagnostics = DiagnosticCollector<JavaFileObject>()
                compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { fm ->
                    fm.setLocation(StandardLocation.CLASS_OUTPUT, listOf(output))
                    if (classpath.isNotEmpty()) fm.setLocation(StandardLocation.CLASS_PATH, classpath)
                    val options = buildList {
                        addAll(baseOptions)
                        if (release != null) { add("--release"); add(release.toString()) }
                    }
                    val ok = compiler.getTask(null, fm, diagnostics, options, null, fm.getJavaFileObjectsFromFiles(sourceFiles)).call()
                    val errors = diagnostics.diagnostics.filter { it.kind == javax.tools.Diagnostic.Kind.ERROR }
                        .map { "${it.source?.name ?: "?"}:${it.lineNumber}: ${it.getMessage(null)}" }.toMutableList()
                    for (cls in classes) {
                        if (!output.resolve(cls.fullName.replace('.', '/') + ".class").isFile) {
                            errors += "no .class produced for ${cls.fullName} (empty or comment-only source?)"
                        }
                    }
                    return JavaCompilation(workspace, output, RecompileResult(ok && errors.isEmpty(), errors))
                }
            } catch (t: Throwable) {
                workspace.deleteRecursively()
                throw t
            }
        }
    }
}
