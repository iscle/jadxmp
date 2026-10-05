package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrContainer
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.region.*
import com.jadxmp.ir.type.IrType

/**
 * Source nullability introduced by JVM array boundaries, nullable elements and explicit nulls.
 * The model follows storage copies and method returns; it does not assume general object parameters
 * or unknown external object-returning methods are nullable. No IR type or shared analysis is mutated.
 */
internal class KotlinArrayNullability(private val root: IrRoot?) {
    private data class RegisterKey(val method: IrMethod, val register: Int)
    private data class Body(val writes: Map<Any, List<Instruction>>, val returns: List<Operand>)
    private sealed class Node {
        data class Value(val method: IrMethod, val operand: Operand) : Node()
        data class Expression(val method: IrMethod, val instruction: Instruction) : Node()
        data class Return(val method: IrMethod) : Node()
    }
    private val bodies = mutableMapOf<IrMethod, Body>()

    fun isNullable(method: IrMethod, operand: Operand): Boolean = reachesNull(Node.Value(method, operand))
    fun returnsNullable(method: IrMethod): Boolean = reachesNull(Node.Return(method))

    private fun reachesNull(start: Node): Boolean {
        val pending = ArrayDeque<Node>()
        val visited = mutableSetOf<Any>()
        pending.add(start)
        while (pending.isNotEmpty()) {
            when (val node = pending.removeFirst()) {
                is Node.Return -> {
                    if (!isReference(node.method.returnType) || !visited.add(node.method)) continue
                    if (node.method.returnType is IrType.ArrayType) return true
                    body(node.method).returns.forEach { pending.add(Node.Value(node.method, it)) }
                }
                is Node.Value -> {
                    val operand = node.operand
                    if (operand is InstructionOperand) {
                        pending.add(Node.Expression(node.method, operand.instruction))
                        continue
                    }
                    if (!isReference(operand.type)) continue
                    when (operand) {
                        is LiteralOperand -> if (operand.value == 0L) return true
                        is InstructionOperand -> pending.add(Node.Expression(node.method, operand.instruction))
                        is RegisterOperand -> {
                            val local = operand.ssaValue?.localVar
                            if (local?.isThis == true) continue
                            val parameter = local?.contains(AttrFlag.METHOD_ARGUMENT) == true ||
                                operand.ssaValue?.contains(AttrFlag.METHOD_ARGUMENT) == true
                            if (parameter && (operand.type is IrType.ArrayType || local?.type is IrType.ArrayType)) return true
                            val key = key(node.method, operand)
                            if (!visited.add(key)) continue
                            val writes = body(node.method).writes[key].orEmpty()
                            if (writes.isEmpty()) {
                                if (!parameter && operand.type is IrType.ArrayType) return true
                            } else writes.forEach { pending.add(Node.Expression(node.method, it)) }
                        }
                    }
                }
                is Node.Expression -> {
                    val instruction = node.instruction
                    if (!visited.add(instruction)) continue
                    fun argument(index: Int) {
                        if (index < instruction.argCount) pending.add(Node.Value(node.method, instruction.getArg(index)))
                    }
                    when (instruction.opcode) {
                        IrOpcode.CONST, IrOpcode.MOVE, IrOpcode.MOVE_RESULT, IrOpcode.ONE_ARG, IrOpcode.CHECK_CAST -> argument(0)
                        IrOpcode.TERNARY -> { argument(1); argument(2) }
                        IrOpcode.ARRAY_GET -> {
                            val resultType = instruction.result?.type ?: instruction.args.firstOrNull()?.type?.arrayElement
                            if (resultType != null && isReference(resultType)) return true
                        }
                        IrOpcode.INVOKE -> {
                            val reference = (instruction as? InvokeInstruction)?.methodRef ?: continue
                            if (reference.returnType is IrType.ArrayType) return true
                            val owner = (reference.declaringType as? IrType.Object)?.className
                            val target = owner?.let { root?.findClass(it) }?.methods?.firstOrNull {
                                it.name == reference.name && it.argTypes == reference.paramTypes && it.returnType == reference.returnType
                            }
                            if (target != null) pending.add(Node.Return(target))
                        }
                        IrOpcode.INSTANCE_GET, IrOpcode.STATIC_GET -> {
                            val type = (instruction as? FieldInstruction)?.fieldRef?.type ?: instruction.result?.type
                            if (type is IrType.ArrayType) return true
                        }
                        // Allocations, constructors, class/string constants and primitive results are non-null.
                        else -> Unit
                    }
                }
            }
        }
        return false
    }

    private fun key(method: IrMethod, operand: RegisterOperand): Any =
        operand.ssaValue?.localVar ?: operand.ssaValue ?: RegisterKey(method, operand.regNum)

    private fun body(method: IrMethod): Body = bodies.getOrPut(method) {
        val writes = mutableMapOf<Any, MutableList<Instruction>>()
        val returns = mutableListOf<Operand>()
        val containers = ArrayDeque<IrContainer>()
        method.region?.let(containers::add) ?: containers.addAll(method.blocks)
        val visited = mutableSetOf<IrContainer>()
        fun instruction(instruction: Instruction) {
            if (instruction.contains(AttrFlag.DONT_GENERATE)) return
            instruction.result?.let { writes.getOrPut(key(method, it)) { mutableListOf() }.add(instruction) }
            if (instruction.opcode == IrOpcode.RETURN && instruction.argCount > 0) returns.add(instruction.getArg(0))
        }
        while (containers.isNotEmpty()) {
            val container = containers.removeFirst()
            if (!visited.add(container)) continue
            when (container) {
                is BasicBlock -> container.instructions.forEach(::instruction)
                is SequenceRegion -> containers.addAll(container.children)
                is IfRegion -> { containers.add(container.thenRegion); container.elseRegion?.let(containers::add) }
                is LoopRegion -> {
                    container[CodegenKeys.LOOP_INIT]?.let(::instruction)
                    container[CodegenKeys.LOOP_UPDATE]?.let(::instruction)
                    containers.add(container.body)
                }
                is SwitchRegion -> {
                    container.cases.forEach { containers.add(it.body) }
                    container.defaultCase?.let(containers::add)
                }
                is TryCatchRegion -> {
                    containers.add(container.tryRegion)
                    container.catches.forEach { containers.add(it.body) }
                    container.finallyRegion?.let(containers::add)
                }
                is SyncRegion -> containers.add(container.body)
            }
        }
        Body(writes, returns)
    }

    companion object {
        fun isReference(type: IrType): Boolean = type is IrType.Object || type is IrType.ArrayType ||
            type is IrType.TypeVariable || type is IrType.Wildcard
    }
}
