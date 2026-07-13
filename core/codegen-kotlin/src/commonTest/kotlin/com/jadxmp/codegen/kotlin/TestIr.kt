package com.jadxmp.codegen.kotlin

import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.ArithInstruction
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.insn.ConstStringInstruction
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.FieldRef
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
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.node.LocalVar
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.type.IrType

/**
 * Hand-construction helpers for `core:ir` values, so Kotlin-codegen tests build IR directly (no
 * pipeline). Mirrors the Java backend's `TestIr` so both suites read alike.
 */
object Flags {
    const val PUBLIC = 0x0001
    const val PRIVATE = 0x0002
    const val PROTECTED = 0x0004
    const val STATIC = 0x0008
    const val FINAL = 0x0010
    const val ABSTRACT = 0x0400
    const val INTERFACE = 0x0200
    const val ANNOTATION = 0x2000
    const val ENUM = 0x4000
}

fun irClass(
    fullName: String,
    accessFlags: Int = Flags.PUBLIC or Flags.FINAL,
    superType: IrType? = null,
    interfaces: List<IrType> = emptyList(),
    root: IrRoot = IrRoot(),
): IrClass {
    val cls = IrClass(root, fullName, accessFlags, superType, interfaces)
    root.addClass(cls)
    return cls
}

fun IrClass.method(
    name: String,
    returnType: IrType = IrType.VOID,
    argTypes: List<IrType> = emptyList(),
    accessFlags: Int = Flags.PUBLIC,
    configure: IrMethod.() -> Unit = {},
): IrMethod {
    val m = IrMethod(this, name, returnType, argTypes, accessFlags)
    methods.add(m)
    m.configure()
    return m
}

/** Add a single basic block of straight-line [instructions] to a method. */
fun IrMethod.body(vararg instructions: Instruction) {
    val block = com.jadxmp.ir.node.BasicBlock(0)
    instructions.forEach { block.instructions.add(it) }
    blocks.add(block)
}

fun generate(cls: IrClass): String = KotlinCodeGenerator().generate(cls).code

// ---- operands ----

fun lit(value: Long, type: IrType): LiteralOperand = LiteralOperand(value, type)
fun intLit(value: Int): LiteralOperand = LiteralOperand(value.toLong(), IrType.INT)
fun reg(num: Int, type: IrType): RegisterOperand = RegisterOperand(num, type)
fun expr(insn: Instruction): InstructionOperand = InstructionOperand(insn)

/** A source-level local variable yielding fresh, but linked, register operands via [ref]. */
class Local(
    val regNum: Int,
    val type: IrType,
    name: String? = null,
    isParam: Boolean = false,
    isThis: Boolean = false,
) {
    val localVar = LocalVar().also {
        it.name = name
        it.type = type
        it.isThis = isThis
        if (isParam) it.add(AttrFlag.METHOD_ARGUMENT)
    }
    val ssaValue = SsaValue(regNum, 0, RegisterOperand(regNum, type)).also {
        localVar.addSsaValue(it)
        if (isParam) it.add(AttrFlag.METHOD_ARGUMENT)
    }

    fun ref(): RegisterOperand = RegisterOperand(regNum, type).also { it.ssaValue = ssaValue }
}

// ---- instructions ----

fun constString(text: String): Instruction = ConstStringInstruction(text, result = reg(-1, IrType.STRING))

fun assign(result: RegisterOperand, value: Instruction): Instruction {
    value.result = result
    return value
}

fun arith(op: ArithOp, left: Operand, right: Operand, result: RegisterOperand? = null): ArithInstruction =
    ArithInstruction(op, result, listOf(left, right))

fun ret(value: Operand? = null): Instruction =
    if (value == null) Instruction(IrOpcode.RETURN) else Instruction(IrOpcode.RETURN, args = listOf(value))

fun staticInvoke(
    owner: IrType,
    name: String,
    returnType: IrType,
    argTypes: List<IrType>,
    args: List<Operand>,
    result: RegisterOperand? = null,
): Instruction =
    InvokeInstruction(MethodRef(owner, name, returnType, argTypes), InvokeKind.STATIC, result, args)

fun virtualInvoke(
    receiver: Operand,
    owner: IrType,
    name: String,
    returnType: IrType,
    argTypes: List<IrType>,
    args: List<Operand> = emptyList(),
    result: RegisterOperand? = null,
): Instruction =
    InvokeInstruction(MethodRef(owner, name, returnType, argTypes), InvokeKind.VIRTUAL, result, listOf(receiver) + args)

fun constructor(
    owner: IrType,
    argTypes: List<IrType>,
    args: List<Operand>,
    result: RegisterOperand? = null,
): Instruction = InvokeInstruction(
    MethodRef(owner, MethodRef.CONSTRUCTOR_NAME, owner, argTypes),
    InvokeKind.DIRECT,
    result,
    args,
    opcode = IrOpcode.CONSTRUCTOR,
)

fun instanceGet(receiver: Operand, field: FieldRef, result: RegisterOperand? = null): Instruction =
    FieldInstruction(field, isStatic = false, isPut = false, result = result, args = listOf(receiver))

fun staticGet(field: FieldRef, result: RegisterOperand? = null): Instruction =
    FieldInstruction(field, isStatic = true, isPut = false, result = result)

fun instancePut(receiver: Operand, value: Operand, field: FieldRef): Instruction =
    FieldInstruction(field, isStatic = false, isPut = true, args = listOf(receiver, value))

/** A primitive numeric conversion to [target] (the cast's result type). */
fun cast(target: IrType, value: Operand, result: RegisterOperand? = reg(-1, target)): Instruction =
    Instruction(IrOpcode.CAST, result = result, args = listOf(value))

/** A reference `check-cast` to [target]. */
fun checkCast(target: IrType, value: Operand, result: RegisterOperand? = reg(-1, target)): Instruction =
    TypeInstruction(IrOpcode.CHECK_CAST, target, result, listOf(value))

/** An `instanceof`/`is` test against [target]. */
fun instanceOf(target: IrType, value: Operand, result: RegisterOperand? = reg(-1, IrType.BOOLEAN)): Instruction =
    TypeInstruction(IrOpcode.INSTANCE_OF, target, result, listOf(value))

/** A `new-instance` of [type] (no constructor args). */
fun newInstance(type: IrType, result: RegisterOperand? = reg(-1, type)): Instruction =
    TypeInstruction(IrOpcode.NEW_INSTANCE, type, result)

/** A sized `new <arrayType>[size]`. [arrayType] is the WHOLE array type (e.g. `int[]`). */
fun newArray(arrayType: IrType, size: Operand, result: RegisterOperand? = null): Instruction =
    TypeInstruction(IrOpcode.NEW_ARRAY, arrayType, result, listOf(size))
