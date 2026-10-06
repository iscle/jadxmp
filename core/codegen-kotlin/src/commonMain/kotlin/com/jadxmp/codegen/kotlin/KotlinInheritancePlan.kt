package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.type.IrType

/** Per-output proofs for plain erased inheritance; only listed JVM Throwable constructor contracts are admitted externally. */
internal class KotlinInheritancePlan(workLimit: Int = 1_000_000, entryLimit: Int = 100_000) {
    data class Analysis<T>(val value: T, val problem: String? = null)
    private class LimitExceeded : IllegalStateException(LIMIT_MESSAGE)
    private companion object { const val LIMIT_MESSAGE = "plain inheritance analysis work/storage limit exceeded" }
    private val budget = Budget(workLimit, entryLimit)
    private val overrides = mutableMapOf<IrMethod, Analysis<List<IrMethod>>>()
    private val constructors = mutableMapOf<IrClass, Analysis<Map<IrMethod, InvokeInstruction>>>()
    private data class MethodKey(val name: String, val arguments: List<IrType>)
    private data class Inherited(val methods: Map<MethodKey, List<IrMethod>>, val problem: String? = null)
    private val inherited = mutableMapOf<IrClass, Inherited>()

    internal val retainedPlanCount: Int get() = overrides.size + constructors.size + inherited.size

    private val terminalConstructors = Analysis<Map<IrMethod, InvokeInstruction>>(emptyMap(), LIMIT_MESSAGE)
    private val terminalOverrides = Analysis<List<IrMethod>>(emptyList(), LIMIT_MESSAGE)

    fun constructorDelegations(cls: IrClass): Analysis<Map<IrMethod, InvokeInstruction>> {
        constructors[cls]?.let { return it }
        if (budget.exhausted) return terminalConstructors
        // Reserve before computing or retaining a result. The first failed plan may use its reserved
        // entry; subsequent lookups share the terminal result without growing any map.
        try { budget.entries(1) } catch (_: LimitExceeded) { return terminalConstructors }
        val result = try { Analysis(constructorPlan(cls)) } catch (_: LimitExceeded) { terminalConstructors }
        constructors[cls] = result
        return result
    }

    fun overrideTargets(method: IrMethod): Analysis<List<IrMethod>> {
        if (method.name.startsWith("<") || method.isStatic || method.accessFlags and KotlinModifiers.PRIVATE != 0)
            return Analysis(emptyList())
        overrides[method]?.let { return it }
        if (budget.exhausted) return terminalOverrides
        try { budget.entries(1) } catch (_: LimitExceeded) { return terminalOverrides }
        val result = try {
            budget.method(method)
            val declarations = inherited.getOrPut(method.declaringClass) {
                budget.entries(1)
                inheritedMethods(method.declaringClass)
            }
            Analysis(declarations.methods[MethodKey(method.name, method.argTypes)] ?: emptyList(), declarations.problem)
        } catch (_: LimitExceeded) { terminalOverrides }
        overrides[method] = result
        return result
    }

    private fun inheritedMethods(cls: IrClass): Inherited {
        val result = mutableListOf<IrMethod>()
        val seen = mutableSetOf<String>()
        var work = 0
        val queue = ArrayDeque<IrType>()
        budget.work(cls.interfaces.size + 1)
        cls.superType?.let(queue::addLast)
        cls.interfaces.forEach(queue::addLast)
        while (queue.isNotEmpty()) {
            val type = queue.removeFirst() as? IrType.Object ?: continue
            budget.work(type.className.length + 1)
            if (!seen.add(type.className)) continue
            if (seen.size > 1024) return Inherited(emptyMap(), "loaded inheritance traversal limit exceeded")
            // Unknown ancestors remain unknown; only an actual loaded declaration proves an override.
            val parent = cls.root.findClass(type.className) ?: continue
            budget.work(parent.methods.size)
            work += parent.methods.size + parent.methods.sumOf { it.argTypes.size }
            if (work > 100_000) return Inherited(emptyMap(), "loaded inheritance member work limit exceeded")
            parent.methods.forEach(budget::method)
            result.addAll(parent.methods.filter {
                !it.isStatic && it.accessFlags and KotlinModifiers.PRIVATE == 0 &&
                    it.accessFlags and (KotlinModifiers.PUBLIC or KotlinModifiers.PROTECTED) != 0 &&
                    !it.contains(AttrFlag.DONT_GENERATE)
            })
            budget.work(parent.interfaces.size + 1)
            parent.superType?.let(queue::addLast)
            parent.interfaces.forEach(queue::addLast)
        }
        budget.entries(result.size)
        result.forEach(budget::method)
        return Inherited(result.groupBy { MethodKey(it.name, it.argTypes) })
    }

    private fun constructorPlan(cls: IrClass): Map<IrMethod, InvokeInstruction> {
        if (cls.accessFlags and (KotlinModifiers.ENUM or KotlinModifiers.INTERFACE) != 0) return emptyMap()
        val superType = cls.superType as? IrType.Object ?: return emptyMap()
        budget.type(superType)
        budget.work(cls.fullName.length + 1)
        if (superType == IrType.OBJECT) return emptyMap()
        val parent = cls.root.findClass(superType.className)
        if (parent == null && !KotlinThrowableProjection.platformConstructor(superType.className, emptyList())) return emptyMap()
        budget.work(cls.methods.size)
        val methods = cls.methods.filter { it.name == "<init>" && !it.contains(AttrFlag.DONT_GENERATE) }
        if (methods.isEmpty() || methods.size > 1024) return emptyMap()
        budget.work((parent?.methods?.size ?: 0) + methods.size)
        val targetMethods = (parent?.methods.orEmpty() + methods).filter { it.name == "<init>" }
        if (targetMethods.size > 2048 || targetMethods.sumOf { it.argTypes.size } > 100_000) return emptyMap()
        targetMethods.forEach(budget::method)
        budget.entries(targetMethods.size)
        val targets = targetMethods.groupBy { it.declaringClass to it.argTypes }
        val calls = mutableMapOf<IrMethod, InvokeInstruction>()
        val delegates = mutableMapOf<IrMethod, IrMethod>()
        var work = 0
        for (method in methods) {
            val statements = statements(method) ?: return emptyMap()
            work += statements.size
            if (work > 100_000) return emptyMap()
            val call = statements.firstOrNull() as? InvokeInstruction ?: return emptyMap()
            val receiver = call.instanceArg as? RegisterOperand ?: return emptyMap()
            val value = receiver.ssaValue ?: return emptyMap()
            if (call.opcode != IrOpcode.INVOKE || call.invokeKind != InvokeKind.DIRECT || !call.methodRef.isConstructor ||
                (value !== method.thisArg && value.localVar?.isThis != true) ||
                call.methodRef.returnType != IrType.VOID || call.argCount != call.methodRef.paramTypes.size + 1) return emptyMap()
            budget.type(call.methodRef.declaringType)
            call.methodRef.paramTypes.forEach(budget::type)
            val owner = when (call.methodRef.declaringType) {
                superType -> parent
                IrType.objectType(cls.fullName) -> cls
                else -> return emptyMap()
            }
            val target = if (owner == null) {
                if (!KotlinThrowableProjection.platformConstructor(superType.className, call.methodRef.paramTypes)) return emptyMap()
                null
            } else {
                val loaded = targets[owner to call.methodRef.paramTypes]?.singleOrNull() ?: return emptyMap()
                if (loaded.contains(AttrFlag.DONT_GENERATE)) return emptyMap()
                if (owner !== cls && loaded.accessFlags and (KotlinModifiers.PUBLIC or KotlinModifiers.PROTECTED) == 0) return emptyMap()
                loaded
            }
            val assignedValues = statements.mapNotNullTo(mutableSetOf()) { it.result?.ssaValue }
            val assignedVariables = assignedValues.mapNotNullTo(mutableSetOf()) { it.localVar }
            fun headerOperand(argument: Operand, depth: Int): Boolean {
                if (depth > 32 || ++work > 100_000) return false
                return when (argument) {
                    is LiteralOperand -> true
                    is RegisterOperand -> {
                        val parameter = argument.ssaValue ?: return false
                        parameter.localVar?.isThis != true &&
                            (parameter.contains(AttrFlag.METHOD_ARGUMENT) || parameter.localVar?.contains(AttrFlag.METHOD_ARGUMENT) == true) &&
                            parameter !in assignedValues && parameter.localVar?.let { it in assignedVariables } != true
                    }
                    is InstructionOperand -> argument.instruction.let {
                        (it.opcode == IrOpcode.CONST || it.opcode == IrOpcode.MOVE) && it.argCount == 1 &&
                            headerOperand(it.getArg(0), depth + 1)
                    }
                    else -> false
                }
            }
            for ((index, argument) in call.args.drop(1).withIndex()) {
                budget.type(argument.type)
                budget.type(call.methodRef.paramTypes[index])
                val expected = call.methodRef.paramTypes[index]
                // Every JVM reference widens to Object without a check or identity change. The
                // header writer still casts to the declared nullable type to preserve overload choice.
                val objectWidening = expected == IrType.OBJECT &&
                    (argument.type is IrType.Object || argument.type is IrType.ArrayType)
                if ((argument.type != expected && !objectWidening) || !headerOperand(argument, 0)) return emptyMap()
            }
            budget.entries(1)
            calls[method] = call
            if (owner === cls) delegates[method] = target ?: return emptyMap()
        }
        // Every chain must terminate in a direct superclass call. Cyclic `this` calls cannot be sourced.
        val proven = mutableSetOf<IrMethod>()
        for (method in methods) {
            val path = mutableSetOf<IrMethod>()
            var current: IrMethod? = method
            while (current != null && current !in proven) {
                if (!path.add(current) || current !in calls) return emptyMap()
                current = delegates[current]
            }
            proven.addAll(path)
        }
        return calls
    }
    /** Match the body writer's top-level ordering without recursively flattening arbitrary regions. */
    private fun statements(method: IrMethod): List<Instruction>? {
        val containers = when (val region = method.region) {
            null -> method.blocks.takeIf { it.size == 1 } ?: return null
            is SequenceRegion -> region.children
            else -> return null
        }
        budget.work(containers.size)
        val result = mutableListOf<Instruction>()
        for (container in containers) {
            val block = container as? BasicBlock ?: return null
            budget.work(block.instructions.size)
            for (instruction in block.instructions) {
                if (!instruction.contains(AttrFlag.DONT_GENERATE) && instruction.opcode != IrOpcode.NOP)
                    result.add(instruction)
            }
        }
        return result
    }

    /** Charge text before structural hashes/equality; cache results by IR identity for subsequent use. */
    private class Budget(private var remainingWork: Int, private var remainingEntries: Int) {
        private val exceeded = LimitExceeded()
        var exhausted: Boolean = false
            private set
        private fun fail(): Nothing {
            exhausted = true
            throw exceeded
        }
        fun work(amount: Int) {
            if (exhausted || amount < 0 || amount > remainingWork) fail()
            remainingWork -= amount
        }
        fun entries(amount: Int) {
            work(1)
            if (amount < 0 || amount > remainingEntries) fail()
            remainingEntries -= amount
        }
        fun method(method: IrMethod) {
            work(method.name.length + method.argTypes.size + 1)
            type(method.returnType)
            method.argTypes.forEach(::type)
        }
        fun type(type: IrType) {
            val pending = ArrayDeque<Pair<IrType, Int>>()
            pending.add(type to 0)
            while (pending.isNotEmpty()) {
                work(1)
                val (next, depth) = pending.removeLast()
                // The following map keys use structural hash/equality; do not let malformed nested
                // types turn that bounded text work into an unbounded recursive stack.
                if (depth > 256) fail()
                when (next) {
                    is IrType.Object -> {
                        work(next.className.length + next.generics.size)
                        next.generics.forEach { pending.add(it to depth + 1) }
                    }
                    is IrType.ArrayType -> pending.add(next.element to depth + 1)
                    is IrType.TypeVariable -> work(next.name.length)
                    is IrType.Wildcard -> next.boundType?.let { pending.add(it to depth + 1) }
                    else -> Unit
                }
            }
        }
    }

}
