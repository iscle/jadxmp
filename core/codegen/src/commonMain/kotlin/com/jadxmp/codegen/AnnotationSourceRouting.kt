package com.jadxmp.codegen

import com.jadxmp.ir.annotation.IrAnnotation
import com.jadxmp.ir.annotation.IrAnnotationVisibility

/**
 * Source annotations and compiler metadata have different owners. Deferred routes preserve the
 * pre-existing feature boundary; they are NOT evidence of generic/nesting/Kotlin reflection parity.
 */
object AnnotationSourceRouting {
    enum class Route {
        DECLARATION, DEFAULTS, LEGACY_NESTING, LEGACY_SIGNATURE, LEGACY_THROWS,
        LEGACY_PARAMETER_NAMES, COMPILER_KOTLIN_METADATA, UNSUPPORTED,
    }

    fun route(annotation: IrAnnotation): Route {
        if (annotation.visibility == null) return Route.UNSUPPORTED
        if (annotation.type.className == "kotlin.Metadata") return Route.COMPILER_KOTLIN_METADATA
        if (annotation.visibility != IrAnnotationVisibility.SYSTEM) return Route.DECLARATION
        return when (annotation.type.className) {
            "dalvik.annotation.AnnotationDefault" -> Route.DEFAULTS
            "dalvik.annotation.InnerClass", "dalvik.annotation.EnclosingClass",
            "dalvik.annotation.EnclosingMethod", "dalvik.annotation.MemberClasses" -> Route.LEGACY_NESTING
            "dalvik.annotation.Signature" -> Route.LEGACY_SIGNATURE
            "dalvik.annotation.Throws" -> Route.LEGACY_THROWS
            "dalvik.annotation.MethodParameters" -> Route.LEGACY_PARAMETER_NAMES
            else -> Route.UNSUPPORTED
        }
    }
}
