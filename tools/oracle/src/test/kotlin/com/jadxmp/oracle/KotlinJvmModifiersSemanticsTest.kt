package com.jadxmp.oracle

import com.jadxmp.api.Decompiler
import com.jadxmp.api.DecompilerArgs
import com.jadxmp.api.OutputFormat
import com.jadxmp.api.plugin.InputPlugin
import com.jadxmp.api.plugin.PluginRegistry
import com.jadxmp.input.jvm.JvmInput
import com.jadxmp.codegen.CodegenKeys
import com.jadxmp.codegen.kotlin.KotlinCodeGenerator
import com.jadxmp.ir.insn.ArithInstruction
import com.jadxmp.ir.insn.ArithOp
import com.jadxmp.ir.insn.Instruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import java.lang.management.ManagementFactory
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.tools.ToolProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KotlinJvmModifiersSemanticsTest {
    @Test fun staticMethodsUseOriginalClassMonitorAndReleaseItOnExceptionalExit() = withNative("NativeLocks", """
        public final class NativeLocks {
            private NativeLocks() {}
            public static synchronized int value() { return 7; }
            public static synchronized int divide(int divisor) { return 7 / divisor; }
            public static int plain() { return 9; }
            public static synchronized int nullable(Object value) { return 7; }
        }
    """.trimIndent()) { original, generated ->
        verifyStaticMonitor(original, null)
        verifyNullMonitorEntry(original, null)
        withCompiledClass(generated, true) { cls ->
            val companion = cls.getField("Companion").get(null)
            verifyStaticMonitor(cls, companion)
            verifyNullMonitorEntry(cls, companion)
        }
    }

    @Test fun nativeVolatileAndTransientFlagsSurviveKotlinBackingFieldEmission() = withNative("NativeFields", """
        public class NativeFields {
            public volatile int value;
            public transient int scratch;
            public static volatile int staticValue;
            public static transient int staticScratch;
            public volatile transient int both;
        }
    """.trimIndent()) { original, generated ->
        withCompiledClass(generated, true) { cls ->
            for (field in original.declaredFields) {
                val emitted = cls.getDeclaredField(field.name)
                assertEquals(Modifier.isVolatile(field.modifiers), Modifier.isVolatile(emitted.modifiers), field.name)
                assertEquals(Modifier.isTransient(field.modifiers), Modifier.isTransient(emitted.modifiers), field.name)
                assertEquals(Modifier.isStatic(field.modifiers), Modifier.isStatic(emitted.modifiers), field.name)
            }
        }
    }

    @Test fun instanceMonitorAndTransientSerializationPreserveRuntimeContracts() {
        val root = IrRoot()
        val model = IrClass(root, "InstanceLocks", 0x11, interfaces = listOf(IrType.objectType("java.io.Serializable")))
        root.addClass(model)
        addMethods(model, static = false)
        model.fields += IrField(model, "saved", IrType.INT, 1 or 0x40)
        model.fields += IrField(model, "scratch", IrType.INT, 1 or 0x80)
        withCompiledClass(DecompiledClass(model.fullName, KotlinCodeGenerator().generate(model).code), true) { cls ->
            val instance = cls.getConstructor().newInstance()
            assertTrue(Modifier.isSynchronized(cls.getMethod("value").modifiers))
            verifyMonitor(cls, instance, instance)
            val saved = cls.getDeclaredField("saved").apply { isAccessible = true }
            val scratch = cls.getDeclaredField("scratch").apply { isAccessible = true }
            saved.setInt(instance, 17)
            scratch.setInt(instance, 29)
            val bytes = ByteArrayOutputStream().also { output -> ObjectOutputStream(output).use { it.writeObject(instance) } }.toByteArray()
            val restored = object : ObjectInputStream(ByteArrayInputStream(bytes)) {
                override fun resolveClass(desc: ObjectStreamClass): Class<*> = Class.forName(desc.name, false, cls.classLoader)
            }.use { it.readObject() }
            assertEquals(17, saved.getInt(restored))
            assertEquals(0, scratch.getInt(restored))
        }
    }

    @Test fun monitorOwnerAndIntrinsicNamesResistClassAndParameterCollisions() {
        for (name in listOf("kotlin", "JvmMonitorOwner", "kotlinSynchronized", "KotlinSynchronized")) {
            val root = IrRoot()
            val model = IrClass(root, name, 0x11)
            root.addClass(model)
            addMethods(model, static = true)
            model.methods.single { it.name == "divide" }[CodegenKeys.PARAM_NAMES] = listOf("kotlinSynchronized")
            withCompiledClass(DecompiledClass(name, KotlinCodeGenerator().generate(model).code), true) { cls ->
                verifyStaticMonitor(cls, cls.getField("Companion").get(null))
            }
        }
    }

    @Test fun privateNestedStaticMethodsResolveTheirExactDeclaringClass() {
        val root = IrRoot()
        val outer = IrClass(root, "NestedLocks", 0x11)
        val inner = IrClass(root, "NestedLocks\$Hidden", 0x1a)
        root.addClass(outer)
        root.addClass(inner)
        inner.outerClass = outer
        outer.innerClasses += inner
        addMethods(inner, static = true)
        val source = KotlinCodeGenerator().generate(outer).code
        withCompiledClass(DecompiledClass(outer.fullName, source), true) { cls ->
            val nested = cls.classLoader.loadClass("NestedLocks\$Hidden")
            val companion = nested.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
            verifyStaticMonitor(nested, companion)
        }
    }

    @Test fun renamedOwnerAndFlaggedEnumEntryCompileWithOriginalContracts() {
        val root = IrRoot()
        val model = IrClass(root, "OriginalLocks", 0x11)
        root.addClass(model)
        addMethods(model, static = true)
        val aliases = com.jadxmp.codegen.AliasMap.of(mapOf(com.jadxmp.codegen.ClassNodeRef(model.fullName) to "RenamedLocks"))
        withCompiledClass(DecompiledClass("RenamedLocks", KotlinCodeGenerator().generate(model, aliases).code), true) { cls ->
            verifyStaticMonitor(cls, cls.getField("Companion").get(null))
        }
        val enum = IrClass(root, "FlaggedEntry", 0x4011)
        root.addClass(enum)
        enum.fields += IrField(enum, "RED", IrType.objectType(enum.fullName), 0x4099)
        withCompiledClass(DecompiledClass(enum.fullName, KotlinCodeGenerator().generate(enum).code), true) { cls ->
            assertTrue(Modifier.isTransient(cls.getField("RED").modifiers))
        }
    }

    private fun verifyNullMonitorEntry(cls: Class<*>, target: Any?) {
        val nullable = (target?.javaClass ?: cls).getMethod("nullable", Any::class.java)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try { assertEquals(7, nullable.invoke(target, null)) } catch (error: Throwable) { failure.set(error) }
            finally { done.countDown() }
        }.apply { isDaemon = true }
        synchronized(cls) {
            thread.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var blocked = false
            while (System.nanoTime() < deadline && done.count != 0L) {
                val info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId())
                if (info?.threadState == Thread.State.BLOCKED && info.lockInfo?.identityHashCode == System.identityHashCode(cls)) {
                    blocked = true
                    break
                }
                Thread.sleep(1)
            }
            assertTrue(blocked, "null argument must not bypass implicit monitor entry")
        }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
    }

    private fun addMethods(cls: IrClass, static: Boolean) {
        for ((name, value) in listOf("value" to 7, "plain" to 9, "divide" to 7)) {
            val types = if (name == "divide") listOf(IrType.INT) else emptyList()
            val flags = 1 or (if (static) 8 else 0) or (if (name != "plain") 0x20 else 0)
            val method = IrMethod(cls, name, IrType.INT, types, flags)
            val block = BasicBlock(0)
            if (name == "divide") {
                method[CodegenKeys.PARAM_NAMES] = listOf("kotlinSynchronized")
                val parameter = RegisterOperand(0, IrType.INT)
                val local = com.jadxmp.ir.node.LocalVar().apply {
                    this.name = "kotlinSynchronized"
                    type = IrType.INT
                    add(com.jadxmp.ir.attr.AttrFlag.METHOD_ARGUMENT)
                }
                val value = com.jadxmp.ir.node.SsaValue(0, 0, parameter)
                local.addSsaValue(value)
                parameter.ssaValue = value
                val expression = ArithInstruction(ArithOp.DIV, args = listOf(LiteralOperand(7, IrType.INT), parameter))
                block.instructions += Instruction(IrOpcode.RETURN, args = listOf(com.jadxmp.ir.insn.InstructionOperand(expression)))
            } else block.instructions += Instruction(IrOpcode.RETURN, args = listOf(LiteralOperand(value.toLong(), IrType.INT)))
            method.blocks += block
            cls.methods += method
        }
    }

    private fun verifyStaticMonitor(cls: Class<*>, target: Any?) = verifyMonitor(cls, target, cls, target)

    private fun verifyMonitor(cls: Class<*>, target: Any?, lock: Any, excluded: Any? = null) {
        val owner = target?.javaClass ?: cls
        val value = owner.getMethod("value").apply { isAccessible = true }
        Class.forName(cls.name, true, cls.classLoader)
        // Reentrant acquisition is required, and an unsynchronized sibling must stay independent.
        synchronized(lock) {
            assertEquals(7, value.invoke(target))
            assertEquals(9, owner.getMethod("plain").apply { isAccessible = true }.invoke(target))
        }
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            started.countDown()
            try { assertEquals(7, value.invoke(target)) } catch (error: Throwable) { failure.set(error) }
            finally { finished.countDown() }
        }.apply { isDaemon = true }
        synchronized(lock) {
            val plainDone = CountDownLatch(1)
            Thread {
                try { assertEquals(9, owner.getMethod("plain").apply { isAccessible = true }.invoke(target)) }
                catch (error: Throwable) { failure.set(error) }
                finally { plainDone.countDown() }
            }.apply { isDaemon = true; start() }
            assertTrue(plainDone.await(2, TimeUnit.SECONDS), "unsynchronized sibling acquired a monitor")
            failure.get()?.let { throw it }
            thread.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var observed = false
            while (System.nanoTime() < deadline && finished.count != 0L) {
                val info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId())
                if (info?.threadState == Thread.State.BLOCKED && info.lockInfo?.identityHashCode == System.identityHashCode(lock)) {
                    observed = true
                    break
                }
                Thread.sleep(1)
            }
            assertTrue(observed, "method did not block on original Class monitor: ${cls.name}")
        }
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
        if (excluded != null) {
            val done = CountDownLatch(1)
            val companionThread = Thread {
                try { assertEquals(7, value.invoke(target)) } catch (error: Throwable) { failure.set(error) }
                finally { done.countDown() }
            }.apply { isDaemon = true }
            synchronized(excluded) {
                companionThread.start()
                assertTrue(done.await(2, TimeUnit.SECONDS), "static method incorrectly locks Companion")
            }
            failure.get()?.let { throw it }
        }
        val thrown = assertThrows(InvocationTargetException::class.java) {
            owner.getMethod("divide", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(target, 0)
        }
        assertInstanceOf(ArithmeticException::class.java, thrown.cause)
        // A subsequent different thread must acquire the monitor after exceptional completion.
        val released = CountDownLatch(1)
        Thread {
            try { assertEquals(7, value.invoke(target)) } catch (error: Throwable) { failure.set(error) }
            finally { released.countDown() }
        }.apply { isDaemon = true; start() }
        assertTrue(released.await(2, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
        assertFalse(Thread.holdsLock(lock))
    }

    private fun withNative(name: String, source: String, action: (Class<*>, DecompiledClass) -> Unit) {
        val directory = Files.createTempDirectory("jadxmp-native-modifiers").toFile()
        try {
            val input = directory.resolve("$name.java").apply { writeText(source) }
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-g", "-d", directory.path, input.path))
            val plugin = object : InputPlugin {
                override val id = "native-modifiers-test"
                override fun tryLoad(name: String, bytes: ByteArray) = JvmInput.loadClass(name, bytes)
            }
            val engine = Decompiler(DecompilerArgs(outputFormat = OutputFormat.KOTLIN, registry = PluginRegistry(listOf(plugin))))
            assertEquals(1, engine.load("$name.class", directory.resolve("$name.class").readBytes()))
            val result = engine.decompileAll()
            // Native constructor lowering remains unsupported; its flagged throwing stub is retained.
            assertEquals(1, result.errorCount, result.classes.joinToString { it.code })
            val generated = result.classes.single()
            URLClassLoader(arrayOf(directory.toURI().toURL())).use { loader ->
                action(loader.loadClass(name), DecompiledClass(generated.fullName, generated.code))
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
