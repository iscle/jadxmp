package com.jadxmp.oracle

import com.jadxmp.api.Decompiler
import com.jadxmp.api.DecompilerArgs
import com.jadxmp.api.OutputFormat
import com.jadxmp.api.plugin.InputPlugin
import com.jadxmp.api.plugin.PluginRegistry
import com.jadxmp.input.*
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Exercise the normalized JVM ACC_SYNCHRONIZED contract before either source backend runs. */
class SynchronizedForwarderSemanticsTest {
    @Test fun javaFacadePreservesSyntheticForwarderMonitor() = verify(false)
    @Test fun kotlinFacadePreservesSyntheticForwarderMonitor() = verify(true)

    private fun verify(kotlin: Boolean) {
        run {
            val plugin = object : InputPlugin {
                override val id = "synchronized-forwarder-fixture"
                override fun tryLoad(name: String, bytes: ByteArray) = ListCodeLoader(listOf(Fixture()))
            }
            val engine = Decompiler(DecompilerArgs(
                outputFormat = if (kotlin) OutputFormat.KOTLIN else OutputFormat.JAVA,
                registry = PluginRegistry(listOf(plugin)),
            ))
            assertEquals(1, engine.load("normalized-jvm-monitor", byteArrayOf()))
            val result = engine.decompileAll()
            assertEquals(0, result.errorCount, result.classes.joinToString { it.code })
            val source = result.classes.single()
            withCompiledClass(DecompiledClass(source.fullName, source.code), kotlin) { cls ->
                val receiver = if (kotlin) cls.getField("Companion").get(null) else null
                val owner = receiver?.javaClass ?: cls
                val caller = owner.getMethod("caller")
                assertEquals(7, caller.invoke(receiver))
                val finished = CountDownLatch(1)
                val failure = AtomicReference<Throwable?>()
                val thread = Thread {
                    try { assertEquals(7, caller.invoke(receiver)) } catch (error: Throwable) { failure.set(error) }
                    finally { finished.countDown() }
                }.apply { isDaemon = true }
                synchronized(cls) {
                    thread.start()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    var blocked = false
                    while (System.nanoTime() < deadline && finished.count != 0L) {
                        val info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId())
                        if (info?.threadState == Thread.State.BLOCKED &&
                            info.lockInfo?.identityHashCode == System.identityHashCode(cls)) {
                            blocked = true
                            break
                        }
                        Thread.sleep(1)
                    }
                    assertTrue(blocked, "forwarder monitor was removed: ${source.code}")
                }
                assertTrue(finished.await(2, TimeUnit.SECONDS))
                failure.get()?.let { throw it }
                // The retained declaration is part of the preserved observable contract too.
                assertEquals(7, owner.getMethod("bridge").invoke(receiver))
            }
        }
    }

    private class Fixture : ClassData {
        override val type = "LForwarderLocks;"
        override val accessFlags = 0x11
        override val superType = "Ljava/lang/Object;"
        override val interfaces = emptyList<String>()
        override val sourceFile: String? = null
        override val fields = emptyList<FieldData>()
        override val annotations = emptyList<AnnotationData>()
        override val inputFileName = "normalized-jvm-monitor"
        override val methods = listOf(method("target", null, 9), method("bridge", "target", 0x1029), method("caller", "bridge", 9))
        override fun disassemble() = "normalized JVM synchronized synthetic forwarder"

        private fun method(name: String, target: String?, flags: Int) = object : MethodData {
            override val ref = reference(name)
            override val accessFlags = flags
            override val annotations = emptyList<AnnotationData>()
            override val parameterAnnotations = emptyList<List<AnnotationData>>()
            override val codeReader = object : CodeReader {
                override val registerCount = 1
                override val unitsCount = 2
                override val codeOffset = 0
                override val tries = emptyList<TryBlock>()
                override val debugInfo: DebugInfo? = null
                override fun visitInstructions(visitor: (Instruction) -> Unit) {
                    visitor(Insn(if (target == null) Opcode.CONST else Opcode.INVOKE_STATIC, 0, target))
                    visitor(Insn(Opcode.RETURN, 1, null))
                }
            }
        }
    }

    private class Insn(override val opcode: Opcode, override val offset: Int, private val targetName: String?) : Instruction {
        override fun decode() = Unit
        override val fileOffset = offset
        override val mnemonic = opcode.name
        override val rawOpcodeUnit = 0
        override val indexType = if (targetName == null) IndexType.NONE else IndexType.METHOD_REF
        override val registerCount = if (targetName == null) 1 else 0
        override fun register(argNum: Int): Int { require(argNum == 0 && registerCount == 1); return 0 }
        override val resultRegister = if (targetName == null) -1 else 0
        override val literal = 7L
        override val target = 0
        override val index = 0
        override val payload: InstructionPayload? = null
        override fun indexAsMethod() = reference(requireNotNull(targetName))
        override fun indexAsString(): String = error("not a string")
        override fun indexAsType(): String = error("not a type")
        override fun indexAsField(): FieldRef = error("not a field")
        override fun indexAsProto(protoIndex: Int): MethodProto = error("not a proto")
        override fun indexAsCallSite(): CallSite = error("not a callsite")
        override fun indexAsMethodHandle(): MethodHandle = error("not a handle")
    }

    companion object {
        private fun reference(name: String) = object : MethodRef {
            override val declaringClassType = "LForwarderLocks;"
            override val name = name
            override val returnType = "I"
            override val parameterTypes = emptyList<String>()
        }
    }
}
