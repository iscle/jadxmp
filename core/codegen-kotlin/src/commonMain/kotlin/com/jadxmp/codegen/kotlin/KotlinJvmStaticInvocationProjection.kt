package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.type.IrType

/** JVM owners whose unqualified names can resolve to Kotlin built-ins in expression positions. */
internal data class KotlinJvmStaticInvocationProjection(
    val ownerName: String,
    val preserveReferenceArgumentTypes: Boolean,
) {
    companion object {
        fun forInvoke(invoke: InvokeInstruction): KotlinJvmStaticInvocationProjection? {
            if (invoke.invokeKind != InvokeKind.STATIC) return null
            val target = invoke.methodRef
            val owner = (target.declaringType as? IrType.Object)?.className ?: return null
            if (owner !in ownerNames) return null
            // valueOf(Object) on a char[] must observe Object.toString, while valueOf(char[])
            // copies its characters. Binding reference arguments to the descriptor prevents Kotlin
            // from silently selecting the more specific overload based on the operand's source type.
            val bindArguments = owner == "java.lang.String" && target.name == "valueOf" &&
                target.returnType == IrType.STRING && invoke.argCount == target.paramTypes.size &&
                target.paramTypes in STRING_REFERENCE_VALUE_OF_SIGNATURES
            return KotlinJvmStaticInvocationProjection(owner, bindArguments)
        }

        val ownerNames = setOf(
            "java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Short",
            "java.lang.Long", "java.lang.Float", "java.lang.Double",
        )
        private val STRING_REFERENCE_VALUE_OF_SIGNATURES = setOf(
            listOf(IrType.OBJECT),
            listOf(IrType.array(IrType.CHAR)),
            listOf(IrType.array(IrType.CHAR), IrType.INT, IrType.INT),
        )
    }
}
