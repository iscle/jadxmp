package com.jadxmp.pipeline.types

import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.attr.IrAttrs
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.support.FakeCatchHandler
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.FakeTryBlock
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReturnTypeValidationTest {
    @Test
    fun referenceStateOnExceptionalBooleanReturnIsDiagnosed() {
        val reader = FakeCodeReader(2, listOf(
            Insn(Opcode.CONST_STRING, 0, intArrayOf(0), stringValue = "before"),
            Insn(Opcode.INVOKE_STATIC, 1, methodRef = FakeMethodRef("Lexample/Hooks;", "fail", "V", emptyList())),
            Insn(Opcode.CONST, 2, intArrayOf(0), literal = 0),
            Insn(Opcode.INVOKE_STATIC, 3, methodRef = FakeMethodRef("Lexample/Hooks;", "fail", "V", emptyList())),
            Insn(Opcode.RETURN, 4, intArrayOf(0)),
            Insn(Opcode.MOVE_EXCEPTION, 5, intArrayOf(1)),
            Insn(Opcode.RETURN, 6, intArrayOf(0)),
        ), tries = listOf(FakeTryBlock(1, 3, FakeCatchHandler(emptyList(), emptyList(), 5))))
        val method = TestPipeline.buildMethod(reader, returnType = IrType.BOOLEAN)
        TestPipeline.full(method)
        assertTrue(method.contains(AttrFlag.HAS_ERROR))
        assertTrue(method[IrAttrs.ERROR]!!.message.contains("reference value reaches primitive return"))
    }

    @Test
    fun polymorphicZeroAndNullableReferenceReturnsRemainValid() {
        for (returnType in listOf(IrType.BOOLEAN, IrType.INT, IrType.OBJECT)) {
            val method = TestPipeline.buildMethod(FakeCodeReader(1, listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
                Insn(Opcode.RETURN, 1, intArrayOf(0)),
            )), returnType = returnType)
            TestPipeline.full(method)
            assertFalse(method.contains(AttrFlag.HAS_ERROR), "zero is legal for $returnType")
        }
    }
}
