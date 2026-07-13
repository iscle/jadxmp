package com.jadxmp.oracle

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import java.io.File
import java.nio.file.Files

/**
 * The **reference oracle**: runs upstream jadx (from Maven Central, see build.gradle.kts) over a
 * dex/apk/jar and returns the decompiled Java per class. This is the yardstick jadxmp is measured
 * against — "at least as accurate as jadx" is defined by these outputs.
 *
 * Uses jadx's public `JadxArgs` / `JadxDecompiler` API only; the `jadx-dex-input` plugin on the
 * classpath is auto-discovered to handle `.dex`/`.apk`. Bytes are staged to a short-lived temp file
 * (jadx loads from files), named by content magic so jadx picks the right input plugin.
 */
class ReferenceDecompiler(jadxVersion: String = DEFAULT_JADX_VERSION) : Decompiler {

    override val name: String = "jadx-$jadxVersion"

    override val errorMarkers: List<String> = ErrorMarkers.JADX

    override fun decompile(name: String, bytes: ByteArray): DecompilationResult {
        val tmp = Files.createTempFile("jadxmp-oracle-ref", extensionFor(bytes)).toFile()
        try {
            tmp.writeBytes(bytes)
            val args = JadxArgs().apply {
                setInputFile(tmp)
                // Single-threaded for deterministic, reproducible oracle runs.
                threadsCount = 1
            }
            JadxDecompiler(args).use { jadx ->
                jadx.load()
                val classes = jadx.classes.map { DecompiledClass(it.fullName, it.code) }
                return DecompilationResult(name, classes, jadx.errorsCount)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun extensionFor(bytes: ByteArray): String = when {
        // "dex\n" magic
        bytes.size >= 4 && bytes[0] == 0x64.toByte() && bytes[1] == 0x65.toByte() &&
            bytes[2] == 0x78.toByte() && bytes[3] == 0x0a.toByte() -> ".dex"
        // "PK" zip magic (apk/jar/zip)
        bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() -> ".apk"
        else -> ".bin"
    }

    companion object {
        /** Kept in sync with the jadx coordinates pinned in build.gradle.kts. */
        const val DEFAULT_JADX_VERSION: String = "1.5.6"
    }
}
