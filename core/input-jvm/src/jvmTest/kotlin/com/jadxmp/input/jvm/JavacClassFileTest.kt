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
                        public static int dense(int value) {
                            return switch (value) { case 0 -> 3; case 1 -> 5; case 2 -> 7; default -> 9; };
                        }
                        public static int sparse(int value) {
                            return switch (value) { case -10000 -> 3; case 2 -> 5; case 10000 -> 7; default -> 9; };
                        }
                        public static int length(String value) {
                            try { return value.length(); }
                            catch (NullPointerException exception) { return -1; }
                        }
                        public String render(List<? extends Number> values) {
                            return values.stream().map(Object::toString).reduce("", (a, b) -> a + b);
                        }
                    }
                """.trimIndent())
            }
            val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "JDK required for javac interop test" }
            assertEquals(0, compiler.run(null, null, null, "--release", "17", "-g", "-parameters", "-d", directory.path, source.path))
            val classBytes = directory.resolve("sample/Example.class").readBytes()
            val file = ClassFileParser.parse(classBytes)
            assertEquals("sample/Example", file.name)
            assertEquals("java/lang/Record", file.superName)
            assertEquals(listOf("java/io/Serializable"), file.interfaces)
            assertEquals("J", file.fields.single { it.name == "MIN" }.descriptor)
            assertEquals("(Ljava/util/List;)Ljava/lang/String;", file.methods.single { it.name == "render" }.descriptor)
            assertTrue(file.methods.single { it.name == "render" }.attributes.any { it.name == "Code" })
            assertTrue(file.attributes.map { it.name }.containsAll(listOf("Record", "Signature", "SourceFile", "BootstrapMethods")))
            val parsedCode = file.methods.flatMap { method ->
                method.attributes.filter { it.name == "Code" }.map { attribute ->
                    JvmCodeAttribute.parse(attribute, file.constants).also { code ->
                        val instructions = code.decodeInstructions()
                        assertEquals(code.bytes.size, instructions.last().offset + instructions.last().size)
                        assertTrue(code.bytes.contentEquals(classBytes.copyOfRange(code.codeOffset, code.codeOffset + code.bytes.size)))
                        for (nested in code.attributes) {
                            assertTrue(nested.bytes.contentEquals(classBytes.copyOfRange(nested.offset, nested.offset + nested.bytes.size)))
                        }
                    }
                }
            }
            assertTrue(parsedCode.isNotEmpty())
            val opcodes = parsedCode.flatMap { it.decodeInstructions() }.map { it.opcode }
            assertTrue(opcodes.containsAll(listOf(0xaa, 0xab, 0xba))) // table, lookup, invokedynamic
            assertEquals(listOf("java/lang/NullPointerException"), parsedCode.flatMap { it.handlers }.map { it.catchType })
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
