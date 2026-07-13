package com.jadxmp.pipeline.pass

import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.attr.IrAttrs
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.AnalysisPipeline
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.model.ModelBuilder
import com.jadxmp.pipeline.support.FakeClassData
import com.jadxmp.pipeline.support.FakeCodeLoader
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodData
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.Insn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PassFrameworkTest {

    private class NamedPass(
        override val name: String,
        override val runAfter: List<String> = emptyList(),
        override val runBefore: List<String> = emptyList(),
        val onRun: (IrMethod) -> Unit = {},
    ) : MethodPass {
        override fun run(method: IrMethod, context: PassContext) = onRun(method)
    }

    @Test
    fun topoSortHonoursOrderingHints() {
        val a = NamedPass("A", runAfter = listOf("B"))
        val b = NamedPass("B")
        val c = NamedPass("C", runBefore = listOf("B"))
        val sorted = PassRunner.topoSort(listOf(a, b, c)).map { it.name }
        // C before B, B before A.
        assertTrue(sorted.indexOf("C") < sorted.indexOf("B"))
        assertTrue(sorted.indexOf("B") < sorted.indexOf("A"))
    }

    @Test
    fun cycleIsRejected() {
        val a = NamedPass("A", runAfter = listOf("B"))
        val b = NamedPass("B", runAfter = listOf("A"))
        assertFailsWith<IllegalStateException> { PassRunner.topoSort(listOf(a, b)) }
    }

    @Test
    fun faultIsIsolatedToTheFailingMethod() {
        val root = IrRoot()
        val cls = IrClass(root, "com.example.C", 0, superType = IrType.OBJECT)
        root.addClass(cls)
        val good = IrMethod(cls, "good", IrType.VOID, emptyList(), IrMethod.ACC_STATIC)
        val bad = IrMethod(cls, "bad", IrType.VOID, emptyList(), IrMethod.ACC_STATIC)
        cls.methods.add(good); cls.methods.add(bad)

        var goodRan = false
        val pass = NamedPass("boom") { m ->
            if (m.name == "bad") throw IllegalStateException("kaboom") else goodRan = true
        }
        PassRunner(methodPasses = listOf(pass)).run(root)

        assertTrue(goodRan, "a sibling method's failure must not stop the good one")
        assertTrue(bad.contains(AttrFlag.HAS_ERROR))
        assertEquals("kaboom", bad[IrAttrs.ERROR]?.message)
        assertFalse(good.contains(AttrFlag.HAS_ERROR))
    }

    @Test
    fun cancellationIsNotSwallowed() {
        val root = IrRoot()
        val cls = IrClass(root, "com.example.C", 0, superType = IrType.OBJECT)
        root.addClass(cls)
        cls.methods.add(IrMethod(cls, "m", IrType.VOID, emptyList(), IrMethod.ACC_STATIC))
        val cancel = CancellationCheck { throw CancellationSignal() }
        val pass = NamedPass("p") { }
        assertFailsWith<CancellationSignal> {
            PassRunner(methodPasses = listOf(pass)).run(root, PassContext(root, cancel))
        }
    }

    @Test
    fun unsupportedOpcodeFlagsMethodWithError() {
        // A method whose body contains const-method-handle must be flagged, not silently accepted.
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.CONST_METHOD_HANDLE, 0, intArrayOf(0)),
                Insn(Opcode.RETURN_VOID, 1),
            ),
        )
        val ref = FakeMethodRef("Lcom/example/C;", "m", "V", emptyList())
        val loader = FakeCodeLoader(
            listOf(
                FakeClassData(
                    type = "Lcom/example/C;",
                    methods = listOf(FakeMethodData(ref, accessFlags = IrMethod.ACC_STATIC, codeReader = reader)),
                ),
            ),
        )
        val model = ModelBuilder.build(loader)
        AnalysisPipeline.runner().run(model)
        val method = model.findClass("com.example.C")!!.methods.first()
        assertTrue(method.contains(AttrFlag.HAS_ERROR), "unsupported opcode must flag the method")
        assertTrue(method[IrAttrs.ERROR]?.message?.contains("CONST_METHOD_HANDLE") == true)
    }

    @Test
    fun endToEndPipelineProducesTypedSsaWithoutErrors() {
        // static int add(): const v0=2; const v1=3; v0=v0+v1; return v0
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 2),
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = 3),
                Insn(Opcode.ADD_INT, 2, intArrayOf(0, 0, 1)),
                Insn(Opcode.RETURN, 3, intArrayOf(0)),
            ),
        )
        val ref = FakeMethodRef("Lcom/example/Calc;", "add", "I", emptyList())
        val loader = FakeCodeLoader(
            listOf(
                FakeClassData(
                    type = "Lcom/example/Calc;",
                    methods = listOf(FakeMethodData(ref, accessFlags = IrMethod.ACC_STATIC, codeReader = reader)),
                ),
            ),
        )
        val rootModel = ModelBuilder.build(loader)
        AnalysisPipeline.runner().run(rootModel)

        val method = rootModel.findClass("com.example.Calc")!!.methods.first()
        assertFalse(method.contains(AttrFlag.HAS_ERROR))
        assertTrue(method.blocks.isNotEmpty())
        assertTrue(method.ssaValues.isNotEmpty())
        // parameters attribute is present (empty here) and the add result typed int
        assertEquals(emptyList(), method[PipelineAttrs.PARAMETERS])
        val addResult = method.ssaValues.first { it.assign.parent?.opcode == com.jadxmp.ir.insn.IrOpcode.ARITH }
        assertEquals(IrType.INT, addResult.type)
    }
}
