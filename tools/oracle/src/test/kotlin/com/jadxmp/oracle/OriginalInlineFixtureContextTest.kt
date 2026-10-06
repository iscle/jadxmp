package com.jadxmp.oracle

import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Original pinned TestMethodInline loads A, B and package-private C together. No stubs are supplied. */
class OriginalInlineFixtureContextTest {
    @Test fun originalSiblingGroupCompilesAndExecutesWithBothDecompilerOutputs() {
        val directory = Corpus.smaliDir().resolve("inline/TestMethodInline")
        val files = listOf("A.smali", "B.smali", "C.smali").map(directory::resolve)
        files.forEach { assertTrue(it.isFile, it.path) }
        val dex = Files.createTempFile("jadxmp-original-inline-group", ".dex").toFile()
        try {
            val options = SmaliOptions().apply {
                apiLevel = SmaliAssembler.DEFAULT_API_LEVEL
                outputDexFile = dex.absolutePath
            }
            assertTrue(Smali.assemble(options, files.map { it.absolutePath }))
            val bytes = dex.readBytes()
            val outputs = listOf(
                false to ReferenceDecompiler().decompile("TestMethodInline", bytes),
                false to JadxmpDecompiler().decompile("TestMethodInline", bytes),
                true to KotlinJadxmpDecompiler().decompileKotlin("TestMethodInline", bytes),
            )
            for ((kotlin, result) in outputs) {
                assertEquals(0, result.reportedErrors, result.classes.joinToString { it.source })
                assertEquals(setOf("inline.A", "inline.other.B", "inline.other.C"), result.classes.map { it.fullName }.toSet())
                withCompiledClasses(result.classes, kotlin) { loader ->
                    val cls = loader.loadClass("inline.A")
                    val receiver = if (kotlin) cls.getField("Companion").get(null) else null
                    assertNull((receiver?.javaClass ?: cls).getMethod("useMth").invoke(receiver))
                }
            }
        } finally {
            dex.delete()
        }
    }
}
