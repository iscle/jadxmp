package com.jadxmp.api

import com.jadxmp.api.plugin.PassPlugin
import com.jadxmp.api.plugin.PluginRegistry
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.pass.CancellationSignal
import com.jadxmp.pipeline.pass.ClassPass
import com.jadxmp.pipeline.pass.PassContext
import com.jadxmp.pipeline.pass.RootPass
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertSame

class RootPassLifecycleTest {
    @Test fun rootPassesRunOncePerLoadBeforeClassPassesAndAliasGeneration() {
        val events = mutableListOf<String>()
        val plugin = object : PassPlugin {
            override val id = "lifecycle"
            override fun rootPasses() = listOf(pass("root") { root ->
                events += "root"
                root.addClass(IrClass(root, "a", 1, IrType.OBJECT))
            })
            override fun classPasses() = listOf(object : ClassPass {
                override val name = "class"
                override fun run(cls: IrClass, context: PassContext) { events += "class" }
            })
        }
        val engine = Decompiler(DecompilerArgs(deobfuscation = true, registry = PluginRegistry(emptyList(), listOf(plugin))))
        repeat(2) {
            assertEquals(1, engine.load("fixture", byteArrayOf()))
            val result = engine.decompileAll()
            assertTrue(result.classes.single().fullName != "a", "Root-created class must be included in the alias snapshot")
            engine.decompileAll()
            engine.decompileClass("a", OutputFormat.KOTLIN)
        }
        assertEquals(listOf("root", "class", "root", "class"), events)
    }

    @Test fun rootPassFailureRemainsVisibleOnceAndLaterPassesContinue() {
        val engine = engine(listOf(pass("bad") { error("broken metadata") }, pass("good") { root ->
            root.addClass(IrClass(root, "Example", 1, IrType.OBJECT))
        }))
        assertEquals(1, engine.load("fixture", byteArrayOf()))
        assertTrue(engine.diagnostics.single().contains("broken metadata"))
        repeat(2) {
            val result = engine.decompileAll()
            assertEquals(1, result.errorCount)
            assertEquals(0, result.classes.single().metadata.errorCount)
        }
    }

    @Test fun emptyModelStillReportsRootFailure() {
        val engine = engine(listOf(pass("bad") { error("empty model failure") }))
        assertEquals(0, engine.load("fixture", byteArrayOf()))
        assertEquals(1, engine.decompileAll().errorCount)
        assertTrue(engine.diagnostics.single().contains("empty model failure"))
    }

    @Test fun bothCancellationTypesDiscardOldAndPartiallyPreparedModels() {
        for (failure in listOf(CancellationSignal(), CancellationException("cancelled"))) {
            var cancel = false
            val engine = engine(listOf(pass("prepare") { root ->
                root.addClass(IrClass(root, "Example", 1, IrType.OBJECT))
                if (cancel) throw failure
            }))
            assertEquals(1, engine.load("first", byteArrayOf()))
            cancel = true
            assertSame(failure, assertFailsWith<RuntimeException> { engine.load("cancelled", byteArrayOf()) })
            assertTrue(engine.classNames.isEmpty())
            assertTrue(engine.decompileAll().classes.isEmpty())
            cancel = false
            assertEquals(1, engine.load("fresh", byteArrayOf()))
            assertEquals(0, engine.decompileAll().errorCount)
        }
    }

    private fun pass(name: String, body: (IrRoot) -> Unit) = object : RootPass {
        override val name = name
        override fun run(root: IrRoot, context: PassContext) = body(root)
    }
    private fun engine(passes: List<RootPass>) = Decompiler(DecompilerArgs(registry = PluginRegistry(emptyList(), listOf(object : PassPlugin {
        override val id = "test-root-passes"
        override fun rootPasses() = passes
    }))))
}
