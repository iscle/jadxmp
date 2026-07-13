package com.jadxmp.codegen.java

/**
 * Deterministic Java identifier sanitization. **jadx: reserved-name / deobfuscation renaming**
 *
 * Obfuscated (and hand-written) inputs can carry names that are illegal Java identifiers — a Java
 * reserved word (`do`, `int`, …), a name starting with a digit, an empty name, a lone `_`, or a name
 * containing characters outside `[A-Za-z0-9_$]`. Emitting them verbatim produces uncompilable output
 * (`public class do {`). [sanitize] maps any such name to a valid identifier.
 *
 * The mapping is a **pure function of the input**: the same original name always yields the same
 * result, with no shared state. That is what lets a definition (`class do` → `class doWord`) and every
 * cross-class reference to it agree on the sanitized spelling without coordination. It does not attempt
 * cross-class *uniqueness* — two distinct originals can still collide after sanitizing (e.g. `do` and
 * `do$`), which is a model-level (pipeline) renaming concern, not this pure text transform.
 *
 * The scheme cannot turn a legal, non-reserved name into a reserved one: legal inputs are returned
 * unchanged, and the reserved-word case only ever appends, so the result is never itself a keyword.
 */
internal object JavaIdentifiers {

    /** Sanitize a single identifier segment. Legal, non-reserved names are returned unchanged. */
    fun sanitize(name: String): String {
        var result = if (name.isEmpty()) {
            "_"
        } else {
            buildString(name.length) {
                for (c in name) append(if (isIdentifierChar(c)) c else '_')
            }
        }
        // A digit-start is a valid identifier *part* but not a valid *start*; prefix rather than drop it.
        if (result[0] in '0'..'9') result = "_$result"
        // Reserved words (and the lone `_`, reserved since Java 9) get a distinguishing suffix.
        if (result in RESERVED) result += SUFFIX
        return result
    }

    /** Sanitize each dot-separated segment of a qualified name (package path, nested class), keeping dots. */
    fun sanitizeQualified(qualifiedName: String): String {
        if (qualifiedName.isEmpty()) return qualifiedName
        if ('.' !in qualifiedName) return sanitize(qualifiedName)
        return qualifiedName.split('.').joinToString(".") { sanitize(it) }
    }

    private fun isIdentifierChar(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '$'

    // Suffix chosen so the result is never itself a reserved word (no keyword ends in "Word") and is
    // stable across calls.
    private const val SUFFIX = "Word"

    private val RESERVED: Set<String> = setOf(
        // JLS keywords
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while",
        // literals usable in identifier position that break as names
        "true", "false", "null",
        // contextual keywords that break when used as a type or in declarations
        "var", "record", "yield", "sealed", "permits", "non-sealed",
        // a lone underscore is a reserved keyword since Java 9
        "_",
    )
}
