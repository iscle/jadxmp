package com.jadxmp.ir.insn

import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.type.IrType

/**
 * The semantic [Instruction] subclasses that carry an instruction's symbolic payload — the resolved
 * method/field reference, the invoke kind, a string/type literal, a switch table, φ edges — that the
 * compact opcode-only [Instruction] base cannot hold.
 *
 * ## THE canonical instruction-payload contract
 *
 * These types (together with [MethodRef], [FieldRef], [InvokeKind]) are the **single** representation
 * of this payload shared across the engine. The IR-build/analysis stages (`core:pipeline`) *produce*
 * them; both codegen backends (`core:codegen`) *consume* them. There must be no parallel
 * ref/invoke/payload types living in pipeline or codegen and no attribute-based side channel for the
 * same data — everything flows through these IR types so producer and consumer cannot drift apart.
 *
 * Each is a normal `Instruction`: it has a result register, an operand [Instruction.args] list, an
 * offset, and attributes, and is usable anywhere an `Instruction` is (basic blocks, wrapped operands).
 */

/**
 * An `invoke-*` (or a normalized constructor call). The operand args are the actual argument
 * expressions; for the instance forms ([InvokeKind.hasInstance]) arg 0 is the receiver.
 * jadx: InvokeNode
 *
 * A **raw `invoke-custom` (invokedynamic)** is modeled by the sibling [InvokeCustomInstruction] (also
 * an `IrOpcode.INVOKE`), which carries the decoded call-site payload (bootstrap method, name, proto).
 * [InvokeKind.CUSTOM] exists for classification only. [InvokeKind.POLYMORPHIC] IS representable here —
 * its call-site proto rides on [methodRef].
 *
 * @param opcode either [IrOpcode.INVOKE] (default) or [IrOpcode.CONSTRUCTOR] once a `<init>` call has
 *   been normalized by a later pass.
 */
class InvokeInstruction(
    val methodRef: MethodRef,
    val invokeKind: InvokeKind,
    result: RegisterOperand? = null,
    args: List<Operand> = emptyList(),
    opcode: IrOpcode = IrOpcode.INVOKE,
) : Instruction(opcode, result, args) {
    init {
        require(opcode == IrOpcode.INVOKE || opcode == IrOpcode.CONSTRUCTOR) {
            "InvokeInstruction opcode must be INVOKE or CONSTRUCTOR, was $opcode"
        }
    }

    val isStatic: Boolean get() = invokeKind.isStatic
    val hasInstance: Boolean get() = invokeKind.hasInstance

    /** The receiver operand for instance calls (arg 0), or null for static/custom. */
    val instanceArg: Operand? get() = if (hasInstance && argCount > 0) getArg(0) else null
}

/**
 * A raw `invoke-custom` (invokedynamic) call site, rendered — like jadx's `InvokeCustomRawNode` — as a
 * polymorphic-style call through the resolved bootstrap:
 * `(<Ret>) bootstrap(MethodHandles.lookup(), "<name>", MethodType.methodType(<Ret>, <params…>))
 * .dynamicInvoker().invoke(<runtime args>) /* invoke-custom */`.
 *
 * This is a normal [IrOpcode.INVOKE] instruction, so every generic pass (SSA renaming, type inference,
 * dead-code / out-of-SSA, expression shaping, structuring) that iterates operands/result handles it
 * unchanged: [Instruction.args] are the RUNTIME register arguments (data flow preserved) and [result]
 * is the (folded `move-result`) destination, given the [protoReturnType] so the value types correctly.
 * The bootstrap "static" arguments (`lookup()`, the name, the `MethodType`) are compile-time constants
 * synthesized by codegen — they are not register operands and so carry no data flow.
 *
 * @param bootstrapMethod the resolved bootstrap method the call site links (jadx: the `resolve` invoke).
 * @param bootstrapKind how the bootstrap dispatches; only [InvokeKind.STATIC] is faithfully renderable
 *   (bootstrap methods are static per the JVM spec).
 * @param callSiteName the invokedynamic name (e.g. `"func"`).
 * @param protoReturnType the call-site proto return type (the `.invoke(…)` result / result cast type).
 * @param protoParamTypes the call-site proto parameter types (the runtime argument types).
 * @param renderable false when the call site cannot be faithfully rendered (a field handle, a
 *   non-static bootstrap, or extra bootstrap arguments this backend does not spell); codegen then
 *   bails to a visible error marker rather than emit wrong code (rule 4).
 */
class InvokeCustomInstruction(
    val bootstrapMethod: MethodRef,
    val bootstrapKind: InvokeKind,
    val callSiteName: String,
    val protoReturnType: IrType,
    val protoParamTypes: List<IrType>,
    val renderable: Boolean,
    result: RegisterOperand? = null,
    args: List<Operand> = emptyList(),
) : Instruction(IrOpcode.INVOKE, result, args)

/**
 * An instance/static field get or put. The [Instruction.opcode] is derived from [isStatic]/[isPut].
 * For a get, arg 0 is the instance (instance get) or empty (static get); the result holds the value.
 * For a put, the last arg is the value. jadx: IndexInsnNode over a FieldInfo
 */
class FieldInstruction(
    val fieldRef: FieldRef,
    val isStatic: Boolean,
    val isPut: Boolean,
    result: RegisterOperand? = null,
    args: List<Operand> = emptyList(),
) : Instruction(opcodeFor(isStatic, isPut), result, args) {
    companion object {
        private fun opcodeFor(isStatic: Boolean, isPut: Boolean): IrOpcode = when {
            isStatic && isPut -> IrOpcode.STATIC_PUT
            isStatic -> IrOpcode.STATIC_GET
            isPut -> IrOpcode.INSTANCE_PUT
            else -> IrOpcode.INSTANCE_GET
        }
    }
}

/** A `const-string`. [value] is the raw (unescaped) string. jadx: ConstStringNode */
class ConstStringInstruction(
    val value: String,
    result: RegisterOperand? = null,
) : Instruction(IrOpcode.CONST_STRING, result)

/**
 * An instruction that references a resolved [IrType] the result register cannot convey — the class
 * literal of `const-class`, the right-hand type of `instanceof`, a `check-cast` target, or the
 * created type of `new-instance`/`new-array`/`filled-new-array`. jadx: IndexInsnNode over a type
 *
 * **[referencedType] is always the WHOLE created/referenced type, never an element type** (matches
 * jadx `IndexInsnNode`). For [IrOpcode.NEW_ARRAY]/[IrOpcode.FILLED_NEW_ARRAY] it is the ARRAY type
 * (e.g. `int[]`); codegen derives the element type via [IrType.arrayElement] to render
 * `new int[n]`. Do NOT store a bare element type here.
 *
 * @param opcode one of [IrOpcode.CONST_CLASS], [IrOpcode.INSTANCE_OF], [IrOpcode.CHECK_CAST],
 *   [IrOpcode.NEW_INSTANCE], [IrOpcode.NEW_ARRAY], [IrOpcode.FILLED_NEW_ARRAY].
 */
class TypeInstruction(
    opcode: IrOpcode,
    val referencedType: IrType,
    result: RegisterOperand? = null,
    args: List<Operand> = emptyList(),
) : Instruction(opcode, result, args) {
    init {
        require(opcode in TYPE_OPCODES) {
            "TypeInstruction opcode must be one of $TYPE_OPCODES, was $opcode"
        }
    }

    companion object {
        private val TYPE_OPCODES = setOf(
            IrOpcode.CONST_CLASS, IrOpcode.INSTANCE_OF, IrOpcode.CHECK_CAST,
            IrOpcode.NEW_INSTANCE, IrOpcode.NEW_ARRAY, IrOpcode.FILLED_NEW_ARRAY,
        )
    }
}

/**
 * A `switch`. [keys] and [caseTargets] are parallel arrays; [caseTargets] and [defaultTarget] are
 * absolute code-unit offsets (as decoded, before structuring resolves them to regions). The single
 * arg is the selector. All three tables are mutable because the packed-switch payload is resolved in
 * a second pass after the instruction is created. jadx: SwitchInsn
 */
class SwitchInstruction(
    keys: IntArray,
    caseTargets: IntArray,
    defaultTarget: Int,
    selector: Operand,
) : Instruction(IrOpcode.SWITCH, result = null, args = listOf(selector)) {
    var keys: IntArray = keys
    var caseTargets: IntArray = caseTargets
    var defaultTarget: Int = defaultTarget

    /** The selector operand being switched on. */
    val selector: Operand get() = getArg(0)
}

/**
 * A `fill-array-data`: bulk-fills the array in operand 0 from a decoded constant blob. jadx: FillArrayInsn
 *
 * The decoded element data rides ON the instruction (not a side attribute) so both codegen backends can
 * render the per-element assignments without reaching into a pipeline-private payload type — the same
 * "payload on the canonical instruction" contract as the other subclasses above.
 *
 * [elements] holds each element's raw bit pattern **sign-extended to a `Long`**, exactly as a
 * [LiteralOperand.value] would: a byte/short/int as its signed value, a long as itself, and a
 * float/double element as its raw IEEE-754 bits. The concrete primitive type is deliberately NOT fixed
 * here — [elementWidth] (1/2/4/8 bytes) plus the array operand's inferred element type select it at
 * codegen. This mirrors jadx, which defers the byte-vs-boolean / int-vs-float / long-vs-double choice
 * until the filled array's element type is known (`FillArrayData.getLiteralArgs(elType)`).
 *
 * The single operand ([array]) is the array register being filled (jadx: `getArg(0)`).
 */
class FillArrayInstruction(
    val elementWidth: Int,
    val elements: LongArray,
    array: Operand,
) : Instruction(IrOpcode.FILL_ARRAY, result = null, args = listOf(array)) {
    init {
        require(elementWidth == 1 || elementWidth == 2 || elementWidth == 4 || elementWidth == 8) {
            "fill-array element width must be 1, 2, 4, or 8 bytes, was $elementWidth"
        }
    }

    /** The array register being bulk-filled (operand 0). */
    val array: Operand get() = getArg(0)

    /** Number of elements written (== [elements] size). */
    val size: Int get() = elements.size
}

/**
 * A φ operand: a register read tagged with the predecessor block it flows in from.  **jadx: (PhiInsn
 * block binding)**
 *
 * The [from] block travels ON the operand, so a φ's value/block pairing CANNOT desync under the base
 * [Instruction] mutation API — removing the operand removes its block with it. Being a
 * [RegisterOperand], it participates in SSA use lists and type inference exactly like any read.
 */
class PhiOperand(
    regNum: Int,
    type: IrType,
    val from: BasicBlock,
) : RegisterOperand(regNum, type)

/** One incoming edge of a [PhiInstruction], in read form. */
data class PhiIncoming(val value: RegisterOperand, val from: BasicBlock)

/**
 * An SSA φ-function: selects among its incoming values by the predecessor the block was entered from.
 * jadx: PhiInsn
 *
 * Every operand is a [PhiOperand] (added via [addIncoming]), which carries its own source block, so
 * there is no parallel block list to fall out of sync. SSA passes that prune/merge edges use the base
 * `removeArg` (or [removeIncoming]); the block always travels with its value.
 */
class PhiInstruction(
    result: RegisterOperand? = null,
) : Instruction(IrOpcode.PHI, result) {

    /**
     * Every φ operand must be a [PhiOperand] carrying its predecessor block, so generic operand
     * rewriting (SSA renaming, copy propagation) that routes through [addArg]/[setArg]/`replaceArg`
     * fails fast at the bad write instead of crashing later in structuring/codegen. A legitimate
     * value rewrite constructs a new `PhiOperand(newReg, type, sameBlock)`.
     */
    override fun addArg(arg: Operand) {
        require(arg is PhiOperand) {
            "PhiInstruction operands must be PhiOperand (carry a predecessor block); use addIncoming"
        }
        super.addArg(arg)
    }

    override fun setArg(index: Int, arg: Operand) {
        require(arg is PhiOperand) {
            "PhiInstruction operands must be PhiOperand; construct a new PhiOperand(reg, type, block)"
        }
        super.setArg(index, arg)
    }

    /** Add an incoming edge (the operand carries its own predecessor block). */
    fun addIncoming(edge: PhiOperand) = addArg(edge)

    /** Convenience: build and add a [PhiOperand] for register [regNum]:[type] coming from [from]. */
    fun addIncoming(regNum: Int, type: IrType, from: BasicBlock): PhiOperand =
        PhiOperand(regNum, type, from).also { addArg(it) }

    /** Remove the edge at [index], returning it. Block travels with the operand. */
    fun removeIncoming(index: Int): PhiOperand = removeArg(index) as PhiOperand

    /** Remove the edge whose predecessor is [from]; returns true if one was removed. */
    fun removeIncoming(from: BasicBlock): Boolean {
        val i = args.indexOfFirst { (it as PhiOperand).from == from }
        if (i < 0) return false
        removeArg(i)
        return true
    }

    /** The predecessor block that supplies the operand at [argIndex]. */
    fun blockFor(argIndex: Int): BasicBlock = (getArg(argIndex) as PhiOperand).from

    /** The incoming edges, in operand order. */
    val incoming: List<PhiIncoming>
        get() = List(argCount) { val e = getArg(it) as PhiOperand; PhiIncoming(e, e.from) }
}
