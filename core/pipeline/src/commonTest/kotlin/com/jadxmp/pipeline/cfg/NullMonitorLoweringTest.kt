package com.jadxmp.pipeline.cfg

import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NullMonitorLoweringTest {
    @Test
    fun knownNullMonitorKeepsPrefixAndTerminatesNormalFlow() {
        val method = TestPipeline.buildMethod(FakeCodeReader(1, listOf(
            Insn(Opcode.INVOKE_STATIC, 0, methodRef = FakeMethodRef("Lexample/Hooks;", "observe", "V", emptyList())),
            Insn(Opcode.CONST, 1, intArrayOf(0), literal = 0),
            Insn(Opcode.MONITOR_ENTER, 2, intArrayOf(0)),
            Insn(Opcode.MONITOR_EXIT, 3, intArrayOf(0)),
            Insn(Opcode.RETURN_VOID, 4),
        )))
        TestPipeline.cfg(method)
        NullMonitorLowering(method).run()
        val block = TestPipeline.blockAt(method, 0)
        assertEquals(listOf(IrOpcode.INVOKE, IrOpcode.CONST, IrOpcode.THROW), block.instructions.map { it.opcode })
        assertEquals(listOf(method.exitBlock), block.successors)
    }

    @Test
    fun unknownOrOverwrittenMonitorIsNeverFolded() {
        for (overwrite in listOf(false, true)) {
            val insns = mutableListOf<Insn>()
            if (overwrite) {
                insns += Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0)
                insns += Insn(Opcode.CONST_STRING, 1, intArrayOf(0), stringValue = "lock")
            }
            insns += Insn(Opcode.MONITOR_ENTER, 2, intArrayOf(0))
            insns += Insn(Opcode.MONITOR_EXIT, 3, intArrayOf(0))
            insns += Insn(Opcode.RETURN_VOID, 4)
            val method = TestPipeline.buildMethod(FakeCodeReader(1, insns))
            TestPipeline.cfg(method)
            NullMonitorLowering(method).run()
            assertTrue(method.blocks.flatMap { it.instructions }.any { it.opcode == IrOpcode.MONITOR_ENTER })
        }
    }
}
