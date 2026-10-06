package com.jadxmp.api

import com.jadxmp.api.plugin.InputPlugin
import com.jadxmp.api.plugin.PassPlugin
import com.jadxmp.api.plugin.PluginRegistry
import com.jadxmp.input.AnnotationData
import com.jadxmp.input.ClassData
import com.jadxmp.input.CodeLoader
import com.jadxmp.input.FieldData
import com.jadxmp.input.ListCodeLoader
import com.jadxmp.input.MethodData
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.pipeline.pass.CancellationSignal
import com.jadxmp.pipeline.pass.PassContext
import com.jadxmp.pipeline.pass.RootPass
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InputCancellationTest {
    @Test fun inputCancellationStopsLoadingInsteadOfBecomingAParseDiagnostic() {
        for (failure in cancellations()) {
            var cancelled = false
            var fallbackCalls = 0
            var rootCalls = 0
            val engine = engine(
                input = { if (cancelled) throw failure else FixtureClass() },
                fallback = { fallbackCalls++ },
                root = { rootCalls++ },
            )
            loadHealthy(engine)
            cancelled = true
            assertSame(failure, assertFailsWith<RuntimeException> { engine.load("cancelled", resourceBytes()) })
            assertEquals(0, fallbackCalls)
            assertEquals(1, rootCalls)
            assertEmpty(engine)
            cancelled = false
            loadHealthy(engine)
        }
    }

    @Test fun cancellationWhileProbingALoaderDoesNotTryFallbackPlugins() {
        for (failure in cancellations()) {
            var cancelled = false
            var fallbackCalls = 0
            val first = object : InputPlugin {
                override val id = "lazy loader"
                override fun tryLoad(name: String, bytes: ByteArray) = object : CodeLoader {
                    override val classes = listOf(FixtureClass())
                    override val isEmpty: Boolean get() {
                        if (cancelled) throw failure
                        return false
                    }
                }
            }
            val fallback = object : InputPlugin {
                override val id = "fallback"
                override fun tryLoad(name: String, bytes: ByteArray): CodeLoader? { fallbackCalls++; return null }
            }
            val engine = Decompiler(DecompilerArgs(registry = PluginRegistry(listOf(first, fallback))))
            loadHealthy(engine)
            cancelled = true
            assertSame(failure, assertFailsWith<RuntimeException> { engine.load("cancelled", resourceBytes()) })
            assertEquals(0, fallbackCalls)
            assertEmpty(engine)
            cancelled = false
            loadHealthy(engine)
        }
    }

    @Test fun oneShotDescriptorCancellationCannotBeSkippedByTheInputIndex() {
        for (failure in cancellations()) {
            var cancelled = false
            var rootCalls = 0
            val engine = engine(input = {
                if (!cancelled) FixtureClass() else object : FixtureClass() {
                    var first = true
                    override val type: String get() {
                        if (first) { first = false; throw failure }
                        return super.type
                    }
                }
            }, root = { rootCalls++ })
            loadHealthy(engine)
            cancelled = true
            assertSame(failure, assertFailsWith<RuntimeException> { engine.load("cancelled", resourceBytes()) })
            assertEquals(1, rootCalls)
            assertEmpty(engine)
            cancelled = false
            loadHealthy(engine)
        }
    }

    @Test fun cancellationAfterIndexingDoesNotPublishPartialResourcesOrSmali() {
        for (failure in cancellations()) for (cancelInRoot in listOf(false, true)) {
            var cancelled = false
            val engine = engine(input = {
                object : FixtureClass() {
                    override val interfaces: List<String> get() {
                        if (cancelled && !cancelInRoot) throw failure
                        return emptyList()
                    }
                }
            }, root = { if (cancelled && cancelInRoot) throw failure })
            loadHealthy(engine)
            cancelled = true
            assertSame(failure, assertFailsWith<RuntimeException> { engine.load("cancelled", resourceBytes()) })
            assertEmpty(engine)
            cancelled = false
            loadHealthy(engine)
        }
    }

    @Test fun ordinaryInputFailureStillProducesADiagnostic() {
        val engine = engine(input = { error("bad input envelope") })
        assertEquals(0, engine.load("bad", byteArrayOf()))
        assertTrue(engine.diagnostics.single().contains("bad input envelope"))
        assertTrue(engine.classNames.isEmpty())
    }

    private fun loadHealthy(engine: Decompiler) {
        assertEquals(1, engine.load("healthy", resourceBytes()))
        assertEquals(listOf("Example"), engine.classNames)
        assertEquals("fixture smali", engine.smali("Example"))
        assertNotNull(engine.resources)
        assertEquals(1, engine.decompileAll().classes.size)
    }

    private fun assertEmpty(engine: Decompiler) {
        assertTrue(engine.classNames.isEmpty())
        assertNull(engine.classInfo("Example"))
        assertTrue(engine.classMembers("Example").isEmpty())
        assertNull(engine.smali("Example"))
        assertNull(engine.resources)
        assertTrue(engine.decompileAll().classes.isEmpty())
        assertTrue(engine.diagnostics.isEmpty(), "Cancellation must not become an input error")
    }

    private fun cancellations() = listOf(CancellationSignal("stop"), CancellationException("stop"))
    private fun resourceBytes() = SourceArchive.zip(listOf(ExportedFile("AndroidManifest.xml", "<manifest/>".encodeToByteArray())))

    private fun engine(input: () -> ClassData, fallback: () -> Unit = {}, root: () -> Unit = {}): Decompiler {
        val first = object : InputPlugin {
            override val id = "fixture"
            override fun tryLoad(name: String, bytes: ByteArray) = ListCodeLoader(listOf(input()))
        }
        val second = object : InputPlugin {
            override val id = "fallback"
            override fun tryLoad(name: String, bytes: ByteArray): ListCodeLoader? { fallback(); return null }
        }
        val prepare = root
        val passes = object : PassPlugin {
            override val id = "preparation"
            override fun rootPasses() = listOf(object : RootPass {
                override val name = "prepare fixture"
                override fun run(root: IrRoot, context: PassContext) = prepare()
            })
        }
        return Decompiler(DecompilerArgs(registry = PluginRegistry(listOf(first, second), listOf(passes))))
    }

    private open class FixtureClass : ClassData {
        override val type get() = "LExample;"
        override val accessFlags = 1
        override val superType = "Ljava/lang/Object;"
        override val interfaces: List<String> get() = emptyList()
        override val sourceFile: String? = null
        override val fields = emptyList<FieldData>()
        override val methods = emptyList<MethodData>()
        override val annotations = emptyList<AnnotationData>()
        override val inputFileName = "fixture"
        override fun disassemble() = "fixture smali"
    }
}
