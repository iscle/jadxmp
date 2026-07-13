package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.attr.AttrKey

/**
 * Kotlin-backend-specific codegen attribute keys — Kotlin idioms the shared `core:codegen` keys don't
 * cover. The producing analysis (a later Kotlin-idiom pass) sets these; the backend reads them. Tests
 * set them directly on hand-built IR.
 */
object KotlinCodegenKeys {
    /**
     * On an `IrMethod`: this function overrides a supertype member, so it is emitted with the
     * `override` modifier (mandatory in Kotlin). Whether a method overrides needs the class hierarchy,
     * which lives in the pipeline; the backend additionally recognises the always-overriding
     * `Any` members (`toString`/`hashCode`/`equals`) on its own.
     */
    val IS_OVERRIDE: AttrKey<Boolean> = AttrKey("codegen.kotlin.isOverride")
}
