package com.jadxmp.oracle

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import java.nio.file.Files

/** Compile trusted source fixtures with javac 11 bytecode and the original baseline's D8 version. */
internal object JavaFixtureCompiler {
    fun dex(fixture: JavaCheckFixture): ByteArray {
        JavaCompilation.compile(fixture.classes, fixture.classpath, release = 11).use { original ->
            check(original.result.success) { "Original fixture does not compile: ${original.result.diagnostics}" }
            val out = Files.createTempDirectory("jadxmp-fixture-dex").toFile()
            try {
                val classes = original.output.walkTopDown().filter { it.isFile && it.extension == "class" }
                    .map { it.toPath() }.toList()
                val command = D8Command.builder().addProgramFiles(classes)
                    .setMode(CompilationMode.DEBUG).setMinApiLevel(26)
                    .setOutput(out.toPath(), OutputMode.DexIndexed)
                AndroidSdk.androidJar()?.let { command.addLibraryFiles(it.toPath()) }
                if (fixture.classpath.isNotEmpty()) command.addClasspathFiles(fixture.classpath.map { it.toPath() })
                D8.run(command.build())
                val outputs = out.listFiles { f -> f.extension == "dex" }.orEmpty()
                check(outputs.size == 1) { "Expected one DEX for a source fixture, got ${outputs.size}" }
                return outputs.single().readBytes()
            } finally {
                out.deleteRecursively()
            }
        }
    }
}
