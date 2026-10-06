package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrMethod

/** JVM flags whose semantics Kotlin properties and companion functions do not preserve by default. */
internal object KotlinJvmModifiers {
    const val SYNCHRONIZED_ANNOTATION = "kotlin.jvm.Synchronized"
    const val SYNCHRONIZED_FUNCTION = "kotlin.synchronized"
    private const val VOLATILE_ANNOTATION = "kotlin.jvm.Volatile"
    private const val TRANSIENT_ANNOTATION = "kotlin.jvm.Transient"
    val aliasedSymbols = setOf(SYNCHRONIZED_ANNOTATION, SYNCHRONIZED_FUNCTION, VOLATILE_ANNOTATION, TRANSIENT_ANNOTATION)

    // JVM class initializers ignore ACC_SYNCHRONIZED; their initialization lock is a separate VM rule.
    fun isSynchronized(method: IrMethod): Boolean = method.name != "<clinit>" &&
        method.accessFlags and KotlinModifiers.SYNCHRONIZED != 0

    fun fieldAnnotations(field: IrField): List<String> = buildList {
        if (field.accessFlags and KotlinModifiers.VOLATILE != 0) add(VOLATILE_ANNOTATION)
        if (field.accessFlags and KotlinModifiers.TRANSIENT != 0) add(TRANSIENT_ANNOTATION)
    }
}
