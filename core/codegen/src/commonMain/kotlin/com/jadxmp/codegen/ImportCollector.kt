package com.jadxmp.codegen

/**
 * Collects the classes a source file references and decides, per class, whether it can be written with
 * its short name (adding an `import`) or must stay fully qualified because its short name clashes.
 * **jadx: ClassGen import handling / ImportGen**
 *
 * Rules, applied the first time a class is used (so results are stable for the rest of the file):
 *  - `java.lang.*` and classes in [currentPackage] need no import; the short name is used.
 *  - Otherwise the class's top-level simple name is claimed. The first claimant of a simple name gets
 *    the import and the short name; any later class with the same simple name but a different
 *    fully-qualified name stays fully qualified (no import) to avoid an ambiguous reference.
 *
 * Binary names (`Outer$Inner`) are accepted; the import is for the top-level class and nested types
 * render as `Outer.Inner`. A `$` immediately followed by a digit (`Outer$1`) is NOT a nesting
 * boundary — it is the compiler's anonymous/synthetic-class marker, which can never be referenced as
 * `Outer.1`; such a `$N` stays part of the simple name so the reference agrees with the class
 * declaration (whose top-level short name keeps the `$`) and recompiles. Determinism: [imports] is
 * returned sorted.
 */
class ImportCollector(private val currentPackage: String = "") {

    // simple top-level name -> the fully-qualified top-level class that owns it in this file.
    private val claimed = HashMap<String, String>()

    // top-level fully-qualified names to emit as `import` statements (excludes java.lang / same package).
    private val importsSet = LinkedHashSet<String>()

    /**
     * Register [fullName] and return the identifier to write for it (short if importable, else fully
     * qualified). [fullName] may use `.` or `$` between the outer and nested class.
     */
    fun useClass(fullName: String): String {
        val normalized = normalizeNesting(fullName)
        val topLevelBinary = topLevelBinaryOf(fullName)
        val topPackage = topLevelBinary.substringBeforeLast('.', "")
        val topSimple = topLevelBinary.substringAfterLast('.')
        // The class name without its package, keeping nested `.` separators (e.g. `Outer.Inner`).
        val display = normalized.removePrefix(if (topPackage.isEmpty()) "" else "$topPackage.")

        // Primitives / already-unqualified names: nothing to import.
        if (topPackage.isEmpty()) return normalized

        if (topPackage == "java.lang" || topPackage == currentPackage) {
            // No import needed, but still claim the simple name so a later clashing class stays qualified.
            claimed.putIfAbsent(topSimple, topLevelBinary)
            return if (claimed[topSimple] == topLevelBinary) display else normalized
        }

        val owner = claimed[topSimple]
        return when (owner) {
            null -> {
                claimed[topSimple] = topLevelBinary
                importsSet.add(topLevelBinary)
                display
            }
            topLevelBinary -> display
            else -> normalized // clash: keep fully qualified, no import
        }
    }

    /** The `import` targets (top-level FQNs), sorted for deterministic output. */
    fun imports(): List<String> = importsSet.sorted()

    /**
     * A `$` at [dollarIndex] is a nested-class boundary only when the next char could start a Java
     * identifier (a letter, `_`, or `$`). A `$` followed by a digit is the anonymous/synthetic-class
     * marker (`Outer$1`) and is kept literal.
     */
    private fun isNestingSeparator(name: String, dollarIndex: Int): Boolean {
        val next = dollarIndex + 1
        return next < name.length && name[next] !in '0'..'9'
    }

    /** Replace only the nesting-boundary `$` with `.` (anonymous/synthetic `$N` markers stay literal). */
    private fun normalizeNesting(name: String): String = buildString(name.length) {
        for (i in name.indices) {
            val c = name[i]
            append(if (c == '$' && isNestingSeparator(name, i)) '.' else c)
        }
    }

    /** The binary name up to the first nesting-boundary `$` (the whole name if there is none). */
    private fun topLevelBinaryOf(name: String): String {
        for (i in name.indices) {
            if (name[i] == '$' && isNestingSeparator(name, i)) return name.substring(0, i)
        }
        return name
    }

    private fun <K, V> HashMap<K, V>.putIfAbsent(key: K, value: V) {
        if (!containsKey(key)) put(key, value)
    }
}
