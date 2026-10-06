package com.jadxmp.ir.annotation

import com.jadxmp.ir.type.IrType

/** Input-independent annotation metadata. Null visibility belongs only to nested values. */
data class IrAnnotation(
    val type: IrType.Object,
    val visibility: IrAnnotationVisibility?,
    /** Validated identity survives value failure; consumers must not infer identity from diagnostics. */
    val values: AnnotationMetadata<Map<String, IrAnnotationValue>>,
)

/** Ordered attachment entries; failure of one annotation does not erase its valid siblings. */
data class IrAnnotationSet(val entries: List<AnnotationMetadata<IrAnnotation>>)

enum class IrAnnotationVisibility { BUILD, RUNTIME, SYSTEM }

/** Typed values; unsupported encodings are reported by [AnnotationMetadata.Unavailable]. */
sealed class IrAnnotationValue {
    /** Integral value or raw IEEE bits, using the same convention as instruction literals. */
    data class Primitive(val bits: Long, val type: IrType) : IrAnnotationValue()
    data class Str(val value: String) : IrAnnotationValue()
    data class ClassLiteral(val type: IrType) : IrAnnotationValue()
    data class EnumConstant(val owner: IrType.Object, val name: String) : IrAnnotationValue()
    data class ArrayValue(val values: List<IrAnnotationValue>) : IrAnnotationValue()
    data class Nested(val annotation: IrAnnotation) : IrAnnotationValue()
}

/** Absence (a ready empty list/null default) is distinct from unreadable or unsupported metadata. */
sealed class AnnotationMetadata<out T> {
    data class Ready<T>(val value: T) : AnnotationMetadata<T>()
    data class Unavailable(val reason: String) : AnnotationMetadata<Nothing>()
}
