package com.jadxmp.input.dex

import com.jadxmp.input.AnnotationData
import com.jadxmp.input.ClassNesting
import com.jadxmp.input.EncodedValueType
import com.jadxmp.input.MethodRef

/** Translate DEX's system-annotation representation at the format boundary. */
internal class DexClassNesting(annotations: List<AnnotationData>) {
    val nesting: ClassNesting.Nested?
    val innerAccessFlags: Int?

    init {
        val inner = annotations.firstOrNull { it.annotationType == "Ldalvik/annotation/InnerClass;" }
        val innerName = inner?.values?.get("name")?.takeIf { it.type == EncodedValueType.STRING }?.value as? String
        innerAccessFlags = inner?.values?.get("accessFlags")?.takeIf { it.type == EncodedValueType.INT }?.value as? Int
        var found: ClassNesting.Nested? = null
        for (annotation in annotations) {
            val value = annotation.values["value"] ?: continue
            found = when (annotation.annotationType) {
                "Ldalvik/annotation/EnclosingClass;" -> if (value.type == EncodedValueType.TYPE) {
                    (value.value as? String)?.let { ClassNesting.Nested(it, innerName = innerName) }
                } else null
                "Ldalvik/annotation/EnclosingMethod;" -> if (value.type == EncodedValueType.METHOD) {
                    (value.value as? MethodRef)?.let { ClassNesting.Nested(it.declaringClassType, it, innerName) }
                } else null
                else -> null
            }
            if (found != null) break
        }
        // Missing enclosing annotations are not proof of a top-level class: stripped/older DEX files
        // still need the existing binary-name fallback, including InnerClass-only member modifiers.
        nesting = found
    }
}
