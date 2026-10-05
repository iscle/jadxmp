package com.jadxmp.pipeline.types

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.attr.DecompileError
import com.jadxmp.ir.attr.IrAttrs
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.Operand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.pass.CancellationCheck

/** Reports definite verifier-category conflicts that best-effort inference must not hide. */
internal class ReturnTypeValidation(
    private val method: IrMethod,
    private val cancellation: CancellationCheck = CancellationCheck.None,
) {
    fun run() {
        if (!method.returnType.isPrimitive || method.returnType == IrType.VOID) return
        for (block in method.blocks) for (insn in block.instructions) {
            if (insn.opcode != IrOpcode.RETURN || insn.argCount == 0) continue
            if (!hasReferenceOrigin(insn.getArg(0))) continue
            if (!method.contains(IrAttrs.ERROR)) {
                method[IrAttrs.ERROR] = DecompileError("invalid register type: reference value reaches primitive return at ${insn.offset}")
            }
            method.add(AttrFlag.HAS_ERROR)
            return
        }
    }

    private fun hasReferenceOrigin(operand: Operand): Boolean {
        val seen = HashSet<SsaValue>()
        val pending = ArrayDeque<Operand>()
        pending.add(operand)
        while (pending.isNotEmpty()) {
            cancellation.ensureActive()
            val source = pending.removeLast()
            val definition = when (source) {
                is InstructionOperand -> source.instruction
                is RegisterOperand -> {
                    val value = source.ssaValue ?: continue
                    if (!seen.add(value)) continue
                    val def = value.assign.parent
                    if (def == null && (value === method.thisArg || value in method[PipelineAttrs.PARAMETERS].orEmpty()) &&
                        isReference(value.type)) return true
                    def
                }
                else -> null // Polymorphic literal zero can legally mean null, false or zero.
            } ?: continue
            when (definition.opcode) {
                IrOpcode.MOVE, IrOpcode.ONE_ARG, IrOpcode.PHI -> pending.addAll(definition.args)
                IrOpcode.CONST_STRING, IrOpcode.CONST_CLASS, IrOpcode.NEW_INSTANCE, IrOpcode.NEW_ARRAY,
                IrOpcode.FILLED_NEW_ARRAY, IrOpcode.CHECK_CAST, IrOpcode.MOVE_EXCEPTION, IrOpcode.CONSTRUCTOR -> return true
                IrOpcode.INVOKE -> if ((definition as? InvokeInstruction)?.methodRef?.returnType?.let(::isReference) == true) return true
                IrOpcode.INSTANCE_GET, IrOpcode.STATIC_GET -> if ((definition as? FieldInstruction)?.fieldRef?.type?.let(::isReference) == true) return true
                else -> Unit // Ambiguous/unresolved origins are not proof of a conflict.
            }
        }
        return false
    }

    private fun isReference(type: IrType): Boolean = type is IrType.Object || type.isArray
}
