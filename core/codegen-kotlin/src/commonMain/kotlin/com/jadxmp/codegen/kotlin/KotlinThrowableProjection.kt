package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType

internal class ThrowableProjectionException(message: String) : IllegalStateException(message)

/** Exact JVM Throwable contracts whose Kotlin declarations are properties, not functions.
 * The cache is output-local and reads only immutable declaration metadata, never another unit's body.
 */
internal class KotlinThrowableProjection(
    private val root: IrRoot,
    private val aliases: AliasMap = AliasMap.EMPTY,
    private var remaining: Int = 1_000_000,
) {
    private val lineage = mutableMapOf<IrClass, Boolean>()
    private val declarations = mutableSetOf<IrMethod>()
    private val scopes = mutableSetOf<IrClass>()
    enum class Invocation { PLATFORM_OWNER, LOADED_OWNER }
    private val invocations = mutableMapOf<InvokeInstruction, Invocation>()
    internal val retainedProofCount: Int get() = lineage.size + declarations.size + scopes.size + invocations.size
    private inline fun check(value: Boolean, message: () -> String) {
        if (!value) throw ThrowableProjectionException(message())
    }

    private fun charge(amount: Int) {
        if (amount < 0 || amount > remaining) {
            remaining = 0
            throw ThrowableProjectionException("Throwable property projection work limit exceeded")
        }
        remaining -= amount
    }

    fun isThrowable(type: IrType): Boolean {
        val objectType = type as? IrType.Object ?: return false
        charge(objectType.className.length + 1)
        val initial = root.findClass(objectType.className)
        if (initial == null) return objectType.className in PLATFORM_OWNERS
        lineage[initial]?.let { return it }
        val path = mutableSetOf<IrClass>()
        var current: IrClass = initial
        var proven = false
        while (true) {
            val cached = lineage[current]
            if (cached != null) { proven = cached; break }
            charge(1)
            if (!path.add(current) || path.size > 1024) break
            val parent = current.superType as? IrType.Object ?: break
            charge(parent.className.length + 1)
            val loaded = root.findClass(parent.className)
            if (loaded == null) {
                proven = parent.className in PLATFORM_OWNERS
                break
            }
            current = loaded
        }
        charge(path.size)
        path.forEach { lineage[it] = proven }
        return proven
    }

    fun declaration(method: IrMethod): Boolean {
        if (method in declarations) return true
        if (method.name != "getMessage" || method.argTypes.isNotEmpty() || method.returnType != IrType.STRING ||
            method.isStatic || method.accessFlags and KotlinModifiers.PRIVATE != 0) return false
        if (!isThrowable(IrType.objectType(method.declaringClass.fullName))) return false
        check(method.accessFlags and KotlinModifiers.PUBLIC != 0) { "Throwable.getMessage property requires public JVM visibility" }
        check(method.accessFlags and KotlinModifiers.NATIVE == 0) { "native Throwable property accessor is unsupported" }
        validateScope(method.declaringClass)
        charge(1)
        declarations.add(method)
        return true
    }

    private fun validateScope(cls: IrClass) {
        if (cls in scopes) return
        var current: IrClass? = cls
        val seen = mutableSetOf<IrClass>()
        while (current != null && seen.add(current)) {
            charge(current.fields.size + current.methods.size + current.interfaces.size + current.fullName.length + 1)
            check(current.interfaces.isEmpty()) { "Throwable property with additional interface contracts is unsupported" }
            for (field in current.fields) {
                charge(field.name.length + 1)
                val renamed = if (aliases.isEmpty) null else {
                    charge(current.fullName.length + field.name.length + 1)
                    aliases.aliasOf(FieldNodeRef(current.fullName, field.name))
                }
                if (renamed != null) charge(renamed.length + 1)
                val name = renamed ?: KotlinIdentifiers.sanitize(field.name)
                check(name.removeSurrounding("`") != "message") { "Throwable.message property conflicts with a loaded field" }
            }
            for (candidate in current.methods) {
                charge(candidate.name.length + 1)
                if (candidate.argTypes.isNotEmpty() || candidate.name.startsWith("<")) continue
                val alias = if (aliases.isEmpty) null else {
                    charge(current.fullName.length + candidate.name.length + 1)
                    aliases.aliasOf(MethodNodeRef(current.fullName, candidate.name, emptyList()))
                }
                if (alias != null) charge(alias.length + 1)
                val emittedName = alias?.removeSurrounding("`") ?: candidate.name
                if (candidate.name == "getMessage") {
                    check(emittedName == "getMessage" && candidate.returnType == IrType.STRING &&
                        !candidate.isStatic && candidate.accessFlags and KotlinModifiers.PUBLIC != 0) {
                        "renamed or incompatible Throwable.getMessage property is unsupported"
                    }
                } else check(emittedName != "getMessage") { "Throwable getter conflicts with a renamed method" }
            }
            val parent = current.superType as? IrType.Object
            if (parent != null) charge(parent.className.length + 1)
            current = parent?.let { root.findClass(it.className) }
        }
        charge(1)
        scopes.add(cls)
    }

    fun invocation(invoke: InvokeInstruction): Invocation? {
        invocations[invoke]?.let { return it }
        val ref = invoke.methodRef
        if (ref.name != "getMessage" || ref.paramTypes.isNotEmpty() || ref.returnType != IrType.STRING ||
            invoke.argCount != 1 || invoke.invokeKind !in listOf(InvokeKind.VIRTUAL, InvokeKind.SUPER)) return null
        if (!isThrowable(ref.declaringType)) return null
        val owner = (ref.declaringType as IrType.Object).className
        val loaded = root.findClass(owner)
        loaded?.let { cls ->
            validateScope(cls)
            charge(cls.methods.size)
            cls.methods.filter { it.name == ref.name && it.argTypes.isEmpty() && it.returnType == ref.returnType }
                .forEach { check(declaration(it)) { "unsupported Throwable getter declaration" } }
        }
        charge(1)
        val projection = if (loaded == null) Invocation.PLATFORM_OWNER else Invocation.LOADED_OWNER
        invocations[invoke] = projection
        return projection
    }

    companion object {
        private val PLATFORM_OWNERS = setOf("java.lang.Throwable", "java.lang.Exception", "java.lang.RuntimeException")
        /** Public four standard JVM overloads plus the protected suppression/stack-trace constructor. */
        fun platformConstructor(owner: String, arguments: List<IrType>): Boolean = owner in PLATFORM_OWNERS && when {
            arguments.isEmpty() -> true
            arguments.size == 1 -> arguments[0] == IrType.STRING || arguments[0] == IrType.THROWABLE
            arguments.size == 2 -> arguments[0] == IrType.STRING && arguments[1] == IrType.THROWABLE
            arguments.size == 4 -> arguments[0] == IrType.STRING && arguments[1] == IrType.THROWABLE &&
                arguments[2] == IrType.BOOLEAN && arguments[3] == IrType.BOOLEAN
            else -> false
        }
    }
}
