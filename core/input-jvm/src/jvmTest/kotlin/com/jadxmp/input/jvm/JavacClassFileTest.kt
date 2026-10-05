package com.jadxmp.input.jvm

import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavacClassFileTest {
    @Test fun parsesRealJavacRecordsLambdasGenericsAndModules() {
        val directory = Files.createTempDirectory("jadxmp-class-parser").toFile()
        try {
            val source = directory.resolve("Example.java").apply {
                writeText("""
                    package sample;
                    import java.util.List;
                    public record Example<T>(T value) implements java.io.Serializable {
                        public static final long MIN = Long.MIN_VALUE;
                        public static final double ZERO = -0.0;
                        public static final String TEXT = "a\u0000\ud83d\ude00";
                        public String render(List<? extends Number> values) {
                            return values.stream().map(Object::toString).reduce("", (a, b) -> a + b);
                        }
                    }
                """.trimIndent())
            }
            val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "JDK required for javac interop test" }
            assertEquals(0, compiler.run(null, null, null, "--release", "17", "-g", "-parameters", "-d", directory.path, source.path))
            val file = ClassFileParser.parse(directory.resolve("sample/Example.class").readBytes())
            assertEquals("sample/Example", file.name)
            assertEquals("java/lang/Record", file.superName)
            assertEquals(listOf("java/io/Serializable"), file.interfaces)
            assertEquals("J", file.fields.single { it.name == "MIN" }.descriptor)
            assertEquals("(Ljava/util/List;)Ljava/lang/String;", file.methods.single { it.name == "render" }.descriptor)
            assertTrue(file.methods.single { it.name == "render" }.attributes.any { it.name == "Code" })
            assertTrue(file.attributes.map { it.name }.containsAll(listOf("Record", "Signature", "SourceFile", "BootstrapMethods")))
            val module = directory.resolve("module-info.java").apply { writeText("module sample.module { exports sample; }") }
            assertEquals(0, compiler.run(null, null, null, "--release", "17", "-d", directory.path, module.path, source.path))
            val moduleFile = ClassFileParser.parse(directory.resolve("module-info.class").readBytes())
            assertEquals("module-info", moduleFile.name)
            assertEquals(null, moduleFile.superName)
            assertTrue(moduleFile.attributes.any { it.name == "Module" })
        } finally {
            directory.deleteRecursively()
        }
    }
}
