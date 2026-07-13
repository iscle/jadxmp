package com.jadxmp.input

/**
 * When an annotation is retained/visible — governs whether it survives into runtime reflection.
 *
 * jadx: AnnotationVisibility
 */
public enum class AnnotationVisibility {
    BUILD,
    RUNTIME,
    SYSTEM,
}

/**
 * A single annotation instance attached to a class, field, method, or parameter.
 *
 * Named [AnnotationData] rather than `Annotation` to avoid clashing with `kotlin.Annotation`.
 *
 * jadx: IAnnotation / JadxAnnotation
 */
public data class AnnotationData(
    /** Descriptor of the annotation type, e.g. `Ljava/lang/Deprecated;`. */
    public val annotationType: String,
    /** Null for nested annotations (encoded values), where visibility is not stored. */
    public val visibility: AnnotationVisibility?,
    /** Named element values, in file order. */
    public val values: Map<String, EncodedValue>,
) {
    /** Convenience for the single-element `value = ...` form. */
    public val defaultValue: EncodedValue?
        get() = values["value"]
}
