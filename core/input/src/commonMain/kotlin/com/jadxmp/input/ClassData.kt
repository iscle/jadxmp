package com.jadxmp.input

/**
 * A field as declared in a class: its reference identity plus modifiers, annotations, and (for a
 * static field) its compile-time constant initializer.
 *
 * jadx: IFieldData
 */
public interface FieldData : FieldRef {
    public val accessFlags: Int

    public val annotations: List<AnnotationData>

    /** The constant value for a `static final` field, or null. */
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

    public val annotations: List<AnnotationData>

    /** Annotations per parameter, indexed by parameter position; entries may be empty lists. */
    public val parameterAnnotations: List<List<AnnotationData>>

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
