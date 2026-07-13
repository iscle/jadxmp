package com.jadxmp.codegen.kotlin

/**
 * Deterministic Kotlin identifier sanitization. **jadx: reserved-name / deobfuscation renaming**
 *
 * Obfuscated (and cross-language) inputs can carry names that are illegal Kotlin identifiers — a
 * Kotlin hard keyword (`fun`, `is`, `object`, …), a name starting with a digit, an empty name, or a
 * name containing characters outside `[A-Za-z0-9_]`. Kotlin uniquely lets any such name be written
 * **backtick-quoted** (`` `is` ``, `` `fun` ``), which is exactly what [sanitize] emits for a reserved
 * word or a name that is otherwise a legal-charset identifier but reserved. A name containing
 * characters that are illegal even inside backticks (`.`, `/`, `<`, …) has those characters replaced
 * with `_` first.
 *
 * The mapping is a **pure function of the input** — the same original always yields the same result —
 * so a definition and every cross-reference to it agree on the spelling without coordination. It does
 * not attempt cross-name uniqueness (a pipeline concern).
 */
internal object KotlinIdentifiers {

    /** Sanitize a single identifier segment. Legal, non-reserved names are returned unchanged. */
    fun sanitize(name: String): String {
        if (name.isEmpty()) return "_"
        val legalCharset = name.all { isIdentifierChar(it) }
        if (legalCharset) {
            val digitStart = name[0] in '0'..'9'
            if (!digitStart && name !in HARD_KEYWORDS) return name
            // A reserved word, or a name that would otherwise be legal but starts with a digit:
            // backtick-quoting keeps it readable and valid ( `fun`, `1abc` ).
            return "`$name`"
        }
        // Characters illegal even inside backticks must be replaced; the result is then a plain name.
        val cleaned = buildString(name.length) {
            for (c in name) append(if (isIdentifierChar(c)) c else '_')
        }
        val fixed = if (cleaned.isEmpty() || cleaned[0] in '0'..'9') "_$cleaned" else cleaned
        return if (fixed in HARD_KEYWORDS) "`$fixed`" else fixed
    }

    /** Sanitize each dot-separated segment of a qualified name (package path, nested class), keeping dots. */
    fun sanitizeQualified(qualifiedName: String): String {
        if (qualifiedName.isEmpty()) return qualifiedName
        if ('.' !in qualifiedName) return sanitize(qualifiedName)
        return qualifiedName.split('.').joinToString(".") { sanitize(it) }
    }

    private fun isIdentifierChar(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_'

    /**
     * Kotlin **hard** keywords — the ones that cannot appear as an identifier unless backtick-quoted.
     * Soft/modifier keywords (`open`, `data`, `internal`, …) are contextual and legal as identifiers,
     * so they are deliberately absent.
     */
    private val HARD_KEYWORDS: Set<String> = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
        "try", "typealias", "typeof", "val", "var", "when", "while",
    )
}
