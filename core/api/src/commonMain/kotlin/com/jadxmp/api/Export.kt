package com.jadxmp.api

import com.jadxmp.io.ZipWriter

/**
 * One file of an exported project: a relative, `/`-separated [path] (`com/example/Foo.java`,
 * `resources/AndroidManifest.xml`) and its raw [bytes]. Produced by [Decompiler.exportSources]; the
 * actual writing (a directory tree on desktop, a downloaded ZIP on web) is platform-side, so this type
 * carries only bytes and stays wasm-safe.
 *
 * The public constructor lets a UI layer rebuild these to hand back to [SourceArchive.zip] without a
 * second decompile.
 */
public class ExportedFile(
    public val path: String,
    public val bytes: ByteArray,
)

/**
 * Packages an exported project ([Decompiler.exportSources] output) into a single ZIP byte array via
 * `core:binary-io`'s [ZipWriter] — for platforms that can only hand the user one file (a browser
 * download, an android Downloads write). Pure and wasm-safe; the desktop shell writes a directory tree
 * from the [ExportedFile] list directly and never needs this.
 */
public object SourceArchive {
    /** Zip [files] (in order) into one archive; each entry is DEFLATEd when that shrinks it, else STORED. */
    public fun zip(files: List<ExportedFile>): ByteArray =
        ZipWriter.write(files.map { it.path to it.bytes })
}

/** Output file extension for a source [format]: Java `.java`, Kotlin `.kt`. */
internal fun OutputFormat.sourceExtension(): String = when (this) {
    OutputFormat.JAVA -> "java"
    OutputFormat.KOTLIN -> "kt"
}
