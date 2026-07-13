package com.jadxmp.ir.insn

import com.jadxmp.ir.attr.AttrNode

/**
 * One IR instruction: an [IrOpcode], an optional result register, and an ordered arg list.
 * **jadx: InsnNode**
 *
 * Deliberately mutable at well-defined points: passes append/replace args (to inline
 * sub-expressions), set the result, and attach attributes. The arg list is exposed read-only via
 * [args]; all edits go through [addArg]/[setArg]/[replaceArg]/[removeArg], which keep each operand's
 * [Operand.parent] back-link correct.
 *
 * Opcodes whose semantics need an operator (arithmetic, conditional branch, …) use the small
 * subclasses below. Opcodes whose semantics need a *symbolic payload* — the called method, the
 * accessed field, a string/type literal, a switch table, φ edges — use the canonical subclasses in
 * `SemanticInstructions.kt` ([InvokeInstruction], [FieldInstruction], [ConstStringInstruction],
 * [TypeInstruction], [SwitchInstruction], [PhiInstruction]). That payload is carried ON the
 * instruction, not via side attributes, so producer (pipeline) and consumers (both codegen backends)
 * share one representation. `open` so later stages can add further specialized instructions.
 */
open class Instruction(
    val opcode: IrOpcode,
    result: RegisterOperand? = null,
    args: List<Operand> = emptyList(),
) : AttrNode() {

    var result: RegisterOperand? = result
        set(value) {
            field?.parent = null
            value?.parent = this
            field = value
        }

    private val mutableArgs: MutableList<Operand> = ArrayList(args.size)

    /** Read-only view of the operands, in order. */
    val args: List<Operand> get() = mutableArgs

    /** Original bytecode offset this instruction was decoded from; -1 for synthetic instructions. */
    var offset: Int = -1

    init {
        result?.parent = this
        for (a in args) {
            a.parent = this
            mutableArgs.add(a)
        }
    }

    val argCount: Int get() = mutableArgs.size

    fun getArg(index: Int): Operand = mutableArgs[index]

    open fun addArg(arg: Operand) {
        arg.parent = this
        mutableArgs.add(arg)
    }

    open fun setArg(index: Int, arg: Operand) {
        mutableArgs[index].parent = null
        arg.parent = this
        mutableArgs[index] = arg
    }

    /** Replace the first occurrence of [old] with [new]; returns true if a replacement happened. */
    fun replaceArg(old: Operand, new: Operand): Boolean {
        val i = mutableArgs.indexOf(old)
        if (i < 0) return false
        setArg(i, new)
        return true
    }

    fun removeArg(index: Int): Operand {
        val removed = mutableArgs.removeAt(index)
        removed.parent = null
        return removed
    }

    override fun toString(): String = buildString {
        result?.let { append(it).append(" = ") }
        append(opcode.name)
        if (mutableArgs.isNotEmpty()) append(mutableArgs.joinToString(", ", " ("))
        if (mutableArgs.isNotEmpty()) append(")")
    }
}

/**
 * An [IrOpcode.ARITH] (or [IrOpcode.NEG]/[IrOpcode.NOT]) instruction carrying its operator.
 * jadx: ArithNode
 */
class ArithInstruction(
    val op: ArithOp,
    result: RegisterOperand? = null,
    args: List<Operand> = emptyList(),
) : Instruction(if (op == ArithOp.NEGATE) IrOpcode.NEG else IrOpcode.ARITH, result, args)

/**
 * An [IrOpcode.IF] two-way conditional branch carrying its comparison operator. jadx: IfNode
 */
class IfInstruction(
    val condition: ConditionOp,
    args: List<Operand> = emptyList(),
) : Instruction(IrOpcode.IF, result = null, args = args)
