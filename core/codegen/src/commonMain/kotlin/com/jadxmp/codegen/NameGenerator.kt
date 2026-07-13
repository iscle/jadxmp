package com.jadxmp.codegen

import com.jadxmp.ir.type.IrType
import com.jadxmp.ir.type.TypeKind

/**
 * Deterministic identifier generation for one method: turns a type into a readable variable name and
 * guarantees uniqueness within the method. **jadx: NameGen**
 *
 * Names are assigned in first-encounter order and cached by object identity, so the same variable
 * always renders with the same name and output is reproducible. This does not implement full
 * source-name recovery (debug info / heuristics) — that is the pipeline's job; here it is a stable,
 * clash-free fallback and a home for the type→name heuristic both backends share.
 */
class NameGenerator {
    private val used = HashSet<String>()
    private val counters = HashMap<String, Int>()

    /** Reserve [name] exactly (e.g. a real parameter name from debug info) and return it. */
    fun reserve(name: String): String {
        used.add(name)
        return name
    }

    /** Whether [name] is already taken. */
    fun isUsed(name: String): Boolean = name in used

    /**
     * Return a unique identifier close to [base]: [base] itself if free, else `base2`, `base3`, ….
     */
    fun unique(base: String): String {
        val root = sanitize(base)
        if (root !in used) {
            used.add(root)
            return root
        }
        var n = counters.getOrElse(root) { 2 }
        var candidate = "$root$n"
        while (candidate in used) {
            n++
            candidate = "$root$n"
        }
        counters[root] = n + 1
        used.add(candidate)
        return candidate
    }

    /** A unique variable name suggested from [type]. */
    fun forType(type: IrType): String = unique(suggestBase(type))

    private fun sanitize(name: String): String {
        if (name.isEmpty()) return "v"
        val sb = StringBuilder(name.length)
        for ((i, c) in name.withIndex()) {
            val ok = if (i == 0) c.isLetter() || c == '_' || c == '$' else c.isLetterOrDigit() || c == '_' || c == '$'
            sb.append(if (ok) c else '_')
        }
        val cleaned = sb.toString()
        return if (cleaned in JAVA_KEYWORDS) "${cleaned}Var" else cleaned
    }

    companion object {
        /**
         * The base name for a variable of [type], following jadx-style conventions: a single letter for
         * primitives, the de-capitalised simple name for objects, and an `arr`-suffixed element name for
         * arrays. Public so both backends and tests share one convention.
         */
        fun suggestBase(type: IrType): String = when (type) {
            is IrType.Primitive -> when (type.kind) {
                TypeKind.BOOLEAN -> "z"
                TypeKind.CHAR -> "c"
                TypeKind.BYTE -> "b"
                TypeKind.SHORT -> "s"
                TypeKind.INT -> "i"
                TypeKind.FLOAT -> "f"
                TypeKind.LONG -> "j"
                TypeKind.DOUBLE -> "d"
                TypeKind.VOID -> "v"
                else -> "v"
            }
            is IrType.Object -> objectBase(type.className)
            is IrType.ArrayType -> suggestBase(type.element) + "Arr"
            is IrType.TypeVariable -> deCapitalize(type.name)
            else -> "v"
        }

        private fun objectBase(className: String): String {
            val simple = className.substringAfterLast('.').substringAfterLast('$')
            return when (simple) {
                "String" -> "str"
                "Object" -> "obj"
                "Class" -> "cls"
                "Integer" -> "num"
                "Boolean" -> "bool"
                else -> deCapitalize(simple)
            }
        }

        private fun deCapitalize(s: String): String {
            if (s.isEmpty()) return "v"
            val first = s[0].lowercaseChar()
            val base = first + s.substring(1)
            // Avoid names that are all-caps constants turning into something odd; ensure it starts alpha.
            return if (base[0].isLetter() || base[0] == '_') base else "v$base"
        }

        private val JAVA_KEYWORDS = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "var", "record", "yield",
        )
    }
}
