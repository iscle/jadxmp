package com.jadxmp.pipeline.decode

import com.jadxmp.input.FillArrayDataPayload
import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.ArithInstruction
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.insn.FieldInstruction
import com.jadxmp.ir.insn.FillArrayInstruction
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.InvokeKind
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeFieldRef
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.Insn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecodeTest {

    private fun decode(regs: Int, vararg insns: Insn): MethodCode =
        MethodDecoder().decode(FakeCodeReader(regs, insns.toList()))

    @Test
    fun constAndReturn() {
        val code = decode(
            1,
            Insn(Opcode.CONST, offset = 0, registers = intArrayOf(0), literal = 5),
            Insn(Opcode.RETURN, offset = 1, registers = intArrayOf(0)),
        )
        assertEquals(2, code.instructions.size)
        val const = code.instructions[0].insn
        assertEquals(IrOpcode.CONST, const.opcode)
        assertEquals(0, (const.result as RegisterOperand).regNum)
        assertEquals(5L, (const.getArg(0) as LiteralOperand).value)
        val ret = code.instructions[1]
        assertEquals(IrOpcode.RETURN, ret.insn.opcode)
        assertTrue(!ret.fallsThrough)
    }

    @Test
    fun nopsAreDropped() {
        val code = decode(
            1,
            Insn(Opcode.NOP, offset = 0),
            Insn(Opcode.RETURN_VOID, offset = 1),
        )
        assertEquals(1, code.instructions.size)
        assertEquals(IrOpcode.RETURN, code.instructions[0].insn.opcode)
    }

    @Test
    fun moveResultFoldsIntoInvoke() {
        val ref = FakeMethodRef("Lcom/example/Foo;", "bar", "I", emptyList())
        val code = decode(
            1,
            Insn(Opcode.INVOKE_STATIC, offset = 0, registers = intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = ref),
            Insn(Opcode.MOVE_RESULT, offset = 1, registers = intArrayOf(0)),
            Insn(Opcode.RETURN, offset = 2, registers = intArrayOf(0)),
        )
        // move-result is folded away: invoke + return only.
        assertEquals(2, code.instructions.size)
        val invoke = code.instructions[0].insn as InvokeInstruction
        val result = invoke.result as RegisterOperand
        assertEquals(0, result.regNum)
        assertEquals(IrType.INT, result.type)
    }

    @Test
    fun invokeVirtualMapsReceiverAndArgs() {
        val ref = FakeMethodRef("Lcom/example/Foo;", "bar", "V", listOf("I", "Ljava/lang/String;"))
        val code = decode(
            3,
            Insn(Opcode.INVOKE_VIRTUAL, offset = 0, registers = intArrayOf(0, 1, 2), indexType = IndexType.METHOD_REF, methodRef = ref),
        )
        val invoke = code.instructions[0].insn as InvokeInstruction
        assertEquals(InvokeKind.VIRTUAL, invoke.invokeKind)
        assertEquals(3, invoke.argCount) // receiver + 2 params
        assertEquals(IrType.objectType("com.example.Foo"), (invoke.getArg(0) as RegisterOperand).type)
        assertEquals(IrType.INT, (invoke.getArg(1) as RegisterOperand).type)
        assertEquals(IrType.STRING, (invoke.getArg(2) as RegisterOperand).type)
    }

    @Test
    fun addIntThreeRegisters() {
        val code = decode(
            3,
            Insn(Opcode.ADD_INT, offset = 0, registers = intArrayOf(0, 1, 2)),
        )
        val add = code.instructions[0].insn as ArithInstruction
        assertEquals(ArithOp.ADD, add.op)
        assertEquals(0, (add.result as RegisterOperand).regNum)
        assertEquals(1, (add.getArg(0) as RegisterOperand).regNum)
        assertEquals(2, (add.getArg(1) as RegisterOperand).regNum)
    }

    @Test
    fun addIntTwoAddressUsesDestAsFirstSource() {
        val code = decode(
            2,
            Insn(Opcode.ADD_INT, offset = 0, registers = intArrayOf(0, 1)),
        )
        val add = code.instructions[0].insn as ArithInstruction
        assertEquals(0, (add.result as RegisterOperand).regNum)
        assertEquals(0, (add.getArg(0) as RegisterOperand).regNum) // dest is first source
        assertEquals(1, (add.getArg(1) as RegisterOperand).regNum)
    }

    @Test
    fun ifBranchResolvesTargetAndFallsThrough() {
        val code = decode(
            1,
            Insn(Opcode.IF_EQZ, offset = 0, registers = intArrayOf(0), target = 3),
            Insn(Opcode.RETURN_VOID, offset = 1),
            Insn(Opcode.NOP, offset = 2),
            Insn(Opcode.RETURN_VOID, offset = 3),
        )
        val ifDi = code.instructions[0]
        assertEquals(IrOpcode.IF, ifDi.insn.opcode)
        assertTrue(ifDi.fallsThrough)
        assertEquals(listOf(3), ifDi.targets.toList())
    }

    @Test
    fun rsubIsLiteralMinusRegister() {
        val code = decode(
            2,
            Insn(Opcode.RSUB_INT, offset = 0, registers = intArrayOf(0, 1), literal = 10),
        )
        val sub = code.instructions[0].insn as ArithInstruction
        assertEquals(ArithOp.SUB, sub.op)
        assertEquals(10L, (sub.getArg(0) as LiteralOperand).value) // literal first
        assertEquals(1, (sub.getArg(1) as RegisterOperand).regNum) // register second
    }

    @Test
    fun iputMapsObjectFirstAndValueLast() {
        // Dalvik `iput vA, vB, field`: vA = value stored, vB = object (instance).
        // Guard against the decoder swapping operands: the FieldInstruction contract is
        // [object, value] (value LAST) — codegen emits `getArg(0).field = getArg(last)`.
        // reg 0 = value (vA), reg 1 = object (vB).
        val fld = FakeFieldRef("Lcom/example/Node;", "next", "Lcom/example/Node;")
        val code = decode(
            2,
            Insn(
                Opcode.IPUT, offset = 0, registers = intArrayOf(0, 1),
                indexType = IndexType.FIELD_REF, fieldRef = fld,
            ),
            Insn(Opcode.RETURN_VOID, offset = 1),
        )
        val put = code.instructions[0].insn as FieldInstruction
        assertEquals(IrOpcode.INSTANCE_PUT, put.opcode)
        assertEquals(2, put.argCount)
        // arg 0 is the OBJECT (vB = reg 1), carrying the declaring type.
        val obj = put.getArg(0) as RegisterOperand
        assertEquals(1, obj.regNum)
        assertEquals(IrType.objectType("com.example.Node"), obj.type)
        // last arg is the VALUE (vA = reg 0), carrying the field type.
        val value = put.getArg(put.argCount - 1) as RegisterOperand
        assertEquals(0, value.regNum)
        assertEquals(IrType.objectType("com.example.Node"), value.type)
    }

    @Test
    fun igetMapsDestAndObject() {
        // Sibling of iput: `iget vA, vB, field` puts the value into vA (result) reading object vB.
        val fld = FakeFieldRef("Lcom/example/Node;", "count", "I")
        val code = decode(
            2,
            Insn(
                Opcode.IGET, offset = 0, registers = intArrayOf(0, 1),
                indexType = IndexType.FIELD_REF, fieldRef = fld,
            ),
            Insn(Opcode.RETURN_VOID, offset = 1),
        )
        val get = code.instructions[0].insn as FieldInstruction
        assertEquals(IrOpcode.INSTANCE_GET, get.opcode)
        // result (dest) is vA = reg 0, of field type.
        val dest = get.result as RegisterOperand
        assertEquals(0, dest.regNum)
        assertEquals(IrType.INT, dest.type)
        // only arg is the object vB = reg 1.
        assertEquals(1, get.argCount)
        assertEquals(1, (get.getArg(0) as RegisterOperand).regNum)
    }

    @Test
    fun fillArrayDataBuildsFillArrayInstructionWithElements() {
        // fill-array-data at offset 0 points (target) at its payload table at offset 5.
        val payload = FillArrayDataPayload(size = 2, elementSize = 8, data = longArrayOf(1, 2))
        val code = decode(
            1,
            Insn(Opcode.FILL_ARRAY_DATA, offset = 0, registers = intArrayOf(0), target = 5),
            Insn(Opcode.RETURN_VOID, offset = 1),
            Insn(Opcode.FILL_ARRAY_DATA_PAYLOAD, offset = 5, payload = payload),
        )
        // The payload pseudo-op is dropped; the fill-array becomes a canonical FillArrayInstruction.
        val fill = code.instructions.map { it.insn }.filterIsInstance<FillArrayInstruction>().single()
        assertEquals(IrOpcode.FILL_ARRAY, fill.opcode)
        assertEquals(8, fill.elementWidth)
        assertEquals(2, fill.size)
        assertTrue(fill.elements.contentEquals(longArrayOf(1, 2)))
        assertEquals(0, (fill.array as RegisterOperand).regNum) // the array register being filled
        assertEquals(0, fill.offset) // keeps the original fill-array-data offset
    }

    @Test
    fun fillArrayDataSignExtendsNarrowElements() {
        // 1-byte elements must be widened SIGNED: 0xFF → -1, matching jadx's getLiteralArgs.
        val payload = FillArrayDataPayload(size = 3, elementSize = 1, data = byteArrayOf(-1, 0, 127))
        val code = decode(
            1,
            Insn(Opcode.FILL_ARRAY_DATA, offset = 0, registers = intArrayOf(0), target = 5),
            Insn(Opcode.RETURN_VOID, offset = 1),
            Insn(Opcode.FILL_ARRAY_DATA_PAYLOAD, offset = 5, payload = payload),
        )
        val fill = code.instructions.map { it.insn }.filterIsInstance<FillArrayInstruction>().single()
        assertEquals(1, fill.elementWidth)
        assertTrue(fill.elements.contentEquals(longArrayOf(-1, 0, 127)))
    }

    @Test
    fun fillArrayDataWithoutPayloadStaysBarePlaceholder() {
        // No matching payload table ⇒ leave the bare FILL_ARRAY placeholder so codegen bails honestly.
        val code = decode(
            1,
            Insn(Opcode.FILL_ARRAY_DATA, offset = 0, registers = intArrayOf(0), target = 5),
            Insn(Opcode.RETURN_VOID, offset = 1),
        )
        val insn = code.instructions.first { it.insn.opcode == IrOpcode.FILL_ARRAY }.insn
        assertTrue(insn !is FillArrayInstruction, "no payload ⇒ must not fabricate a FillArrayInstruction")
    }

    @Test
    fun unsupportedOpcodeIsPreservedAndFlaggedNotDropped() {
        val code = decode(
            1,
            Insn(Opcode.UNKNOWN, offset = 0),
            Insn(Opcode.RETURN_VOID, offset = 1),
        )
        // The unsupported instruction is preserved as a placeholder (no silent code loss) and reported.
        assertEquals(2, code.instructions.size)
        assertTrue(code.errors.isNotEmpty(), "an undecodable opcode must be reported")
    }

    @Test
    fun constMethodHandlePreservesDestinationRegister() {
        val code = decode(
            1,
            Insn(Opcode.CONST_METHOD_HANDLE, offset = 0, registers = intArrayOf(0)),
            Insn(Opcode.RETURN_VOID, offset = 1),
        )
        assertEquals(2, code.instructions.size)
        assertTrue(code.errors.isNotEmpty())
        // The destination register is preserved as a definition so it never becomes undefined.
        val placeholder = code.instructions[0].insn
        assertEquals(0, (placeholder.result as RegisterOperand).regNum)
        assertEquals(IrType.objectType("java.lang.invoke.MethodHandle"), placeholder.result!!.type)
    }
}
