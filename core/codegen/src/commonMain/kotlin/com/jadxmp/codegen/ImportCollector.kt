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
 * render as `Outer.Inner`. Determinism: [imports] is returned sorted.
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
        val normalized = fullName.replace('$', '.')
        val topLevelBinary = fullName.substringBefore('$')
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

    private fun <K, V> HashMap<K, V>.putIfAbsent(key: K, value: V) {
        if (!containsKey(key)) put(key, value)
    }
}
