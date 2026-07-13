package com.jadxmp.input

/**
 * Discriminates the payload carried by an [EncodedValue].
 *
 * jadx: EncodedType
 */
public enum class EncodedValueType {
    NULL,
    BOOLEAN,
    BYTE,
    SHORT,
    CHAR,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    STRING,
    TYPE,
    ENUM,
    FIELD,
    METHOD,
    METHOD_TYPE,
    METHOD_HANDLE,
    ARRAY,
    ANNOTATION,
}

/**
 * A constant value as it appears in annotations, static-field initializers, and `call_site` args.
 *
 * The Kotlin type of [value] is fixed by [type]:
 * - [EncodedValueType.NULL] → `null`
 * - `BOOLEAN`→`Boolean`, `BYTE`→`Byte`, `SHORT`→`Short`, `CHAR`→`Char`, `INT`→`Int`, `LONG`→`Long`,
 *   `FLOAT`→`Float`, `DOUBLE`→`Double`, `STRING`→`String`, `TYPE`→`String` (a descriptor)
 * - `ENUM`/`FIELD`→[FieldRef], `METHOD`→[MethodRef], `METHOD_TYPE`→[MethodProto],
 *   `METHOD_HANDLE`→[MethodHandle]
 * - `ARRAY`→`List<EncodedValue>`, `ANNOTATION`→[AnnotationData]
 *
 * jadx: EncodedValue
 */
public data class EncodedValue(
    public val type: EncodedValueType,
    public val value: Any?,
) {
    public companion object {
        public val NULL: EncodedValue = EncodedValue(EncodedValueType.NULL, null)
    }
}
