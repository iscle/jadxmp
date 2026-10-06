package com.jadxmp.codegen

import com.jadxmp.ir.type.IrType

/** Descriptor-only projection. Parameter annotations have their own positional contract and are never padded. */
object EnumConstructorParameters {
    const val SYNTHETIC_PREFIX_SIZE: Int = 2

    fun hasSyntheticPrefix(parameters: List<IrType>): Boolean = parameters.size >= SYNTHETIC_PREFIX_SIZE &&
        parameters[0] == IrType.STRING && parameters[1] == IrType.INT
}
