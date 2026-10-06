package com.jadxmp.input

/**
 * A field as declared in a class: its reference identity plus modifiers, annotations, and (for a
 * static field) its compile-time constant initializer.
 *
 * jadx: IFieldData
 */
public interface FieldData : FieldRef {
    public val accessFlags: Int

    /** Optional JVMS generic signature, independent of the erased descriptor. May reject malformed input on access. */
    public val genericSignature: String? get() = null

    public val annotations: List<AnnotationData>

    /** The encoded initializer for a static field, or null; the field need not be final. */
    public val constValue: EncodedValue?
}

/**
 * A method as declared in a class: its reference/signature, modifiers, annotations, per-parameter
 * annotations, and a lazily-obtained [CodeReader] for the body (null for abstract/native methods).
 *
 * jadx: IMethodData
 */
public interface MethodData {
    public val ref: MethodRef

    public val accessFlags: Int

    /** Optional JVMS generic signature, independent of the erased descriptor. May reject malformed input on access. */
    public val genericSignature: String? get() = null

    public val annotations: List<AnnotationData>

    /** Annotations per parameter, indexed by parameter position; entries may be empty lists. */
    public val parameterAnnotations: List<List<AnnotationData>>

    /** Annotation element default, or null when absent. Malformed metadata may throw on access. */
    public val annotationDefault: EncodedValue? get() = null

    /** The method body, or null when the method has no code (abstract/native). */
    public val codeReader: CodeReader?
}

/**
 * A single class/interface/enum parsed from an input, normalized to descriptors and the input model.
 * This is the top-level unit the engine consumes.
 *
 * jadx: IClassData
 */
public interface ClassData {
    /** Descriptor of this type, e.g. `Lcom/example/Foo;`. */
    public val type: String

    public val accessFlags: Int

    /** Optional JVMS generic signature, independent of the erased descriptor. May reject malformed input on access. */
    public val genericSignature: String? get() = null

    /** Format-neutral lexical enclosure, or null for unavailable/legacy metadata. */
    public val nesting: ClassNesting? get() = null

    /**
     * Enclosure used by runtime generic reflection, independently of best-effort source nesting.
     * Null means unknown, never proof of no enclosing scope. Formats may know reflection sees no
     * enclosure even when [nesting] stays unavailable to permit source reconstruction heuristics.
     */
    public val reflectiveNesting: ClassNesting? get() = nesting

    /** Member-class modifiers, independent of enclosure (which may be unavailable). */
    public val innerAccessFlags: Int? get() = null

    /** Descriptor of the superclass, or null (only `java/lang/Object` and interfaces have none). */
    public val superType: String?

    /** Descriptors of directly implemented interfaces. */
    public val interfaces: List<String>

    /** Source file name from debug metadata, if present. */
    public val sourceFile: String?

    public val fields: List<FieldData>

    public val methods: List<MethodData>

    public val annotations: List<AnnotationData>

    /** Name of the container file this class came from (e.g. `classes2.dex`), for diagnostics. */
    public val inputFileName: String

    /** Disassemble the class to human-readable text (smali for DEX). Best-effort, for display. */
    public fun disassemble(): String
}
