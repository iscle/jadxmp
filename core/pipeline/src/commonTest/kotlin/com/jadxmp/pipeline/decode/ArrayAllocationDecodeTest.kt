package com.jadxmp.pipeline.decode

import com.jadxmp.input.ArrayAllocationPayload
import com.jadxmp.input.Opcode
import com.jadxmp.input.InlineSwitchPayload
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.Insn
import kotlin.test.*

class ArrayAllocationDecodeTest {
    @Test fun inlineWholeArrayTypeRequiresNoConstantPoolAndSeparatesDefinitionFromSize() {
        val decoded = decode("[[I")
        assertTrue(decoded.errors.isEmpty())
        val instruction = decoded.instructions.single().insn
        assertEquals(IrOpcode.NEW_ARRAY, instruction.opcode)
        assertEquals(IrType.array(IrType.INT, 2), instruction.result!!.type)
        assertEquals(IrType.INT, instruction.getArg(0).type)
        assertNotSame(instruction.result!!, instruction.getArg(0) as RegisterOperand)
    }

    @Test fun malformedInlineDescriptorsAreRefusedInsteadOfPartiallyParsed() {
        for (descriptor in listOf("I", "[V", "[", "[Igarbage", "[L;", "[Ljava//lang/String;", "[Lbad.name;", "[".repeat(256) + "I", "[L" + "a".repeat(65536) + ";")) {
            assertFails { decode(descriptor) }
        }
    }

    @Test fun malformedInlineShapeIsNotTreatedAsLegacyIndexedInput() {
        for (registers in listOf(intArrayOf(0), intArrayOf(0, 0, 0))) assertFails {
            MethodDecoder().decode(FakeCodeReader(1, listOf(Insn(Opcode.NEW_ARRAY, 0,
                registers = registers, payload = ArrayAllocationPayload("[I")))))
        }
        assertFails {
            MethodDecoder().decode(FakeCodeReader(1, listOf(Insn(Opcode.NEW_ARRAY, 0,
                registers = intArrayOf(0, 0), typeValue = "[I", payload = InlineSwitchPayload(intArrayOf(), intArrayOf(), 0)))))
        }
    }

    @Test fun indexedLegacyArrayTypeStillDecodes() {
        val decoded = MethodDecoder().decode(FakeCodeReader(2, listOf(Insn(Opcode.NEW_ARRAY, 0,
            registers = intArrayOf(0, 1), typeValue = "[J"))))
        assertEquals(IrType.array(IrType.LONG), decoded.instructions.single().insn.result!!.type)
    }

    private fun decode(descriptor: String) = MethodDecoder().decode(FakeCodeReader(1, listOf(Insn(
        Opcode.NEW_ARRAY, 0, registers = intArrayOf(0, 0), payload = ArrayAllocationPayload(descriptor)))))
}
