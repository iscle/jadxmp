package com.jadxmp.codegen

import com.jadxmp.ir.type.IrType

/** Exact erased field types only. No recursive rendering, structural hashing or generic guesses. */
object OwnedFieldTypes {
    /** null means the caller's work budget cannot establish the comparison. */
    fun equal(left: IrType, right: IrType, charge: (Long) -> Boolean): Boolean? {
        var a = left
        var b = right
        while (true) {
            if (!charge(1)) return null
            when {
                a is IrType.ArrayType && b is IrType.ArrayType -> {
                    a = a.element
                    b = b.element
                }
                a is IrType.Primitive && b is IrType.Primitive -> return a.kind == b.kind
                a is IrType.Object && b is IrType.Object -> {
                    if (a.generics.isNotEmpty() || b.generics.isNotEmpty()) return false
                    if (!charge(a.className.length.toLong() + b.className.length)) return null
                    return a.className == b.className
                }
                else -> return false
            }
        }
    }
}
