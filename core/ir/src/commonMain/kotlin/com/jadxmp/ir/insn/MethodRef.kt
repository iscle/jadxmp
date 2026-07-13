package com.jadxmp.ir.insn

import com.jadxmp.ir.type.IrType

/**
 * A symbolic reference to a method or constructor, in resolved [IrType] terms.  **jadx: MethodInfo**
 *
 * This is part of THE canonical instruction-payload contract (see [InvokeInstruction]): the IR owns
 * the single ref representation that the analysis/structuring stages produce and both codegen
 * backends consume. Input-model method refs (descriptor strings) are mapped to this by the IR-build
 * stage; there must be no competing `MethodRef` type in `core:pipeline` or `core:codegen`.
 *
 * [paramTypes] are the *declared* parameter types (for overload-correct rendering and metadata); the
 * actual argument expressions are the owning instruction's operands. Value type (structural equality).
 */
data class MethodRef(
    val declaringType: IrType,
    val name: String,
    val returnType: IrType,
    val paramTypes: List<IrType>,
) {
    /** True for the JVM instance initializer `<init>` (a constructor; rendered as `new T(...)`). */
    val isConstructor: Boolean get() = name == CONSTRUCTOR_NAME

    /** True for the JVM static initializer `<clinit>` (never rendered as a call). */
    val isStaticInit: Boolean get() = name == STATIC_INIT_NAME

    override fun toString(): String =
        "$declaringType.$name(${paramTypes.joinToString(", ")}): $returnType"

    companion object {
        const val CONSTRUCTOR_NAME = "<init>"
        const val STATIC_INIT_NAME = "<clinit>"
    }
}
