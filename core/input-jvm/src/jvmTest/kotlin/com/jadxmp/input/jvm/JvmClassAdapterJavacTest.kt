package com.jadxmp.input.jvm

import com.jadxmp.io.ByteReaderException
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmClassAdapterJavacTest {
    @Test fun javacLocalTypeAnnotationsProduceMethodDiagnosticsAndKeepHealthySibling() {
        val directory = Files.createTempDirectory("jadxmp-native-annotations").toFile()
        try {
            val source = directory.resolve("NativeAnnotations.java").apply {
                writeText("""
                    import java.lang.annotation.*;
                    @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME) @interface Visible {}
                    @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.CLASS) @interface Invisible {}
                    public interface NativeAnnotations {
                        static int visible(int x) { @Visible int y = x + 1; return y; }
                        static int invisible(int x) { @Invisible int y = x + 1; return y; }
                        static int good(int x) { return x + 2; }
                    }
                """.trimIndent())
            }
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-g", "-d", directory.path, source.path))
            val cls = JvmInput.loadClass("NativeAnnotations.class", directory.resolve("NativeAnnotations.class").readBytes())
                .classes.single()
            for ((method, attribute) in listOf("visible" to "RuntimeVisibleTypeAnnotations", "invisible" to "RuntimeInvisibleTypeAnnotations")) {
                val error = assertFailsWith<ByteReaderException> { cls.methods.single { it.ref.name == method }.codeReader }
                assertTrue(error.message.orEmpty().contains(attribute), error.message)
            }
            assertTrue(cls.methods.single { it.ref.name == "good" }.codeReader != null)
        } finally {
            directory.deleteRecursively()
        }
    }
}
