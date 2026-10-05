package com.jadxmp.pipeline.decode

import com.jadxmp.input.InlineSwitchPayload
import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.SwitchInstruction
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InlineSwitchDecodeTest {
    @Test fun explicitDefaultDoesNotCreateAFalseFallthroughEdge() {
        val reader = reader(InlineSwitchPayload(intArrayOf(-2, 8), intArrayOf(20, 20), 30))
        val method = TestPipeline.buildMethod(reader)
        val decoded = TestPipeline.cfg(method)
        val switch = assertIs<SwitchInstruction>(decoded.instructions.first().insn)
        assertEquals(listOf(-2, 8), switch.keys.toList())
        assertEquals(listOf(20, 20), switch.caseTargets.toList())
        assertEquals(30, switch.defaultTarget)
        assertFalse(decoded.instructions.first().fallsThrough)
        assertEquals(setOf(TestPipeline.blockAt(method, 20), TestPipeline.blockAt(method, 30)),
            TestPipeline.blockAt(method, 0).successors.toSet())
        assertTrue(decoded.errors.isEmpty())
    }

    @Test fun defaultOnlyAndBackwardDestinationsAreExplicit() {
        val code = MethodDecoder().decode(reader(InlineSwitchPayload(intArrayOf(), intArrayOf(), 0)))
        assertEquals(listOf(0), code.instructions.first().targets.toList())
        assertFalse(code.instructions.first().fallsThrough)
    }

    @Test fun decoderOwnsCopiesAfterInputCursorMovesOn() {
        val keys = intArrayOf(4)
        val targets = intArrayOf(20)
        val code = MethodDecoder().decode(reader(InlineSwitchPayload(keys, targets, 30)))
        keys[0] = 99; targets[0] = 10
        val switch = assertIs<SwitchInstruction>(code.instructions.first().insn)
        assertEquals(listOf(4), switch.keys.toList())
        assertEquals(listOf(20), switch.caseTargets.toList())
    }

    @Test fun malformedInlineTablesProduceVisibleDiagnostics() {
        for (payload in listOf(null,
            InlineSwitchPayload(intArrayOf(1), intArrayOf(), 30),
            InlineSwitchPayload(intArrayOf(1, 1), intArrayOf(20, 30), 30),
            InlineSwitchPayload(intArrayOf(1), intArrayOf(-1), 30),
            InlineSwitchPayload(intArrayOf(), intArrayOf(), -1),
            InlineSwitchPayload(intArrayOf(1), intArrayOf(999), 30),
            InlineSwitchPayload(intArrayOf(), intArrayOf(), 999))) {
            assertTrue(MethodDecoder().decode(reader(payload)).errors.isNotEmpty())
        }
    }

    private fun reader(payload: InlineSwitchPayload?) = FakeCodeReader(1, listOf(
        Insn(Opcode.SWITCH, 0, intArrayOf(0), payload = payload),
        Insn(Opcode.RETURN_VOID, 10), // deliberately NOT the default
        Insn(Opcode.RETURN_VOID, 20),
        Insn(Opcode.RETURN_VOID, 30),
    ))
}
