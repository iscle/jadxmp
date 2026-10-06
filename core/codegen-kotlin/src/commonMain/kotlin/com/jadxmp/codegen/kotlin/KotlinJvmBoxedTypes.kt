package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType

/** JVM wrapper references are distinct from primitive Kotlin values, including when known non-null. */
internal object KotlinJvmBoxedTypes {
    private val primitiveNames = mapOf(
        "java.lang.Boolean" to "Boolean", "java.lang.Byte" to "Byte", "java.lang.Short" to "Short",
        "java.lang.Integer" to "Int", "java.lang.Long" to "Long", "java.lang.Float" to "Float",
        "java.lang.Double" to "Double", "java.lang.Character" to "Char",
    )
    val owners = primitiveNames.keys
    val projectedOwners = primitiveNames.values.mapTo(linkedSetOf()) { "kotlin.$it" }
    val aliasedOwners = owners - setOf("java.lang.Integer", "java.lang.Character")
    private val numericOwners = owners - setOf("java.lang.Boolean", "java.lang.Character") + "java.lang.Number"
    private val numericAccessors = mapOf(
        "byteValue" to (IrType.BYTE to "toByte"),
        "shortValue" to (IrType.SHORT to "toShort"),
        "intValue" to (IrType.INT to "toInt"),
        "longValue" to (IrType.LONG to "toLong"),
        "floatValue" to (IrType.FLOAT to "toFloat"),
        "doubleValue" to (IrType.DOUBLE to "toDouble"),
    )

    fun isWrapper(type: IrType): Boolean = (type as? IrType.Object)?.className in owners

    fun projectedName(type: IrType): String? = (type as? IrType.Object)?.className?.let(primitiveNames::get)?.let { "kotlin.$it" }

    fun needsProjection(type: IrType): Boolean = isWrapper(type) ||
        (type is IrType.ArrayType && needsProjection(type.element))

    /** Loaded generated declarations retain their explicit JVM reference spelling at call sites. */
    fun hasGeneratedDeclaration(root: IrRoot?, reference: MethodRef): Boolean =
        hasGeneratedMember(root, reference.declaringType) { cls ->
            cls.methods.any {
                it.name == reference.name && it.argTypes == reference.paramTypes &&
                    (reference.isConstructor || it.returnType == reference.returnType)
            }
        }

    fun hasGeneratedField(root: IrRoot?, reference: FieldRef): Boolean =
        hasGeneratedMember(root, reference.declaringType) { cls ->
            cls.fields.any { it.name == reference.name && it.type == reference.type }
        }

    private fun hasGeneratedMember(root: IrRoot?, owner: IrType, matches: (IrClass) -> Boolean): Boolean {
        val ownerName = (owner as? IrType.Object)?.className ?: return false
        val pending = ArrayDeque<IrClass>()
        root?.findClass(ownerName)?.let(pending::add) ?: return false
        val visited = mutableSetOf<IrClass>()
        while (pending.isNotEmpty()) {
            val cls = pending.removeFirst()
            if (!visited.add(cls)) continue
            if (matches(cls)) return true
            for (type in listOfNotNull(cls.superType) + cls.interfaces) {
                (type as? IrType.Object)?.className?.let(root::findClass)?.let(pending::add)
            }
        }
        return false
    }

    /** Kotlin renames Number's JVM accessors; this retains the original virtual invocation descriptor. */
    fun accessor(invoke: InvokeInstruction): String? {
        if (invoke.invokeKind != InvokeKind.VIRTUAL || invoke.argCount != 1) return null
        val target = invoke.methodRef
        if ((target.declaringType as? IrType.Object)?.className !in numericOwners || target.paramTypes.isNotEmpty()) return null
        val (result, name) = numericAccessors[target.name] ?: return null
        return name.takeIf { result == target.returnType }
    }
}
