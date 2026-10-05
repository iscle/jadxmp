package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrContainer
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.type.IrType

/** Small source-reconstruction proofs. Unrecognised control flow keeps the original method body. */
internal object KotlinConstructorProof {
    fun statements(method: IrMethod): List<Instruction>? {
        val output = mutableListOf<Instruction>()
        fun visit(container: IrContainer): Boolean = when (container) {
            is BasicBlock -> {
                output.addAll(container.instructions.filterNot { it.contains(AttrFlag.DONT_GENERATE) || it.opcode == IrOpcode.NOP })
                true
            }
            is SequenceRegion -> container.children.all(::visit)
            else -> false
        }
        val region = method.region
        if (region != null) {
            if (!visit(region)) return null
        } else {
            // Without a region, multiple CFG blocks have no proven source execution order.
            if (method.blocks.size != 1 || !visit(method.blocks.single())) return null
        }
        return output
    }

    fun initializes(field: IrField): Boolean {
        if (field.accessFlags and KotlinModifiers.STATIC != 0) return false
        val constructors = field.declaringClass.methods.filter { it.name == "<init>" && !it.contains(AttrFlag.DONT_GENERATE) }
        return constructors.isNotEmpty() && constructors.all { method ->
            val statements = statements(method) ?: return@all false
            val stores = statements.filterIsInstance<FieldInstruction>().filter { matches(it, field) && it.opcode == IrOpcode.INSTANCE_PUT }
            stores.size == 1 && isThis(stores[0].getArg(0), method) &&
                statements.take(statements.indexOf(stores[0])).none { it.opcode == IrOpcode.RETURN || it.opcode == IrOpcode.THROW }
        }
    }

    fun canonicalConstructor(method: IrMethod, properties: List<IrField>): Boolean {
        val statements = statements(method)?.toMutableList() ?: return false
        if (statements.lastOrNull()?.let { it.opcode == IrOpcode.RETURN && it.argCount == 0 } == true) statements.removeLast()
        val first = statements.firstOrNull() as? InvokeInstruction
        if (first != null && first.methodRef.name == "<init>" && first.methodRef.declaringType == IrType.OBJECT &&
            first.methodRef.paramTypes.isEmpty() && first.argCount == 1 && isThis(first.getArg(0), method)
        ) statements.removeAt(0)
        if (statements.size != properties.size) return false
        val arguments = parameters(statements)
        if (arguments.size != method.argTypes.size) return false
        return properties.indices.all { index ->
            val store = statements[index] as? FieldInstruction ?: return@all false
            store.opcode == IrOpcode.INSTANCE_PUT && matches(store, properties[index]) && store.argCount == 2 &&
                isThis(store.getArg(0), method) && parameterRegister(store.getArg(1)) == arguments[index] &&
                store.getArg(1).type == method.argTypes[index]
        }
    }

    fun canonicalComponent(method: IrMethod, field: IrField): Boolean {
        val statement = statements(method)?.singleOrNull() ?: return false
        val get = (statement.args.singleOrNull() as? InstructionOperand)?.instruction as? FieldInstruction ?: return false
        return statement.opcode == IrOpcode.RETURN && get.opcode == IrOpcode.INSTANCE_GET && matches(get, field) &&
            get.argCount == 1 && isThis(get.getArg(0), method)
    }

    fun canonicalCopy(method: IrMethod): Boolean {
        val statements = statements(method) ?: return false
        val statement = statements.lastOrNull() ?: return false
        if (statement.opcode != IrOpcode.RETURN) return false
        val returned = statement.args.singleOrNull() ?: return false
        val call = when {
            statements.size == 1 -> (returned as? InstructionOperand)?.instruction as? InvokeInstruction
            statements.size == 2 && returned is RegisterOperand -> {
                val invocation = statements.first() as? InvokeInstruction ?: return false
                val result = invocation.result ?: return false
                if (result.regNum != returned.regNum || result.ssaValue !== returned.ssaValue) return false
                invocation
            }
            else -> null
        } ?: return false
        if (call.opcode != IrOpcode.CONSTRUCTOR ||
            call.methodRef.declaringType != IrType.objectType(method.declaringClass.fullName) ||
            call.methodRef.paramTypes != method.argTypes || call.argCount != method.argTypes.size
        ) return false
        val arguments = parameters(listOf(call))
        return arguments.size == method.argTypes.size && call.args.indices.all {
            parameterRegister(call.getArg(it)) == arguments[it] && call.getArg(it).type == method.argTypes[it]
        }
    }

    private fun parameters(instructions: List<Instruction>): List<Int> {
        val result = mutableSetOf<Int>()
        fun visit(operand: Operand) {
            parameterRegister(operand)?.let(result::add)
            if (operand is InstructionOperand) operand.instruction.args.forEach(::visit)
        }
        instructions.forEach { it.args.forEach(::visit) }
        return result.sorted()
    }

    private fun parameterRegister(operand: Operand): Int? {
        val register = operand as? RegisterOperand ?: return null
        val value = register.ssaValue ?: return null
        if (value.localVar?.isThis == true) return null
        return register.regNum.takeIf { value.contains(AttrFlag.METHOD_ARGUMENT) || value.localVar?.contains(AttrFlag.METHOD_ARGUMENT) == true }
    }

    private fun matches(instruction: FieldInstruction, field: IrField): Boolean =
        instruction.fieldRef.name == field.name && instruction.fieldRef.type == field.type &&
            instruction.fieldRef.declaringType == IrType.objectType(field.declaringClass.fullName)

    private fun isThis(operand: Operand, method: IrMethod): Boolean {
        val value = (operand as? RegisterOperand)?.ssaValue ?: return false
        return value === method.thisArg || value.localVar?.isThis == true
    }
}
