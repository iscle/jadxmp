package com.jadxmp.api

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F2: real multi-class concurrency. Decompiling the 8-class sample APK sequentially and in parallel
 * (parallelism > 1) must produce **byte-identical** output per class — the scheduler must not let
 * ordering or shared state perturb results. Uses two fresh [Decompiler] instances so neither run sees
 * the other's cache or lowered IR.
 */
class ApkParallelDeterminismTest {

    private fun apkBytes(): ByteArray {
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "corpus/binary/app-with-fake-dex.apk") }
            .firstOrNull { it.isFile }
            ?: error("app-with-fake-dex.apk not found under corpus/binary")
        return file.readBytes()
    }

    @Test
    fun parallelAndSequentialAgreeOnEveryClass() {
        val bytes = apkBytes()

        val sequential = Decompiler()
        // 8 classes are DISCOVERED, but 5 of them are `R.*` member classes that nest under `R`, so only
        // 3 top-level output units (BuildConfig, MainActivity, R) are emitted — one file per outer class.
        assertEquals(8, sequential.load("app.apk", bytes), "expected the 8-class sample")
        val seq = sequential.decompileAll().classes.associate { it.fullName to it.code }

        val parallel = Decompiler(DecompilerArgs(parallelism = 4))
        parallel.load("app.apk", bytes)
        val par = runBlocking { parallel.decompileAllParallel().classes }
            .associate { it.fullName to it.code }

        assertEquals(3, seq.size, "expected 3 top-level output units (R.* inners nest under R), got ${seq.size}")
        assertEquals(seq.keys, par.keys, "same set of classes from both paths")
        // The nested R.* classes must be emitted WITHIN R's single unit, not as their own files.
        val rCode = seq.entries.first { it.key.endsWith(".R") }.value
        assertTrue(rCode.contains("class R "), "R's unit should open the outer class")
        assertTrue(
            rCode.split("class ").size - 1 >= 2,
            "R's unit should also contain its nested classes (found only ${rCode.split("class ").size - 1})",
        )
        for ((name, code) in seq) {
            assertEquals(code, par[name], "class $name differs between sequential and parallel runs")
        }
    }
}
