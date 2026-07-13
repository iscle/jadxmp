package com.jadxmp.ir.insn

/**
 * How an [InvokeInstruction] dispatches — the union of the JVM/DEX invoke forms.  **jadx: InvokeType**
 *
 * Part of the canonical instruction-payload contract; the single invoke-kind enum both pipeline and
 * codegen share.
 */
enum class InvokeKind {
    /** `invoke-virtual`: dynamic dispatch on the receiver's runtime class. */
    VIRTUAL,

    /** `invoke-static`: no receiver. */
    STATIC,

    /** `invoke-direct`: non-virtual instance call (`<init>`, private methods). */
    DIRECT,

    /** `invoke-super`: call the superclass implementation. */
    SUPER,

    /** `invoke-interface`: dispatch through an interface. */
    INTERFACE,

    /** `invoke-polymorphic`: signature-polymorphic call (`MethodHandle.invoke`/`invokeExact`). */
    POLYMORPHIC,

    /** `invoke-custom` / `invokedynamic`: bootstrap-resolved call site (lambdas, string concat). */
    CUSTOM,
    ;

    /** True for the receiver-less forms. */
    val isStatic: Boolean get() = this == STATIC

    /** True for the forms that take an explicit instance operand (arg 0). */
    val hasInstance: Boolean get() = this != STATIC && this != CUSTOM
}
