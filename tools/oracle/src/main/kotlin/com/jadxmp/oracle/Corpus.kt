package com.jadxmp.oracle

import java.io.File

/**
 * Locates the fenced `corpus/` tree (see `corpus/README.md`). Used by both the runner and the tests
 * so there is a single source of truth for where inputs live regardless of the working directory a
 * task runs from.
 *
 * Resolution order: the `jadxmp.corpus` system property (absolute path to `corpus/`), else walk up
 * from the working directory until a `corpus/binary` directory is found.
 */
object Corpus {

    fun root(): File {
        System.getProperty("jadxmp.corpus")?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "corpus/binary").isDirectory) return File(dir, "corpus")
            dir = dir.parentFile
        }
        error(
            "Could not locate corpus/ (searched up from ${System.getProperty("user.dir")}). " +
                "Set -Djadxmp.corpus=/absolute/path/to/corpus",
        )
    }

    /** The `corpus/binary/` directory holding ready-to-run `.dex`/`.apk` samples. */
    fun binaryDir(): File = File(root(), "binary")

    /** All runnable binary inputs (`.dex`/`.apk`), sorted for deterministic runs. */
    fun binaryInputs(): List<File> =
        binaryDir().listFiles { f -> f.isFile && (f.extension == "dex" || f.extension == "apk") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Resolve one named binary sample, asserting it exists. */
    fun binaryFile(name: String): File = File(binaryDir(), name).also {
        require(it.isFile) { "Corpus binary not found: $it" }
    }

    /** The `corpus/smali/` tree of jadx-derived `.smali` inputs, grouped by construct in subdirectories. */
    fun smaliDir(): File = File(root(), "smali")

    /**
     * All `.smali` files under [smaliDir], optionally restricted to the given top-level [categories]
     * (e.g. `conditions`, `loops`). Sorted by relative path for deterministic runs.
     */
    fun smaliInputs(categories: Set<String> = emptySet()): List<File> {
        val base = smaliDir()
        if (!base.isDirectory) return emptyList()
        return base.walkTopDown()
            .filter { it.isFile && it.extension == "smali" }
            .filter { categories.isEmpty() || categoryOf(it) in categories }
            .sortedBy { it.relativeTo(base).invariantSeparatorsPath }
            .toList()
    }

    /** The construct category (top-level dir under `corpus/smali/`) a smali file belongs to. */
    fun categoryOf(smaliFile: File): String =
        smaliFile.relativeTo(smaliDir()).invariantSeparatorsPath.substringBefore('/')

    /** Sample label used in the scoreboard: path relative to `corpus/smali/`, e.g. `loops/TestLoop1.smali`. */
    fun smaliSampleName(smaliFile: File): String =
        smaliFile.relativeTo(smaliDir()).invariantSeparatorsPath
}
