package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SmaliAssemblerTest {

    private val goodSmali = """
        .class public LHelloSmali;
        .super Ljava/lang/Object;

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public answer()I
            .registers 1
            const/16 v0, 0x2a
            return v0
        .end method
    """.trimIndent()

    /** A known-good smali assembles to a dex that BOTH decompilers can parse into the class. */
    @Test
    fun assemblesGoodSmaliToParseableDex(@TempDir dir: File) {
        val smali = File(dir, "HelloSmali.smali").apply { writeText(goodSmali) }

        val result = SmaliAssembler.assemble(smali)
        assertTrue(result.ok, "good smali should assemble; error=${result.error}")
        assertNotNull(result.dex, "dex bytes expected")
        val dex = result.dex!!
        // dex magic "dex\n"
        assertTrue(dex.size > 4 && dex[0] == 0x64.toByte() && dex[1] == 0x65.toByte(), "should be a dex")

        // Reference (jadx) parses it (jadx moves default-package classes under `defpackage`).
        val ref = ReferenceDecompiler().decompile("HelloSmali", dex)
        assertTrue(ref.classes.any { it.simpleName == "HelloSmali" }, "jadx should decompile HelloSmali: ${ref.classes.map { it.fullName }}")

        // Candidate (jadxmp core:api) parses it too (straight-line, so it should decompile).
        val cand = JadxmpDecompiler().decompile("HelloSmali", dex)
        assertTrue(cand.classes.any { it.simpleName == "HelloSmali" }, "jadxmp should decompile HelloSmali: ${cand.classes.map { it.fullName }}")
    }

    /** Malformed smali is COUNTED as a failure — never crashes, never silently produces a dex. */
    @Test
    fun malformedSmaliIsReportedAsFailureNotCrash(@TempDir dir: File) {
        val bad = File(dir, "Broken.smali").apply {
            writeText(".class public LBroken;\n.super Ljava/lang/Object;\n.method public oops(\n  this is not smali\n")
        }

        val result = SmaliAssembler.assemble(bad)
        assertFalse(result.ok, "malformed smali must not report success")
        assertNotNull(result.error, "a failure reason must be recorded (not silently dropped)")
    }

    @Test
    fun emptyFileFailsCleanly(@TempDir dir: File) {
        val empty = File(dir, "Empty.smali").apply { writeText("") }
        val result = SmaliAssembler.assemble(empty)
        // Either it produces no class-bearing dex or reports an error; it must not claim a real success+crash.
        if (result.ok) {
            // An empty smali may assemble to an empty dex; that's acceptable as long as it didn't crash.
            assertNotNull(result.dex)
        } else {
            assertNotNull(result.error)
        }
    }
}
