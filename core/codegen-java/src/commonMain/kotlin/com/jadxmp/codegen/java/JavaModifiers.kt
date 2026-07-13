package com.jadxmp.codegen.java

/**
 * JVM access-flag decoding and canonical Java modifier ordering. **jadx: AccessInfo**
 *
 * The Java Language Specification recommends a fixed modifier order; emitting it consistently keeps
 * output deterministic and diff-stable.
 */
internal object JavaModifiers {
    const val PUBLIC = 0x0001
    const val PRIVATE = 0x0002
    const val PROTECTED = 0x0004
    const val STATIC = 0x0008
    const val FINAL = 0x0010
    const val SYNCHRONIZED = 0x0020
    const val VOLATILE = 0x0040
    const val TRANSIENT = 0x0080
    const val NATIVE = 0x0100
    const val INTERFACE = 0x0200
    const val ABSTRACT = 0x0400
    const val STRICTFP = 0x0800
    const val ANNOTATION = 0x2000
    const val ENUM = 0x4000

    fun has(flags: Int, mask: Int): Boolean = flags and mask != 0

    /** Modifiers valid on a class declaration, in canonical order, with a trailing space each. */
    fun forClass(flags: Int): String = buildModifiers {
        visibility(flags)
        // 'static'/'final'/'abstract' — but never both abstract and final; emit what is set.
        add(flags, ABSTRACT, "abstract")
        add(flags, STATIC, "static")
        add(flags, FINAL, "final")
        add(flags, STRICTFP, "strictfp")
    }

    /** Modifiers valid on a field declaration. */
    fun forField(flags: Int): String = buildModifiers {
        visibility(flags)
        add(flags, STATIC, "static")
        add(flags, FINAL, "final")
        add(flags, TRANSIENT, "transient")
        add(flags, VOLATILE, "volatile")
    }

    /** Modifiers valid on a method declaration. */
    fun forMethod(flags: Int): String = buildModifiers {
        visibility(flags)
        add(flags, ABSTRACT, "abstract")
        add(flags, STATIC, "static")
        add(flags, FINAL, "final")
        add(flags, SYNCHRONIZED, "synchronized")
        add(flags, NATIVE, "native")
        add(flags, STRICTFP, "strictfp")
    }

    private class Builder(val sb: StringBuilder) {
        fun visibility(flags: Int) {
            when {
                has(flags, PUBLIC) -> sb.append("public ")
                has(flags, PRIVATE) -> sb.append("private ")
                has(flags, PROTECTED) -> sb.append("protected ")
            }
        }

        fun add(flags: Int, mask: Int, keyword: String) {
            if (has(flags, mask)) sb.append(keyword).append(' ')
        }
    }

    private inline fun buildModifiers(block: Builder.() -> Unit): String {
        val sb = StringBuilder()
        Builder(sb).block()
        return sb.toString()
    }
}
