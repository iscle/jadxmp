package com.jadxmp.oracle

import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.io.File
import java.nio.file.Files

/**
 * Assembles `.smali` source into an in-memory `.dex` using `com.android.tools.smali:smali` — the same
 * assembler (and default API level 27) jadx itself uses, so the dex we hand to both decompilers is
 * exactly what jadx's own smali tests would produce.
 *
 * Assembly is **per file**: each `.smali` is a complete standalone class, and references to sibling
 * test classes become ordinary external type-refs in the dex (unresolved at assembly time, which is
 * legal) — so a one-class dex decompiles identically-in-context on both sides. Per-file granularity
 * also isolates a bad sample and gives the scoreboard one row per smali file.
 */
object SmaliAssembler {

    /** jadx's default smali API level (`SmaliInputOptions` `defaultValue(27)`). */
    const val DEFAULT_API_LEVEL: Int = 27

    /** [dex] bytes on success, or an [error] describing why assembly failed (never both null-and-null). */
    data class Result(val dex: ByteArray?, val error: String?) {
        val ok: Boolean get() = dex != null
    }

    fun assemble(smaliFile: File, apiLevel: Int = DEFAULT_API_LEVEL): Result {
        val outDex = Files.createTempFile("jadxmp-smali", ".dex").toFile()
        return try {
            val options = SmaliOptions().apply {
                this.apiLevel = apiLevel
                this.outputDexFile = outDex.absolutePath
            }
            // smali prints parse/compile diagnostics to stderr; silence it so a bad sample doesn't
            // flood the scoreboard output (we surface a concise per-file error instead).
            val assembled = suppressingStdErr {
                runCatching { Smali.assemble(options, listOf(smaliFile.absolutePath)) }
            }
            when {
                assembled.isFailure -> Result(null, assembled.exceptionOrNull()?.let { it.message ?: it.toString() } ?: "assemble threw")
                assembled.getOrThrow() == false -> Result(null, "smali reported assembly errors")
                !outDex.isFile || outDex.length() == 0L -> Result(null, "no dex produced")
                else -> Result(outDex.readBytes(), null)
            }
        } finally {
            outDex.delete()
        }
    }

    // NOTE: swaps System.err PROCESS-GLOBALLY for the duration of [block]. Safe for the single-threaded
    // scoreboard runner; if smali assembly is ever parallelized this must be revisited (a concurrent
    // task would lose its stderr, or restore a stale stream).
    private inline fun <T> suppressingStdErr(block: () -> T): T {
        val original = System.err
        return try {
            System.setErr(java.io.PrintStream(java.io.OutputStream.nullOutputStream()))
            block()
        } finally {
            System.setErr(original)
        }
    }
}
