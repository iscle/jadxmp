package com.jadxmp.input

/**
 * A reference to a field: the class that declares it, its name, and its type descriptor.
 *
 * Type descriptors are the JVM/DEX form (`I`, `Ljava/lang/String;`, `[I`) so both parsers speak the
 * same language. This is used both as a field's own identity ([FieldData]) and as an operand of a
 * field instruction.
 *
 * jadx: IFieldRef
 */
public interface FieldRef {
    /** Descriptor of the declaring class, e.g. `Lcom/example/Foo;`. */
    public val declaringClassType: String

    public val name: String

    /** Descriptor of the field's type. */
    public val type: String
}

/**
 * The signature shape of a method: its return type and parameter types, all as descriptors.
 *
 * jadx: IMethodProto
 */
public interface MethodProto {
    public val returnType: String

    /** Parameter type descriptors, in declaration order (no implicit `this`). */
    public val parameterTypes: List<String>
}

/**
 * A reference to a method: its declaring class and name on top of the [MethodProto] shape.
 *
 * jadx: IMethodRef
 */
public interface MethodRef : MethodProto {
    public val declaringClassType: String

    public val name: String
}

/**
 * The kind of member a [MethodHandle] points at — mirrors the DEX `method_handle_type` values.
 *
 * jadx: MethodHandleType
 */
public enum class MethodHandleType {
    STATIC_PUT,
    STATIC_GET,
    INSTANCE_PUT,
    INSTANCE_GET,
    INVOKE_STATIC,
    INVOKE_INSTANCE,
    INVOKE_DIRECT,
    INVOKE_CONSTRUCTOR,
    INVOKE_INTERFACE,
    ;

    /** True for the four field-accessor handle kinds (the rest reference methods). */
    public val isField: Boolean
        get() = this == STATIC_PUT || this == STATIC_GET || this == INSTANCE_PUT || this == INSTANCE_GET
}

/**
 * A method handle constant (`invokedynamic` / `const-method-handle` support). Exactly one of
 * [fieldRef] / [methodRef] is non-null, selected by [type].
 *
 * jadx: IMethodHandle
 */
public interface MethodHandle {
    public val type: MethodHandleType

    public val fieldRef: FieldRef?

    public val methodRef: MethodRef?
}

/**
 * A `call_site` reference for `invoke-custom`: the bootstrap arguments as encoded values.
 *
 * jadx: ICallSite
 */
public interface CallSite {
    public val values: List<EncodedValue>
}
