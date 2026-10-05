package com.jadxmp.pipeline.structure

import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertTrue

class ExpressionEffectOrderTest {
    private val effect = FakeMethodRef("Lhooks/Effects;", "effect", "V", emptyList())
    private val value = FakeMethodRef("Lhooks/Effects;", "value", "I", emptyList())
    private val consume = FakeMethodRef("Lhooks/Effects;", "consume", "V", listOf("I", "I"))

    @Test
    fun arrayLengthDoesNotMovePastObservableCall() {
        val method = TestPipeline.buildMethod(FakeCodeReader(2, listOf(
            Insn(Opcode.ARRAY_LENGTH, 0, intArrayOf(0, 1)),
            Insn(Opcode.INVOKE_STATIC, 1, methodRef = effect, indexType = IndexType.METHOD_REF),
            Insn(Opcode.RETURN, 2, intArrayOf(0)),
        )), returnType = IrType.INT, argTypes = listOf(IrType.array(IrType.INT)))
        shape(method)
        assertTrue(method.blocks.flatMap { it.instructions }.any { it.opcode == IrOpcode.ARRAY_LENGTH })
    }

    @Test
    fun divisionAndRemainderDoNotMovePastObservableCall() {
        for (opcode in listOf(Opcode.DIV_INT, Opcode.REM_INT)) {
            val method = TestPipeline.buildMethod(FakeCodeReader(3, listOf(
                Insn(opcode, 0, intArrayOf(0, 1, 2)),
                Insn(Opcode.INVOKE_STATIC, 1, methodRef = effect, indexType = IndexType.METHOD_REF),
                Insn(Opcode.RETURN, 2, intArrayOf(0)),
            )), returnType = IrType.INT, argTypes = listOf(IrType.INT, IrType.INT))
            shape(method)
            assertTrue(method.blocks.flatMap { it.instructions }.any { it.opcode == IrOpcode.ARITH }, opcode.name)
        }
    }

    @Test
    fun resolutionLoadsDoNotMovePastObservableCall() {
        for (opcode in listOf(Opcode.CONST_STRING, Opcode.CONST_CLASS)) {
            val method = TestPipeline.buildMethod(FakeCodeReader(1, listOf(
                Insn(opcode, 0, intArrayOf(0), stringValue = "literal", typeValue = "Lmissing/Type;",
                    indexType = if (opcode == Opcode.CONST_STRING) IndexType.STRING_REF else IndexType.TYPE_REF),
                Insn(Opcode.INVOKE_STATIC, 1, methodRef = effect, indexType = IndexType.METHOD_REF),
                Insn(Opcode.RETURN, 2, intArrayOf(0)),
            )), returnType = IrType.OBJECT)
            shape(method)
            assertTrue(method.blocks.flatMap { it.instructions }.any {
                it.opcode == IrOpcode.CONST_STRING || it.opcode == IrOpcode.CONST_CLASS
            }, opcode.name)
        }
    }

    @Test
    fun foldingReversedCallArgumentsDoesNotReverseTheirEvaluation() {
        val method = TestPipeline.buildMethod(FakeCodeReader(2, listOf(
            Insn(Opcode.INVOKE_STATIC, 0, methodRef = value.copy(name = "first"), indexType = IndexType.METHOD_REF, resultRegister = 0),
            Insn(Opcode.INVOKE_STATIC, 1, methodRef = value.copy(name = "second"), indexType = IndexType.METHOD_REF, resultRegister = 1),
            Insn(Opcode.INVOKE_STATIC, 2, intArrayOf(1, 0), methodRef = consume, indexType = IndexType.METHOD_REF),
            Insn(Opcode.RETURN_VOID, 3),
        )))
        shape(method)
        assertTrue(method.blocks.flatMap { it.instructions }.filterIsInstance<InvokeInstruction>()
            .any { it.methodRef.name == "first" }, "first() must execute before the second() expression in argument zero")
    }

    @Test
    fun arrayStoreUsesSourceOperandOrderWhenFoldingEffects() {
        val method = TestPipeline.buildMethod(FakeCodeReader(3, listOf(
            Insn(Opcode.INVOKE_STATIC, 0, methodRef = value.copy(name = "first"), indexType = IndexType.METHOD_REF, resultRegister = 0),
            Insn(Opcode.INVOKE_STATIC, 1, methodRef = value.copy(name = "array", returnType = "[I"), indexType = IndexType.METHOD_REF, resultRegister = 1),
            Insn(Opcode.INVOKE_STATIC, 2, methodRef = value.copy(name = "index"), indexType = IndexType.METHOD_REF, resultRegister = 2),
            Insn(Opcode.APUT, 3, intArrayOf(0, 1, 2)),
            Insn(Opcode.RETURN_VOID, 4),
        )))
        shape(method)
        assertTrue(method.blocks.flatMap { it.instructions }.filterIsInstance<InvokeInstruction>()
            .any { it.methodRef.name == "first" }, "array[index] = value evaluates the value last in source")
    }

    private fun shape(method: com.jadxmp.ir.node.IrMethod) {
        TestPipeline.full(method)
        OutOfSsa(method).run()
        ExpressionShaping(method).run()
    }
}
