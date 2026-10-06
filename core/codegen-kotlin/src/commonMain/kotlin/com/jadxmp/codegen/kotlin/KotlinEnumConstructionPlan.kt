package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.EnumConstructorParameters
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.ConstStringInstruction
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.MethodRef
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.insn.TypeInstruction
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.type.IrType

/** One proof consumed by entry arguments, constructor headers, and final-field declarations. */
internal class KotlinEnumConstructionPlan private constructor(
    val entries: Map<IrField, InvokeInstruction>,
    val constructors: Map<IrMethod, Delegation>,
    val consumedInitializers: Set<Instruction>,
    private val initializedFields: Set<IrField>,
) {
    class Delegation(val call: InvokeInstruction, val target: IrMethod?, val consumed: Set<Instruction>)

    fun initializes(field: IrField): Boolean = field in initializedFields

    companion object {
        fun analyze(cls: IrClass, fields: List<IrField>, clinit: IrMethod, valuesField: IrField, budget: Budget = Budget(), outputUnit: IrClass = cls): KotlinEnumConstructionPlan? =
            try { Builder(cls, budget, outputUnit).build(fields, clinit, valuesField) } catch (_: Unsupported) { null }

        private fun isThis(value: Operand, method: IrMethod): Boolean =
            (value as? RegisterOperand)?.ssaValue?.let { it === method.thisArg } == true
    }

    private class Unsupported : RuntimeException()
    /** Shared across a complete output, including both discovery and rendering. */
    class Budget(private var remaining: Int = 1_000_000) {
        val exhausted: Boolean get() = remaining == 0
        fun reserve(): Boolean = try { work(); true } catch (_: Unsupported) { false }
        fun work(size: Int = 1) {
            if (size < 0 || size > remaining) { remaining = 0; throw Unsupported() }
            remaining -= size
        }
    }
    private class Builder(val cls: IrClass, private val budget: Budget, private val outputUnit: IrClass) {
        private fun work(size: Int = 1) = budget.work(size)
        private fun requireProof(value: Boolean) { if (!value) throw Unsupported() }
        /** Bound recursive IrType hashing/equality before any signature key operation. */
        private fun type(type: IrType) {
            var current = type
            var dimensions = 0
            while (true) {
                work()
                when (current) {
                    is IrType.ArrayType -> { requireProof(++dimensions <= 32); current = current.element }
                    is IrType.Object -> { work(current.className.length); requireProof(current.generics.isEmpty()); return }
                    is IrType.Primitive -> return
                    else -> throw Unsupported()
                }
            }
        }
        private fun signature(parameters: List<IrType>) { work(parameters.size); parameters.forEach(::type) }
        private val ownType = IrType.objectType(cls.fullName)

        fun build(fields: List<IrField>, clinit: IrMethod, valuesField: IrField): KotlinEnumConstructionPlan {
            work(cls.fullName.length + cls.methods.size + fields.size)
            val methods = cls.methods.filter { it.name == "<init>" && !it.contains(AttrFlag.DONT_GENERATE) }
            requireProof(methods.isNotEmpty() && methods.size <= 256)
            methods.forEach {
                signature(it.argTypes)
                requireProof(!it.isStatic && it.accessFlags and KotlinModifiers.PRIVATE != 0 &&
                    EnumConstructorParameters.hasSyntheticPrefix(it.argTypes))
            }
            val methodsByArgs = methods.groupBy { it.argTypes }
            requireProof(methodsByArgs.values.all { it.size == 1 })
            val delegations = linkedMapOf<IrMethod, Delegation>()
            val bodies = mutableMapOf<IrMethod, List<Instruction>>()
            for (method in methods) {
                val statements = statements(method)
                bodies[method] = statements
                rejectOwnInitializationDependencies(statements, emptyList())
                val index = statements.indexOfFirst { it is InvokeInstruction && it.methodRef.isConstructor }
                requireProof(index >= 0)
                val call = statements[index] as InvokeInstruction
                signature(call.methodRef.paramTypes); type(call.methodRef.declaringType)
                requireProof(call.opcode == IrOpcode.INVOKE && call.invokeKind == InvokeKind.DIRECT &&
                    call.methodRef.returnType == IrType.VOID && call.argCount == call.methodRef.paramTypes.size + 1 &&
                    EnumConstructorParameters.hasSyntheticPrefix(call.methodRef.paramTypes))
                val trace = ExpressionTrace(method)
                val receiver = trace.origin(call.getArg(0))
                requireProof(isThis(receiver, method))
                work(method.ssaValues.size * 16)
                val parameters = method.ssaValues.filter { it.contains(AttrFlag.METHOD_ARGUMENT) && it !== method.thisArg }
                    .sortedBy { it.regNum }
                requireProof(parameters.size == method.argTypes.size)
                requireProof((trace.origin(call.getArg(1)) as? RegisterOperand)?.ssaValue === parameters[0])
                requireProof((trace.origin(call.getArg(2)) as? RegisterOperand)?.ssaValue === parameters[1])
                val hidden = parameters.take(2).toSet()
                ensureVisibleArguments(call.args.drop(3), hidden)
                val target = when (call.methodRef.declaringType) {
                    IrType.objectType("java.lang.Enum") -> {
                        requireProof(call.methodRef.paramTypes == listOf(IrType.STRING, IrType.INT)); null
                    }
                    ownType -> methodsByArgs[call.methodRef.paramTypes]?.singleOrNull() ?: throw Unsupported()
                    else -> throw Unsupported()
                }
                for (i in 0 until call.argCount) trace.visit(call.getArg(i))
                val prefix = statements.subList(0, index)
                trace.validate(prefix, call.args)
                val consumed = trace.definitions.toMutableSet().apply { add(call) }
                ensureNoRemainingReads(statements.drop(index + 1), consumed, parameters.take(2).toSet())
                delegations[method] = Delegation(call, target, consumed)
            }
            // Identity-keyed graph: every this-chain must end at the Enum super call.
            val proven = mutableSetOf<IrMethod>()
            for (method in methods) {
                val active = mutableSetOf<IrMethod>()
                var next: IrMethod? = method
                while (next != null && next !in proven) {
                    work(); requireProof(active.add(next)); next = delegations[next]?.target
                }
                proven.addAll(active)
            }
            val statements = statements(clinit)
            val entries = linkedMapOf<IrField, InvokeInstruction>()
            val consumed = mutableSetOf<Instruction>()
            var start = 0
            val fieldsByName = fields.associateBy { work(it.name.length); it.name }
            requireProof(fieldsByName.size == fields.size)
            for (ordinal in fields.indices) {
                val index = (start until statements.size).firstOrNull {
                    work(); val instruction = statements[it]
                    if (instruction is FieldInstruction && instruction.isPut && instruction.isStatic) {
                        type(instruction.fieldRef.declaringType); work(instruction.fieldRef.name.length)
                        instruction.fieldRef.declaringType == ownType && fieldsByName.containsKey(instruction.fieldRef.name)
                    } else false
                } ?: throw Unsupported()
                val store = statements[index] as FieldInstruction
                val field = fieldsByName.getValue(store.fieldRef.name)
                requireProof(field !in entries)
                val trace = ExpressionTrace(null)
                val construction = trace.definition(store.getArg(0)) as? InvokeInstruction ?: throw Unsupported()
                signature(construction.methodRef.paramTypes); type(construction.methodRef.declaringType)
                requireProof(construction.opcode == IrOpcode.CONSTRUCTOR && construction.methodRef.returnType == IrType.VOID && construction.methodRef.declaringType == ownType &&
                    construction.argCount == construction.methodRef.paramTypes.size &&
                    methodsByArgs.containsKey(construction.methodRef.paramTypes))
                val name = trace.definition(construction.getArg(0)) as? ConstStringInstruction
                val number = trace.origin(construction.getArg(1)) as? LiteralOperand
                trace.regeneratedName = name
                requireProof(name?.value == field.name && number?.value == ordinal.toLong() &&
                    KotlinIdentifiers.sanitize(field.name).removeSurrounding("`") == field.name &&
                    field.name !in setOf("entries", "name", "ordinal"))
                rejectOwnInitializationDependencies(listOf(construction), entries.keys.toList())
                trace.visit(store.getArg(0))
                trace.validate(statements.subList(start, index), store.args)
                consumed.addAll(trace.definitions); consumed.add(store)
                entries[field] = construction
                start = index + 1
            }
            ensureNoRemainingReads(statements.drop(start), consumed)
            val helpers = consumeValues(statements, start, entries.keys.toList(), valuesField, consumed)
            checkHiddenReferences(clinit, consumed, helpers, valuesField, entries.keys)
            return KotlinEnumConstructionPlan(entries, delegations, consumed, initializedFields(delegations, bodies))
        }

        private fun rejectOwnInitializationDependencies(statements: List<Instruction>, entries: List<IrField>) {
            val queue = ArrayDeque<Instruction>()
            val seen = mutableSetOf<Instruction>()
            work(statements.size); statements.forEach(queue::addLast)
            while (queue.isNotEmpty()) {
                val instruction = queue.removeFirst(); work(instruction.argCount + 1)
                if (!seen.add(instruction)) continue
                if (instruction is InvokeInstruction && !instruction.methodRef.isConstructor) {
                    type(instruction.methodRef.declaringType)
                    // Enum companions initialize after entries. Proving member-call dependencies
                    // (including instance calls which may reach that companion) is a separate plan.
                    requireProof(instruction.methodRef.declaringType != ownType)
                }
                if (instruction is FieldInstruction && instruction.isStatic) {
                    type(instruction.fieldRef.declaringType); work(instruction.fieldRef.name.length)
                    if (instruction.fieldRef.declaringType == ownType) requireProof(entries.any {
                        work(it.name.length); it.name == instruction.fieldRef.name
                    })
                }
                for (operand in instruction.args) when (operand) {
                    is InstructionOperand -> queue.addLast(operand.instruction)
                    is RegisterOperand -> operand.ssaValue?.assign?.parent?.let(queue::addLast)
                    else -> Unit
                }
            }
        }

        private fun matchesField(instruction: Instruction, field: IrField): Boolean {
            if (instruction !is FieldInstruction || !instruction.isPut || !instruction.isStatic) return false
            type(instruction.fieldRef.declaringType); work(instruction.fieldRef.name.length + field.name.length)
            return instruction.fieldRef.declaringType == ownType && instruction.fieldRef.name == field.name
        }

        private fun initializedFields(delegations: Map<IrMethod, Delegation>, bodies: Map<IrMethod, List<Instruction>>): Set<IrField> {
            work(cls.fields.size)
            val fieldsByName = cls.fields.associateBy { work(it.name.length); type(it.type); it.name }
            requireProof(fieldsByName.size == cls.fields.size)
            val stores = mutableMapOf<IrMethod, Set<IrField>>()
            val allStores = mutableMapOf<IrMethod, Set<IrField>>()
            for ((method, body) in bodies) {
                val once = mutableSetOf<IrField>()
                val invalid = mutableSetOf<IrField>()
                var terminated = false
                for (statement in body) {
                    work()
                    if (statement.opcode == IrOpcode.RETURN || statement.opcode == IrOpcode.THROW) terminated = true
                    if (statement !is FieldInstruction || !statement.isPut || statement.isStatic) continue
                    type(statement.fieldRef.declaringType); work(statement.fieldRef.name.length)
                    if (statement.fieldRef.declaringType != ownType) continue
                    val field = fieldsByName[statement.fieldRef.name] ?: continue
                    type(statement.fieldRef.type); requireProof(statement.fieldRef.type == field.type)
                    if (terminated || !isThis(statement.getArg(0), method) || !once.add(field)) invalid.add(field)
                }
                stores[method] = once - invalid
                allStores[method] = once + invalid
            }
            val result = mutableSetOf<IrField>()
            for (field in cls.fields) {
                if (field.accessFlags and KotlinModifiers.STATIC != 0) continue
                var valid = true
                for ((method, delegation) in delegations) {
                    work()
                    if (delegation.target == null) {
                        if (field !in stores.getValue(method)) valid = false
                    } else {
                        // All writes in a delegating body, even duplicate/invalid ones, reject a val.
                        if (field in allStores.getValue(method)) valid = false
                    }
                }
                if (valid) result.add(field)
            }
            return result
        }

        private fun consumeValues(statements: List<Instruction>, start: Int, fields: List<IrField>, valuesField: IrField,
            consumed: MutableSet<Instruction>): Set<IrMethod> {
            val arrayType = IrType.array(ownType)
            requireProof(valuesField.type == arrayType && valuesField.accessFlags and
                (KotlinModifiers.PRIVATE or KotlinModifiers.STATIC or KotlinModifiers.FINAL) ==
                (KotlinModifiers.PRIVATE or KotlinModifiers.STATIC or KotlinModifiers.FINAL))
            fun fieldRead(operand: Operand, field: IrField): Boolean {
                val read = ExpressionTrace(null).definition(ExpressionTrace(null).origin(operand)) as? FieldInstruction ?: return false
                type(read.fieldRef.declaringType); type(read.fieldRef.type); work(read.fieldRef.name.length + field.name.length)
                return read.isStatic && !read.isPut && read.fieldRef.declaringType == ownType &&
                    read.fieldRef.name == field.name && read.fieldRef.type == field.type && read.argCount == 0
            }
            fun array(operand: Operand): Boolean {
                val instruction = ExpressionTrace(null).definition(ExpressionTrace(null).origin(operand)) as? TypeInstruction ?: return false
                if (instruction.referencedType != arrayType) return false
                if (instruction.opcode == IrOpcode.NEW_ARRAY && fields.isEmpty() && instruction.argCount == 1)
                    return (ExpressionTrace(null).origin(instruction.getArg(0)) as? LiteralOperand)?.value == 0L
                if (instruction.opcode != IrOpcode.FILLED_NEW_ARRAY || instruction.argCount != fields.size) return false
                return fields.indices.all { fieldRead(instruction.getArg(it), fields[it]) }
            }
            fun returned(method: IrMethod): Operand {
                val body = statements(method)
                val returned = body.lastOrNull() ?: throw Unsupported()
                requireProof(returned.opcode == IrOpcode.RETURN && returned.argCount == 1)
                val trace = ExpressionTrace(method, allowEmptyArray = true)
                trace.visit(returned.getArg(0)); trace.validate(body.dropLast(1), returned.args)
                return trace.origin(returned.getArg(0))
            }
            val helpers = cls.methods.filter { it.name == "\$values" && it.isStatic && it.argTypes.isEmpty() && it.returnType == arrayType }
            requireProof(helpers.size <= 1)
            val helper = helpers.singleOrNull()
            if (helper != null) {
                requireProof(!KotlinJvmModifiers.isSynchronized(helper))
                requireProof(helper.accessFlags and KotlinModifiers.PRIVATE != 0 && array(returned(helper)))
            }
            val storeIndex = (start until statements.size).firstOrNull {
                work(); val statement = statements[it]
                matchesField(statement, valuesField)
            } ?: throw Unsupported()
            val store = statements[storeIndex]
            val trace = ExpressionTrace(null, allowEmptyArray = true)
            val producer = trace.definition(trace.origin(store.getArg(0)))
            if (producer is InvokeInstruction) {
                requireProof(helper != null && producer.opcode == IrOpcode.INVOKE && producer.isStatic && producer.argCount == 0 &&
                    producer.methodRef.declaringType == ownType && producer.methodRef.name == helper.name &&
                    producer.methodRef.paramTypes.isEmpty() && producer.methodRef.returnType == arrayType)
            } else requireProof(array(store.getArg(0)))
            trace.visit(store.getArg(0)); trace.validate(statements.subList(start, storeIndex), store.args)
            consumed.addAll(trace.definitions); consumed.add(store)
            ensureNoRemainingReads(statements.drop(storeIndex + 1), consumed)
            val values = cls.methods.singleOrNull { it.name == "values" && it.isStatic && it.argTypes.isEmpty() && it.returnType == arrayType }
                ?: throw Unsupported()
            requireProof(!KotlinJvmModifiers.isSynchronized(values))
            val cast = ExpressionTrace(values).definition(returned(values)) as? TypeInstruction ?: throw Unsupported()
            requireProof(cast.opcode == IrOpcode.CHECK_CAST && cast.referencedType == arrayType && cast.argCount == 1)
            val clone = ExpressionTrace(values).definition(ExpressionTrace(values).origin(cast.getArg(0))) as? InvokeInstruction ?: throw Unsupported()
            requireProof(clone.opcode == IrOpcode.INVOKE && clone.invokeKind == InvokeKind.VIRTUAL && clone.methodRef.name == "clone" && clone.methodRef.paramTypes.isEmpty() && clone.argCount == 1 &&
                clone.methodRef.returnType == IrType.OBJECT && clone.methodRef.declaringType == arrayType && fieldRead(clone.getArg(0), valuesField))
            val valueOf = cls.methods.singleOrNull { it.name == "valueOf" && it.isStatic && it.argTypes == listOf(IrType.STRING) && it.returnType == ownType }
                ?: throw Unsupported()
            requireProof(!KotlinJvmModifiers.isSynchronized(valueOf))
            val resultCast = ExpressionTrace(valueOf).definition(returned(valueOf)) as? TypeInstruction ?: throw Unsupported()
            requireProof(resultCast.opcode == IrOpcode.CHECK_CAST && resultCast.referencedType == ownType && resultCast.argCount == 1)
            val invoke = ExpressionTrace(valueOf).definition(ExpressionTrace(valueOf).origin(resultCast.getArg(0))) as? InvokeInstruction ?: throw Unsupported()
            requireProof(invoke.isStatic && invoke.argCount == 2 && invoke.methodRef == MethodRef(IrType.objectType("java.lang.Enum"),
                "valueOf", IrType.objectType("java.lang.Enum"), listOf(IrType.CLASS, IrType.STRING)))
            val literal = ExpressionTrace(valueOf).definition(ExpressionTrace(valueOf).origin(invoke.getArg(0))) as? TypeInstruction ?: throw Unsupported()
            requireProof(literal.opcode == IrOpcode.CONST_CLASS && literal.referencedType == ownType)
            val input = ExpressionTrace(valueOf).origin(invoke.getArg(1)) as? RegisterOperand ?: throw Unsupported()
            requireProof(input.ssaValue?.contains(AttrFlag.METHOD_ARGUMENT) == true && input.ssaValue?.assign?.parent == null)
            return setOfNotNull(values, valueOf, helper)
        }

        private fun checkHiddenReferences(clinit: IrMethod, consumed: Set<Instruction>, helpers: Set<IrMethod>,
            backing: IrField, entries: Set<IrField>) {
            // Other top-level units may be undecoded or concurrently lowered. Only this complete
            // output's bodies are stable here; private hidden-member uses in its nest remain checked.
            val owners = ArrayDeque<IrClass>()
            val visitedOwners = mutableSetOf<IrClass>()
            owners.add(outputUnit)
            while (owners.isNotEmpty()) {
                work()
                val owner = owners.removeFirst()
                requireProof(visitedOwners.add(owner))
                work(owner.innerClasses.size)
                owner.innerClasses.forEach(owners::addLast)
                work(owner.methods.size)
                for (method in owner.methods) {
                    if (method in helpers) continue
                    work(method.blocks.size)
                    val queue = ArrayDeque<Instruction>()
                    for (block in method.blocks) {
                        work(block.instructions.size); block.instructions.forEach(queue::addLast)
                    }
                    val seen = mutableSetOf<Instruction>()
                    while (queue.isNotEmpty()) {
                        val instruction = queue.removeFirst(); work(instruction.argCount + 1)
                        if (!seen.add(instruction) || instruction.contains(AttrFlag.DONT_GENERATE) ||
                            (method === clinit && instruction in consumed)) continue
                        if (instruction is FieldInstruction) {
                            type(instruction.fieldRef.declaringType); work(instruction.fieldRef.name.length + backing.name.length)
                            if (instruction.fieldRef.declaringType == ownType) {
                                requireProof(instruction.fieldRef.name != backing.name)
                                if (instruction.isPut && instruction.isStatic) requireProof(entries.none {
                                    work(it.name.length); it.name == instruction.fieldRef.name
                                })
                            }
                        }
                        if (instruction is InvokeInstruction) {
                            type(instruction.methodRef.declaringType); work(instruction.methodRef.name.length)
                            if (instruction.methodRef.declaringType == ownType) requireProof(helpers.none {
                                work(it.name.length)
                                it.name == "\$values" && it.name == instruction.methodRef.name && instruction.methodRef.paramTypes.isEmpty()
                            })
                        }
                        for (operand in instruction.args) if (operand is InstructionOperand) queue.addLast(operand.instruction)
                    }
                }
            }
        }

        private fun statements(method: IrMethod): List<Instruction> {
            val containers = when (val region = method.region) {
                null -> method.blocks.also { requireProof(it.size == 1) }
                is SequenceRegion -> region.children
                else -> throw Unsupported()
            }
            work(containers.size)
            val result = mutableListOf<Instruction>()
            for ((blockIndex, container) in containers.withIndex()) {
                val block = container as? BasicBlock ?: throw Unsupported()
                work(block.instructions.size)
                for (instruction in block.instructions) {
                    if (instruction.opcode == IrOpcode.GOTO) requireProof(block.successors.size == 1 &&
                        blockIndex + 1 < containers.size && block.successors.single() === containers[blockIndex + 1])
                    if (!instruction.contains(AttrFlag.DONT_GENERATE) && instruction.opcode != IrOpcode.NOP && instruction.opcode != IrOpcode.GOTO)
                        result.add(instruction)
                }
            }
            return result
        }

        private fun ensureVisibleArguments(arguments: List<Operand>, hidden: Set<SsaValue>) {
            val queue = ArrayDeque<Operand>()
            work(arguments.size); arguments.forEach(queue::addLast)
            val hiddenVariables = hidden.mapNotNullTo(mutableSetOf()) { it.localVar }
            while (queue.isNotEmpty()) {
                work()
                val operand = queue.removeFirst()
                val definition = when (operand) {
                    is RegisterOperand -> {
                        requireProof(operand.ssaValue !in hidden && operand.ssaValue?.localVar !in hiddenVariables)
                        operand.ssaValue?.assign?.parent
                    }
                    is InstructionOperand -> operand.instruction
                    else -> null
                }
                if (definition != null) { work(definition.argCount); definition.args.forEach(queue::addLast) }
            }
        }

        private fun ensureNoRemainingReads(statements: List<Instruction>, consumed: Set<Instruction>, hiddenParameters: Set<SsaValue> = emptySet()) {
            val queue = ArrayDeque<Instruction>()
            val hiddenVariables = hiddenParameters.mapNotNullTo(mutableSetOf()) { it.localVar }
            work(statements.size); statements.forEach(queue::addLast)
            while (queue.isNotEmpty()) {
                val instruction = queue.removeFirst(); work(instruction.argCount + 1)
                requireProof(instruction.result?.ssaValue?.localVar !in hiddenVariables)
                for (operand in instruction.args) when (operand) {
                    is RegisterOperand -> requireProof(operand.ssaValue !in hiddenParameters &&
                        operand.ssaValue?.localVar !in hiddenVariables && operand.ssaValue?.assign?.parent !in consumed)
                    is InstructionOperand -> queue.addLast(operand.instruction)
                    else -> Unit
                }
            }
        }

        private inner class ExpressionTrace(val method: IrMethod?, val allowEmptyArray: Boolean = false) {
            var regeneratedName: ConstStringInstruction? = null
            val definitions = linkedSetOf<Instruction>()
            private fun pure(instruction: Instruction): Boolean = instruction.opcode in PURE || instruction === regeneratedName
            private val evaluated = mutableListOf<Instruction>()
            private val active = mutableSetOf<Instruction>()
            fun origin(operand: Operand, depth: Int = 0): Operand {
                work(); requireProof(depth < 32)
                val def = definition(operand) ?: return operand
                return if (def.opcode in FORWARD && def.argCount == 1) origin(def.getArg(0), depth + 1) else operand
            }
            fun definition(operand: Operand): Instruction? = when (operand) {
                is RegisterOperand -> operand.ssaValue?.assign?.parent
                is InstructionOperand -> operand.instruction
                else -> null
            }
            fun visit(operand: Operand, depth: Int = 0) {
                work(); requireProof(depth < 32)
                if (operand is LiteralOperand) return
                if (operand is RegisterOperand && operand.ssaValue?.assign?.parent == null) {
                    requireProof(method != null && (operand.ssaValue === method.thisArg ||
                        operand.ssaValue?.contains(AttrFlag.METHOD_ARGUMENT) == true)); return
                }
                val instruction = definition(operand) ?: throw Unsupported()
                val emptyArray = allowEmptyArray && instruction is TypeInstruction && instruction.opcode == IrOpcode.NEW_ARRAY &&
                    instruction.referencedType == IrType.array(ownType) && instruction.argCount == 1 &&
                    (origin(instruction.getArg(0)) as? LiteralOperand)?.value == 0L
                requireProof((instruction.opcode in EXPRESSIONS || emptyArray) && active.add(instruction))
                val repeated = !definitions.add(instruction)
                // Repeated constants/copies are harmless; repeating even a heap read or allocation is not.
                requireProof(!repeated || instruction.opcode in PURE)
                work(instruction.argCount)
                instruction.args.forEach { visit(it, depth + 1) }
                if (!pure(instruction)) evaluated.add(instruction)
                active.remove(instruction)
            }
            fun validate(prefix: List<Instruction>, tail: List<Operand>) {
                val order = mutableListOf<Instruction>()
                fun original(instruction: Instruction, depth: Int) {
                    work(); requireProof(depth < 32)
                    for (operand in instruction.args) if (operand is InstructionOperand) original(operand.instruction, depth + 1)
                    if (!pure(instruction)) order.add(instruction)
                }
                for (instruction in prefix) {
                    if (instruction !in definitions) {
                        requireProof(instruction.opcode in PURE)
                        val previous = evaluated.size
                        visit(InstructionOperand(instruction))
                        requireProof(evaluated.size == previous)
                    }
                    original(instruction, 0)
                }
                // Include wrapped tail expressions: moving an earlier local read after a wrapped call
                // would otherwise pass a prefix-only comparison while reversing observable effects.
                for (operand in tail) if (operand is InstructionOperand) original(operand.instruction, 0)
                requireProof(evaluated == order)
            }
        }
        companion object {
            private val FORWARD = setOf(IrOpcode.MOVE, IrOpcode.MOVE_RESULT, IrOpcode.ONE_ARG, IrOpcode.CONST)
            private val PURE = FORWARD
            private val EXPRESSIONS = PURE + setOf(IrOpcode.CONST_STRING, IrOpcode.CONST_CLASS, IrOpcode.CAST, IrOpcode.CHECK_CAST,
                IrOpcode.ARITH, IrOpcode.NEG, IrOpcode.NOT, IrOpcode.INSTANCE_OF, IrOpcode.ARRAY_LENGTH,
                IrOpcode.ARRAY_GET, IrOpcode.FILLED_NEW_ARRAY, IrOpcode.STATIC_GET, IrOpcode.INSTANCE_GET,
                IrOpcode.INVOKE, IrOpcode.CONSTRUCTOR, IrOpcode.STRING_CONCAT)
        }
    }
}
