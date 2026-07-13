package com.jadxmp.ir.type

/**
 * The fundamental kinds a value slot can hold: the eight JVM primitives, plus [OBJECT] and [ARRAY]
 * as the two reference kinds, plus [VOID].
 *
 * A concrete [IrType] pins exactly one kind. A *partial* type ([IrType.Unknown]) carries a **set**
 * of the kinds it could still turn out to be — that is the only place [OBJECT] and [ARRAY] appear as
 * bare kinds (a resolved reference type is an [IrType.Object] / [IrType.ArrayType], not a kind).
 *
 * jadx: PrimitiveType
 *
 * @property descriptor single-char JVM type descriptor ('I', 'Z', 'L', '[', …).
 * @property slots number of 32-bit register/stack slots the kind occupies (long/double take 2).
 */
enum class TypeKind(val descriptor: Char, val slots: Int) {
    BOOLEAN('Z', 1),
    CHAR('C', 1),
    BYTE('B', 1),
    SHORT('S', 1),
    INT('I', 1),
    FLOAT('F', 1),
    LONG('J', 2),
    DOUBLE('D', 2),
    OBJECT('L', 1),
    ARRAY('[', 1),
    VOID('V', 0),
    ;

    val isReference: Boolean get() = this == OBJECT || this == ARRAY

    companion object {
        /** All eleven kinds, used to build the fully-unknown top type. */
        val ALL: Set<TypeKind> = entries.toSet()
    }
}
