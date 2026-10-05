package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.type.IrType

/** Exact JVM signatures whose Kotlin surface is a property or an indexing operation. */
internal enum class KotlinJvmInvocationProjection {
    JAVA_CLASS,
    LENGTH,
    CHAR_AT,
    ;

    companion object {
        fun forInvoke(invoke: InvokeInstruction): KotlinJvmInvocationProjection? {
            if (invoke.invokeKind != InvokeKind.VIRTUAL && invoke.invokeKind != InvokeKind.INTERFACE) return null
            val target = invoke.methodRef
            if (invoke.argCount != target.paramTypes.size + 1) return null
            val owner = (target.declaringType as? IrType.Object)?.className
            // Object.getClass is final, so the signature cannot be overridden by a user implementation.
            if (target.name == "getClass" && target.paramTypes.isEmpty() && target.returnType == IrType.CLASS) {
                return JAVA_CLASS
            }
            if (owner != "java.lang.String" && owner != "java.lang.CharSequence") return null
            return when {
                target.name == "length" && target.paramTypes.isEmpty() && target.returnType == IrType.INT -> LENGTH
                target.name == "charAt" && target.paramTypes == listOf(IrType.INT) && target.returnType == IrType.CHAR -> CHAR_AT
                else -> null
            }
        }
    }
}
