package com.jadxmp.oracle

import java.io.ByteArrayOutputStream
import java.util.ServiceLoader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import jadx.api.plugins.JadxPlugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReferenceJvmInputTest {
    private val source = DecompiledClass("jvminput.Sample", """
        package jvminput;
        public class Sample {
            public static int sum(int a, int b) { return a + b; }
            public static boolean check() { return sum(3, 4) == 7 && sum(Integer.MAX_VALUE, 1) == Integer.MIN_VALUE; }
        }
    """.trimIndent())

    @Test
    fun referenceDiscoversBothOriginalInputPlugins() {
        val names = ServiceLoader.load(JadxPlugin::class.java).map { it.javaClass.name }.toSet()
        assertTrue("jadx.plugins.input.dex.DexInputPlugin" in names)
        assertTrue("jadx.plugins.input.java.JavaInputPlugin" in names)
    }

    @Test
    fun originalJvmClassRoundTripsWithoutDexConversion() {
        JavaCompilation.compile(listOf(source), release = 11).use { compiled ->
            assertTrue(compiled.result.success, compiled.result.diagnostics.toString())
            assertRoundTrip("renamed.bin", compiled.output.resolve("jvminput/Sample.class").readBytes())
        }
    }

    @Test
    fun originalClassOnlyJarRoundTripsWithoutDexConversion() {
        JavaCompilation.compile(listOf(source), release = 11).use { compiled ->
            assertTrue(compiled.result.success, compiled.result.diagnostics.toString())
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("jvminput/Sample.class"))
                zip.write(compiled.output.resolve("jvminput/Sample.class").readBytes())
                zip.closeEntry()
            }
            assertRoundTrip("classes.jar", bytes.toByteArray())
        }
    }

    private fun assertRoundTrip(name: String, bytes: ByteArray) {
        val reference = ReferenceDecompiler()
        val result = reference.decompile(name, bytes)
        assertEquals(listOf(source.fullName), result.classes.map { it.fullName }, result.toString())
        assertEquals(SignalScore(true, true, true), SignalScore.of(result, reference.errorMarkers,
            original = JavaCheckFixture(listOf(source), source.fullName)), result.classes.joinToString { it.source })
    }
}
