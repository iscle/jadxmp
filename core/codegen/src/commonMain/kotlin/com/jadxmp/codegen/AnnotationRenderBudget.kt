package com.jadxmp.codegen

/** Shared by every annotation writer in one output pass, including nested classes. */
class AnnotationRenderBudget(private var nodes: Int = 20_000, private var characters: Int = 1_000_000) {
    fun enter(depth: Int) {
        if (depth > 32 || nodes <= 0) exhausted()
        nodes--
    }

    /** Charge before copying, escaping, hashing or joining supplied text. */
    fun text(length: Int, expansion: Int = 1) {
        if (length < 0 || expansion <= 0 || length.toLong() * expansion > characters) exhausted()
        characters -= length * expansion
    }

    private fun exhausted(): Nothing {
        nodes = 0
        characters = 0
        throw IllegalArgumentException("annotation source rendering budget exhausted")
    }
}
