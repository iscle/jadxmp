package com.jadxmp.pipeline.decode

import com.jadxmp.input.EncodedValue
import com.jadxmp.input.EncodedValueType
import com.jadxmp.input.MethodHandleType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.InvokeCustomInstruction
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.support.FakeCallSite
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodHandle
import com.jadxmp.pipeline.support.FakeMethodProto
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.Insn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FusedResultDecodeTest {
    @Test fun fusedInvokeKeepsItsDeclaredResultTypeAndWideArgumentWords() {
        for ((descriptor, type) in listOf("I" to IrType.INT, "J" to IrType.LONG,
            "D" to IrType.DOUBLE, "F" to IrType.FLOAT, "Ljava/lang/String;" to IrType.STRING)) {
            val code = decode(8, Insn(Opcode.INVOKE_STATIC, 0, registers = intArrayOf(0, 1, 2),
                methodRef = FakeMethodRef("LExample;", "call", descriptor, listOf("J", "I")), resultRegister = 6))
            val invoke = code.instructions.single().insn
            assertEquals(6, invoke.result?.regNum)
            assertEquals(type, invoke.result?.type)
            assertEquals(listOf(IrType.LONG, IrType.INT), invoke.args.map { it.type })
            assertTrue(code.errors.isEmpty())
        }
    }

    @Test fun fusedCustomCallAndFilledArrayRetainTheirResults() {
        val bootstrap = FakeMethodHandle(MethodHandleType.INVOKE_STATIC,
            methodRef = FakeMethodRef("LBootstrap;", "link", "Ljava/lang/invoke/CallSite;", emptyList()))
        val callSite = FakeCallSite(listOf(
            EncodedValue(EncodedValueType.METHOD_HANDLE, bootstrap),
            EncodedValue(EncodedValueType.STRING, "call"),
            EncodedValue(EncodedValueType.METHOD_TYPE, FakeMethodProto("J", emptyList())),
        ))
        val custom = decode(2, Insn(Opcode.INVOKE_CUSTOM, 0, callSite = callSite, resultRegister = 0))
        assertIs<InvokeCustomInstruction>(custom.instructions.single().insn)
        assertEquals(IrType.LONG, custom.instructions.single().insn.result?.type)
        val array = decode(2, Insn(Opcode.FILLED_NEW_ARRAY, 0, registers = intArrayOf(0), typeValue = "[I", resultRegister = 1))
        assertEquals(1, array.instructions.single().insn.result?.regNum)
        assertEquals(IrType.array(IrType.INT), array.instructions.single().insn.result?.type)
    }

    @Test fun discardedInvokeAndDexMoveResultRemainSupported() {
        val call = Insn(Opcode.INVOKE_STATIC, 0, methodRef = FakeMethodRef("LExample;", "call", "I", emptyList()))
        assertNull(decode(1, call).instructions.single().insn.result)
        val paired = decode(1, call, Insn(Opcode.MOVE_RESULT, 1, registers = intArrayOf(0)))
        assertEquals(0, paired.instructions.single().insn.result?.regNum)
        assertEquals(IrType.INT, paired.instructions.single().insn.result?.type)
    }

    @Test fun rejectsImpossibleFusedResultsWithoutAttachingAFalseDefinition() {
        for ((descriptor, register) in listOf("V" to 0, "I" to 2, "J" to 1, "I" to -2, "I" to Int.MAX_VALUE)) {
            val code = decode(2, Insn(Opcode.INVOKE_STATIC, 0,
                methodRef = FakeMethodRef("LExample;", "call", descriptor, emptyList()), resultRegister = register))
            assertNull(code.instructions.single().insn.result)
            assertTrue(code.errors.isNotEmpty(), "$descriptor -> $register")
        }
    }

    private fun decode(registerCount: Int, vararg instructions: Insn) =
        MethodDecoder().decode(FakeCodeReader(registerCount, instructions.toList()))
}
