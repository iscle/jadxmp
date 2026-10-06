package com.jadxmp.oracle

import java.io.File
import java.security.MessageDigest

/** One independently compiled fixture context; every physical source belongs to exactly one. */
internal data class SmaliFixtureContext(
    val id: String,
    val inputs: List<File>,
    val requiredTopLevelClasses: Set<String> = emptySet(),
) {
    val category: String get() = id.substringBefore('/')

    fun outputProblems(result: DecompilationResult): List<String> = buildList {
        val names = result.classes.map { it.fullName }
        val missing = requiredTopLevelClasses - names.toSet()
        if (missing.isNotEmpty()) add("missing original top-level classes: ${missing.sorted()}")
        if (names.size != names.toSet().size) add("duplicate generated top-level class names")
    }
}

/** Explicit original-test composition only. Unknown directories remain singleton measurements. */
internal object SmaliFixtureContexts {
    private const val ORIGINAL_PIN = "0e232fb3510ec86083af0055470163d3550957cd"
    private const val GROUP = "inline/TestMethodInline"
    private const val TEST = "jadx-core/src/test/java/jadx/tests/integration/inline/TestMethodInline.java"
    private const val TEST_HASH = "1c4c44a2d7902cebeba23ffa81119d3495f6419b2a176f9ec17d18fffaa25a1e"
    private val members = linkedMapOf(
        "$GROUP/A.smali" to "5f0a58c7209f9b03068ba59904a0235a1bde4de3ab24de01c3e68fbbcdef6d82",
        "$GROUP/B.smali" to "cd3bc43d6d2793abe87357fa6f31d883895cd7742731a2f731414162b7d7604a",
        "$GROUP/C.smali" to "85d57d787a372b57c2332acb667ce51ce263f672880d4198ce512b73edcb8678",
    )

    fun plan(
        inputs: List<File>,
        base: File = Corpus.smaliDir(),
        selectedCategories: Set<String> = emptySet(),
    ): List<SmaliFixtureContext> {
        val paths = inputs.map { it.relativeTo(base).invariantSeparatorsPath }
        require(paths.size == paths.toSet().size) { "Duplicate physical smali input" }
        val byPath = paths.zip(inputs).toMap()
        val grouped = paths.filter { it.startsWith("$GROUP/") }
        val result = mutableListOf<SmaliFixtureContext>()
        // Selection intent is independent of discovery: losing the entire registered directory
        // must not silently reduce full-corpus or inline-category measurements.
        val groupSelected = selectedCategories.isEmpty() || GROUP.substringBefore('/') in selectedCategories
        if (groupSelected || grouped.isNotEmpty()) {
            require(grouped.toSet() == members.keys) { "Original $GROUP context requires exactly ${members.keys}; found $grouped" }
            require(ReferenceDecompiler.DEFAULT_JADX_VERSION == ORIGINAL_PIN) { "Fixture grouping requires original baseline migration review" }
            val reference = Corpus.root().parentFile.resolve("reference/jadx")
            PinnedReference.verify(reference)
            require(hash(reference.resolve(TEST).readBytes()) == TEST_HASH) { "Original fixture-composition test changed: $TEST" }
            for ((path, expected) in members) {
                require(hash(byPath.getValue(path).readBytes()) == expected) { "Grouped fixture source changed: $path" }
            }
            result += SmaliFixtureContext(GROUP, members.keys.map(byPath::getValue), setOf("inline.A", "inline.other.B", "inline.other.C"))
        }
        for ((path, file) in byPath) {
            if (path !in members) result += SmaliFixtureContext(path, listOf(file))
        }
        check(result.sumOf { it.inputs.size } == inputs.size) { "Incomplete physical fixture coverage" }
        return result.sortedBy { it.id }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
