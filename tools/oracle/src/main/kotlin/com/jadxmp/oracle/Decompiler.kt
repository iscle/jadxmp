package com.jadxmp.oracle

import java.io.File

/** One decompiled class: its fully-qualified name and the emitted Java source. */
data class DecompiledClass(val fullName: String, val source: String) {
    /** Simple (unqualified) class name — the expected `.java` file base name. */
    val simpleName: String get() = fullName.substringAfterLast('.')
}

/** Everything one decompiler produced for one input. */
data class DecompilationResult(
    /** Human-readable label for the input (a file name, or an assembled-smali sample path). */
    val inputName: String,
    val classes: List<DecompiledClass>,
    /**
     * Errors the decompiler itself reported (jadx's `getErrorsCount()`, jadxmp's summed
     * `ClassMetadata.errorCount`) — its own *structured* no-error signal, distinct from the text scan.
     */
    val reportedErrors: Int,
)

/**
 * A decompiler under differential test. **Both** the reference (jadx) and jadxmp implement this one
 * interface, which is the whole point: the [Scoreboard] compares any two [Decompiler]s.
 *
 * The primary entry point is [decompile] on raw bytes, so an in-memory assembled-smali dex needs no
 * temp file; [decompileFile] is a convenience for the on-disk corpus binaries.
 */
interface Decompiler {
    /** Human-readable identifier for scoreboard output, e.g. `"jadx-1.5.6"` or `"jadxmp"`. */
    val name: String

    /**
     * This decompiler's own signal-1 failure markers (see [ErrorMarkers]). Decompiler-specific
     * because jadx's literal sentinels never appear in jadxmp's clean-room output and vice versa.
     */
    val errorMarkers: List<String>

    /** Decompile an in-memory container ([bytes], a `.dex`/`.apk`/`.jar`) labelled [name]. */
    fun decompile(name: String, bytes: ByteArray): DecompilationResult

    /** Convenience: decompile a container file from disk. */
    fun decompileFile(input: File): DecompilationResult = decompile(input.name, input.readBytes())
}
