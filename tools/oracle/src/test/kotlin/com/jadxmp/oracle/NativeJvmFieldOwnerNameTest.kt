package com.jadxmp.oracle

import com.jadxmp.api.Decompiler
import com.jadxmp.api.DecompilerArgs
import com.jadxmp.api.OutputFormat
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes.ASM9

class NativeJvmFieldOwnerNameTest {
    @Test fun javaFieldOwnersPreserveNamesInitializationAndEffects() = verify(OutputFormat.JAVA)
    @Test fun kotlinFieldOwnersPreserveNamesInitializationAndEffects() = verify(OutputFormat.KOTLIN)

    @Test fun unloadedAncestorCannotCaptureOwnerOrChangeInitialization() = verifyUnloadedAncestor(true)

    @Test fun staticPropertyContainerBindsOwnFieldDespiteUnloadedAncestorShadows() = verifyUnloadedAncestor(false)

    private fun verifyUnloadedAncestor(includeInstance: Boolean) {
        val property = "jadxmp.native.field.external.base"
        val base = DecompiledClass("fieldshadow.Base", """
            package fieldshadow;
            public class Base {
                public static class Owner { public static int value=99; }
                public static Integer Owner;
                public static Integer JvmOwner;
                static { Owner=99; JvmOwner=100; System.setProperty("$property", "initialized"); }
            }
        """.trimIndent())
        val source = DecompiledClass("fieldshadow.Owner", """
            package fieldshadow;
            public class Owner extends Base {
                public static int value=7;
                public static int read(){return value;}
                ${if (includeInstance) "public int instanceRead(){return value;}" else ""}
                public static int healthy(){return 9;}
                public static void write(int[] effects){value=effects[0]++;}
            }
        """.trimIndent())
        JavaCompilation.compile(listOf(base, source), release = 17).use { original ->
            assertTrue(original.result.success, original.result.diagnostics.toString())
            // A constructorless class is valid JVM input. Keep javac's field/method/clinit bytes,
            // removing only the separately unsupported constructor before original verification.
            val file = original.output.resolve("fieldshadow/Owner.class")
            val writer = ClassWriter(0)
            ClassReader(file.readBytes()).accept(object : ClassVisitor(ASM9, writer) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? =
                    if (name == "<init>") null else super.visitMethod(access, name, descriptor, signature, exceptions)
            }, 0)
            val bytes = writer.toByteArray()
            file.writeBytes(bytes)
            System.clearProperty(property)
            URLClassLoader(arrayOf(original.output.toURI().toURL())).use { loader ->
                val cls = loader.loadClass(source.fullName)
                assertNull(System.getProperty(property))
                assertEquals(7, cls.getMethod("read").invoke(null))
                assertEquals(7, cls.getDeclaredField("value").get(null))
                assertEquals("initialized", System.getProperty(property))
                val effects = intArrayOf(12)
                cls.getMethod("write", IntArray::class.java).invoke(null, effects)
                assertEquals(13, effects[0])
                assertEquals(12, cls.getMethod("read").invoke(null))
                if (includeInstance) assertEquals(12, cls.getMethod("instanceRead").invoke(allocate(cls)))
            }
            for (format in OutputFormat.entries) {
                val engine = Decompiler(DecompilerArgs(outputFormat = format))
                engine.load("Owner.class", bytes)
                val result = engine.decompileAll()
                assertEquals(if (format == OutputFormat.KOTLIN && includeInstance) 1 else 0, result.errorCount, result.classes.joinToString { it.code })
                val output = result.classes.single().let { DecompiledClass(it.fullName, it.code) }
                if (format == OutputFormat.KOTLIN && includeInstance) {
                    assertTrue(output.source.contains("owned static field binding is shadowed in an unresolved source scope"), output.source)
                    assertTrue(output.source.contains("fun healthy(): Int {\n            return 9"), output.source)
                    val compile = KotlinAccuracySignals.recompiles(listOf(output), listOf(original.output))
                    assertEquals(KotlinRecompileStatus.ERRORS, compile.status)
                    assertTrue(compile.errors.any { it.contains("value") }, compile.errors.toString())
                    continue // Explicit unsupported scope, never execution parity.
                }
                System.clearProperty(property)
                withCompiledClass(output, format == OutputFormat.KOTLIN, listOf(original.output)) { cls ->
                    assertNull(System.getProperty(property))
                    val target = if (format == OutputFormat.KOTLIN) cls.getField("Companion").get(null) else null
                    val owner = target?.javaClass ?: cls
                    assertEquals(7, owner.getMethod("read").invoke(target), output.source)
                    assertEquals(7, cls.getDeclaredField("value").apply { isAccessible = true }.get(null), output.source)
                    assertEquals("initialized", System.getProperty(property))
                    val effects = intArrayOf(12)
                    owner.getMethod("write", IntArray::class.java).invoke(target, effects)
                    assertEquals(13, effects[0])
                    assertEquals(12, owner.getMethod("read").invoke(target))
                    if (includeInstance) assertEquals(12, cls.getMethod("instanceRead").invoke(allocate(cls)))
                    assertEquals(9, owner.getMethod("healthy").invoke(target))
                }
            }
        }
        System.clearProperty(property)
    }


    @Test fun loadedInheritedTypeCannotRedirectOwnedInstanceRead() {
        val base = DecompiledClass("loadedfield.Base", """
            package loadedfield;
            public class Base { public static class Owner { public static int value=99; } }
        """.trimIndent())
        val source = DecompiledClass("loadedfield.Owner", """
            package loadedfield;
            public class Owner extends Base {
                public static int value=7;
                public int read(){return value;}
            }
        """.trimIndent())
        JavaCompilation.compile(listOf(base, source), release = 8).use { original ->
            assertTrue(original.result.success, original.result.diagnostics.toString())
            val file = original.output.resolve("loadedfield/Owner.class")
            val writer = ClassWriter(0)
            ClassReader(file.readBytes()).accept(object : ClassVisitor(ASM9, writer) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? =
                    if (name == "<init>") null else super.visitMethod(access, name, descriptor, signature, exceptions)
            }, 0)
            file.writeBytes(writer.toByteArray())
            URLClassLoader(arrayOf(original.output.toURI().toURL())).use { loader ->
                val cls = loader.loadClass(source.fullName)
                assertEquals(7, cls.getMethod("read").invoke(allocate(cls)))
            }
            for (format in OutputFormat.entries) {
                val plugin = object : com.jadxmp.api.plugin.InputPlugin {
                    override val id = "loaded-field-dependencies"
                    override fun tryLoad(name: String, bytes: ByteArray) = com.jadxmp.input.ListCodeLoader(
                        listOf("Base", "Base\$Owner", "Owner").flatMap { entry ->
                            com.jadxmp.input.jvm.JvmInput.loadClass("$entry.class",
                                original.output.resolve("loadedfield/$entry.class").readBytes()).classes
                        })
                }
                val engine = Decompiler(DecompilerArgs(outputFormat = format,
                    registry = com.jadxmp.api.plugin.PluginRegistry(listOf(plugin))))
                assertEquals(3, engine.load("field-dependencies", byteArrayOf()), engine.diagnostics.toString())
                val result = engine.decompileAll()
                // The dependency constructors are unsupported, but source generation of the target
                // uses their loaded declarations. Compile against the original helper classes.
                assertEquals(2, result.errorCount, result.classes.joinToString { it.code })
                val output = result.classes.single { it.fullName == source.fullName }.let { DecompiledClass(it.fullName, it.code) }
                assertFalse(output.source.contains("JADXMP ERROR"), output.source)
                withCompiledClass(output, format == OutputFormat.KOTLIN, listOf(original.output)) { cls ->
                    assertEquals(7, cls.getMethod("read").invoke(allocate(cls)), output.source)
                    assertEquals(7, cls.getDeclaredField("value").apply { isAccessible = true }.get(null))
                }
            }
        }
    }

    private fun allocate(cls: Class<*>): Any {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return (field.get(null) as sun.misc.Unsafe).allocateInstance(cls)
    }

    private fun verify(format: OutputFormat) {
        for (memberShadow in listOf(false, true)) {
            val declaration = if (memberShadow) "public static int i=2; static {value=i+3;}" else "static {value=5;}"
            val source = DecompiledClass("i", """
                public class i {
                    public static int value;
                    $declaration
                    public static int read(int n){return value+n;}
                    public static void write(int n){value=n;}
                    public static int initial(){return value;}
                    public static int through(i[] receivers,int[] effects){return receivers[effects[0]++].value;}
                    public static void assign(i[] receivers,int[] effects){receivers[effects[0]++].value=effects[0]++;}
                }
            """.trimIndent())
            JavaCompilation.compile(listOf(source), release = 17).use { original ->
                assertTrue(original.result.success, original.result.diagnostics.toString())
                val engine = Decompiler(DecompilerArgs(outputFormat = format))
                engine.load("i.class", original.output.resolve("i.class").readBytes())
                val result = engine.decompileAll()
                // Constructor support is explicitly outside this native slice; no instances needed.
                assertEquals(1, result.errorCount, result.classes.joinToString { it.code })
                val output = result.classes.single().let { DecompiledClass(it.fullName, it.code) }
                URLClassLoader(arrayOf(original.output.toURI().toURL()), javaClass.classLoader).use { loader ->
                    val expectedClass = loader.loadClass("i")
                    withCompiledClass(output, format == OutputFormat.KOTLIN) { cls ->
                        val target = if (format == OutputFormat.KOTLIN) cls.getField("Companion").get(null) else null
                        val owner = target?.javaClass ?: cls
                        val intType = Int::class.javaPrimitiveType!!
                        assertEquals(5, owner.getMethod("initial").invoke(target))
                        owner.getMethod("write", intType).invoke(target, 7)
                        expectedClass.getMethod("write", intType).invoke(null, 7)
                        assertEquals(12, owner.getMethod("read", intType).invoke(target, 5))
                        for (method in listOf("through", "assign")) for (arrayKind in 0..2) {
                            val expectedArray = if (arrayKind == 0) null else ReflectArray.newInstance(expectedClass, arrayKind - 1)
                            val actualArray = if (arrayKind == 0) null else ReflectArray.newInstance(cls, arrayKind - 1)
                            val before = intArrayOf(0); val after = intArrayOf(0)
                            fun call(on: Class<*>, instance: Any?, arrayClass: Class<*>, receivers: Any?, effects: IntArray): Any? = try {
                                on.getMethod(method, arrayClass, IntArray::class.java).invoke(instance, receivers, effects)
                            } catch (failure: InvocationTargetException) { failure.cause!!::class.java.name }
                            val expected = call(expectedClass, null, ReflectArray.newInstance(expectedClass, 0).javaClass, expectedArray, before)
                            val actual = call(owner, target, ReflectArray.newInstance(cls, 0).javaClass, actualArray, after)
                            assertEquals(expected, actual, output.source)
                            assertArrayEquals(before, after)
                            assertEquals(expectedClass.getMethod("initial").invoke(null), owner.getMethod("initial").invoke(target))
                        }
                    }
                }
            }
        }
    }
}
