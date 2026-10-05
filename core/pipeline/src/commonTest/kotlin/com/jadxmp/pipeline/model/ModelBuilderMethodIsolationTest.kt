package com.jadxmp.pipeline.model

import com.jadxmp.input.CodeReader
import com.jadxmp.input.MethodData
import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.attr.IrAttrs
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.pass.CancellationSignal
import com.jadxmp.pipeline.support.FakeClassData
import com.jadxmp.pipeline.support.FakeCodeLoader
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodData
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.Insn
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ModelBuilderMethodIsolationTest {
    @Test fun failedBodyProviderPreservesMethodAndHealthySiblings() {
        val failure = IllegalArgumentException("unsupported bytecode in broken")
        val broken = object : MethodData by declaration("broken") {
            override val codeReader: CodeReader get() = throw failure
        }
        assertIsolated(broken, failure)
    }

    @Test fun failedRegisterLayoutDoesNotPublishHalfInitializedBody() {
        val failure = IllegalArgumentException("invalid frame size")
        val reader = object : CodeReader by validReader {
            override val registerCount: Int get() = throw failure
        }
        assertIsolated(declaration("broken", reader), failure)
    }

    @Test fun cancellationDuringBodyLoadPropagatesUnchanged() {
        val cancellation = CancellationSignal("stop loading")
        val broken = object : MethodData by declaration("broken") {
            override val codeReader: CodeReader get() = throw cancellation
        }
        assertSame(cancellation, assertFailsWith<CancellationSignal> { build(broken) })
    }

    @Test fun coroutineCancellationDuringBodyLoadAlsoPropagates() {
        val cancellation = CancellationException("cancelled plugin")
        val broken = object : MethodData by declaration("broken") {
            override val codeReader: CodeReader get() = throw cancellation
        }
        assertSame(cancellation, assertFailsWith<CancellationException> { build(broken) })
    }

    private fun assertIsolated(broken: MethodData, failure: Exception) {
        val root = build(broken)
        assertEquals(listOf("Example", "Sibling"), root.classes.map { it.fullName })
        val methods = root.classes.first().methods
        assertEquals(listOf("before", "broken", "after"), methods.map { it.name })
        val failed = methods[1]
        assertTrue(failed.contains(AttrFlag.HAS_ERROR))
        assertSame(failure, failed[IrAttrs.ERROR]?.cause)
        assertTrue(failed[IrAttrs.ERROR]?.message?.contains(failure.message.orEmpty()) == true)
        assertNull(failed[PipelineAttrs.CODE_READER])
        assertNull(failed[PipelineAttrs.REGISTER_COUNT])
        for (method in listOf(methods[0], methods[2], root.classes.last().methods.single())) {
            assertFalse(method.contains(AttrFlag.HAS_ERROR))
            assertSame(validReader, method[PipelineAttrs.CODE_READER])
            assertEquals(0, method[PipelineAttrs.REGISTER_COUNT])
        }
    }

    private fun build(broken: MethodData) = ModelBuilder.build(FakeCodeLoader(listOf(
        FakeClassData("LExample;", methods = listOf(declaration("before", validReader), broken, declaration("after", validReader))),
        FakeClassData("LSibling;", methods = listOf(declaration("healthy", validReader))),
    )))

    private fun declaration(name: String, reader: CodeReader? = null) =
        FakeMethodData(FakeMethodRef("LExample;", name, "V", emptyList()), codeReader = reader)

    private val validReader = FakeCodeReader(0, listOf(Insn(Opcode.RETURN_VOID, 0)))
}
