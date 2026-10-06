package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes

class KotlinThrowableOverrideSemanticsTest {
    @Test fun exactPinnedExplicitOverrideKeepsNullableMessageAndJvmGetterIdentity() {
        val fixture = Corpus.root().resolve("smali/others/TestExplicitOverride.smali")
        val referenceRoot = Corpus.root().parentFile.resolve("reference/jadx")
        PinnedReference.verify(referenceRoot)
        assertTrue(fixture.readBytes().contentEquals(referenceRoot.resolve("jadx-core/src/test/smali/others/TestExplicitOverride.smali").readBytes()))
        assertEquals("5983d276292e4814a3a2dc20d7ac44ff0aeee8d211c468334e72c47fb306f4dc",
            MessageDigest.getInstance("SHA-256").digest(fixture.readBytes()).joinToString("") { "%02x".format(it) })
        val dex = SmaliAssembler.assemble(fixture).dex!!
        val original = DecompiledClass("others.TestExplicitOverride", """
            package others;
            public class TestExplicitOverride extends Exception {
                public TestExplicitOverride() { super(); }
                @Override public String getMessage() { return super.getMessage(); }
            }
        """.trimIndent())
        val reference = ReferenceDecompiler().decompile(fixture.name, dex)
        val candidate = KotlinJadxmpDecompiler().decompileKotlin(fixture.name, dex)
        fun verify(classes: List<DecompiledClass>, kotlin: Boolean) {
            withCompiledClasses(classes, kotlin) { loader ->
                val cls = loader.loadClass(original.fullName)
                val value = cls.getConstructor().newInstance() as Throwable
                assertEquals(null, value.message)
                val getter = cls.getDeclaredMethod("getMessage")
                assertEquals(String::class.java, getter.returnType)
                assertEquals(null, getter.invoke(value))
            }
        }
        verify(listOf(original), false)
        verify(reference.classes, false)
        assertTrue(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP), candidate.classes.joinToString("\n") { it.source })
        verify(candidate.classes, true)
    }

    @Test fun messageOverridesPreserveSuperVirtualDispatchNullableReturnsAndEffects() {
        val source = DecompiledClass("fixtures.Messages", """
            package fixtures;
            public class Messages {
                public static int trace;
                public static boolean held;
                public static class Base extends Exception {
                    public Base(String message) { super(message); trace = trace * 10 + 1; }
                    @Override public String getMessage() { trace = trace * 10 + 2; return super.getMessage(); }
                    public String getMessage(int unused) { return "overload"; }
                    public String exposed() { return getMessage(); }
                }
                public static class Child extends Base {
                    public Child(String message) { super(message); trace = trace * 10 + 3; }
                    @Override public final synchronized String getMessage() { held = Thread.holdsLock(this); trace = trace * 10 + 4; return super.getMessage(); }
                }
                public static class Unrelated {
                    public String getMessage() { return "ordinary"; }
                }
                public static String read(Throwable value) { trace = trace * 10 + 5; return value.getMessage(); }
                public static Throwable next(Throwable value) { trace = trace * 10 + 6; return value; }
                public static String chained(Throwable value) { return next(value).getMessage(); }
                public static String ordinary(Unrelated value) { return value.getMessage(); }
                public static String overloaded(Base value) { return value.getMessage(9); }
            }
        """.trimIndent())
        val dex = JavaFixtureCompiler.dex(JavaCheckFixture(listOf(source), source.fullName))
        val reference = ReferenceDecompiler().decompile("messages.dex", dex)
        val candidate = KotlinJadxmpDecompiler().decompileKotlin("messages.dex", dex)
        fun measure(classes: List<DecompiledClass>, kotlin: Boolean): List<Any?> {
            val observations = mutableListOf<Any?>()
            withCompiledClasses(classes, kotlin) { loader ->
                val cls = loader.loadClass(source.fullName)
                val target = if (kotlin) cls.getField("Companion").get(null) else null
                val owner = target?.javaClass ?: cls
                val trace = cls.getField("trace")
                val child = loader.loadClass("fixtures.Messages\$Child")
                val base = loader.loadClass("fixtures.Messages\$Base")
                val ordinary = loader.loadClass("fixtures.Messages\$Unrelated")
                assertGetterOwner(loader, base.name, "java/lang/Exception")
                assertGetterOwner(loader, child.name, "fixtures/Messages\$Base")
                // D8 desugars javac synchronization to explicit monitor instructions. The Kotlin body
                // retains those; pinned jadx reconstructs the JVM modifier as well.
                assertEquals(!kotlin, java.lang.reflect.Modifier.isSynchronized(child.getDeclaredMethod("getMessage").modifiers))
                for (message in listOf(null, "text")) {
                    trace.setInt(null, 0)
                    val value = child.getConstructor(String::class.java).newInstance(message)
                    observations += trace.getInt(null)
                    observations += owner.getMethod("read", Throwable::class.java).invoke(target, value)
                    observations += trace.getInt(null)
                    assertEquals(true, cls.getField("held").getBoolean(null))
                    observations += owner.getMethod("overloaded", base).invoke(target, value)
                    observations += java.lang.reflect.Modifier.isFinal(child.getDeclaredMethod("getMessage").modifiers)
                    trace.setInt(null, 0)
                    assertEquals(message, base.getMethod("exposed").invoke(value))
                    assertEquals(42, trace.getInt(null))
                    trace.setInt(null, 0)
                    assertEquals(message, owner.getMethod("chained", Throwable::class.java).invoke(target, value))
                    assertEquals(642, trace.getInt(null))
                }
                observations += owner.getMethod("ordinary", ordinary).invoke(target, ordinary.getConstructor().newInstance())
                trace.setInt(null, 0)
                val failure = runCatching { owner.getMethod("read", Throwable::class.java).invoke(target, null) }.exceptionOrNull()
                observations += (failure as java.lang.reflect.InvocationTargetException).targetException.javaClass.name
                observations += trace.getInt(null)
                trace.setInt(null, 0)
                val chainedFailure = runCatching { owner.getMethod("chained", Throwable::class.java).invoke(target, null) }.exceptionOrNull()
                assertTrue((chainedFailure as java.lang.reflect.InvocationTargetException).targetException is NullPointerException)
                assertEquals(6, trace.getInt(null))
            }
            return observations
        }
        val expected = listOf(13, null, 13542, "overload", true, 13, "text", 13542, "overload", true,
            "ordinary", "java.lang.NullPointerException", 5)
        assertEquals(expected, measure(listOf(source), false))
        assertEquals(expected, measure(reference.classes, false))
        assertTrue(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP), candidate.classes.joinToString("\n") { it.source })
        assertEquals(expected, measure(candidate.classes, true))
    }

    @Test fun exactPlatformConstructorOverloadsPreserveMessageCauseAndSuppression() {
        val source = DecompiledClass("fixtures.Constructors", """
            package fixtures;
            public class Constructors {
                public static class Direct extends Throwable {
                    public Direct(String message, Throwable cause, boolean suppress, boolean stack) {
                        super(message, cause, suppress, stack);
                    }
                }
                public static class Failure extends RuntimeException {
                    public Failure() { super(); }
                    public Failure(String message) { super(message); }
                    public Failure(Throwable cause) { super(cause); }
                    public Failure(String message, Throwable cause) { super(message, cause); }
                }
            }
        """.trimIndent())
        val dex = JavaFixtureCompiler.dex(JavaCheckFixture(listOf(source), source.fullName))
        val reference = ReferenceDecompiler().decompile("constructors.dex", dex)
        val candidate = KotlinJadxmpDecompiler().decompileKotlin("constructors.dex", dex)
        fun verify(classes: List<DecompiledClass>, kotlin: Boolean) {
            withCompiledClasses(classes, kotlin) { loader ->
                val failure = loader.loadClass("fixtures.Constructors\$Failure")
                val cause = IllegalStateException("cause")
                assertEquals(null, (failure.getConstructor().newInstance() as Throwable).message)
                for (message in listOf(null, "text")) {
                    assertEquals(message, (failure.getConstructor(String::class.java).newInstance(message) as Throwable).message)
                    val value = failure.getConstructor(String::class.java, Throwable::class.java).newInstance(message, cause) as Throwable
                    assertEquals(message, value.message)
                    assertTrue(value.cause === cause)
                }
                for (input in listOf(null, cause)) {
                    val value = failure.getConstructor(Throwable::class.java).newInstance(input) as Throwable
                    assertTrue(value.cause === input)
                    assertEquals(input?.toString(), value.message)
                }
                val direct = loader.loadClass("fixtures.Constructors\$Direct")
                val constructor = direct.getConstructor(String::class.java, Throwable::class.java,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                for (suppress in listOf(false, true)) for (stack in listOf(false, true)) {
                    val value = constructor.newInstance(null, cause, suppress, stack) as Throwable
                    assertEquals(null, value.message)
                    assertTrue(value.cause === cause)
                    value.addSuppressed(IllegalArgumentException())
                    assertEquals(if (suppress) 1 else 0, value.suppressed.size)
                    assertEquals(stack, value.stackTrace.isNotEmpty())
                }
            }
        }
        verify(listOf(source), false)
        verify(reference.classes, false)
        assertTrue(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP), candidate.classes.joinToString("\n") { it.source })
        verify(candidate.classes, true)
    }

    @Test fun absentRuntimeOwnerRequiresExplicitUnsupportedDiagnostic() {
        val sources = listOf(
            DecompiledClass("linkage.Missing", "package linkage; public class Missing extends Exception {}"),
            DecompiledClass("linkage.Caller", """
                package linkage;
                public class Caller {
                    public static int trace;
                    public static int healthy() { return 7; }
                    public static String run() {
                        Missing value = null;
                        trace = 7;
                        return value.getMessage();
                    }
                }
            """.trimIndent()),
        )
        val dex = JavaFixtureCompiler.dex(JavaCheckFixture(sources, "linkage.Caller"))
        val reference = ReferenceDecompiler().decompile("linkage.dex", dex)
        val candidate = KotlinJadxmpDecompiler().decompileKotlin("linkage.dex", dex)
        fun absentOwner(classes: List<DecompiledClass>, kotlin: Boolean): Pair<String, Int> {
            var observation: Pair<String, Int>? = null
            withCompiledClasses(classes, kotlin) { loader ->
                val output = java.io.File((loader as java.net.URLClassLoader).urLs.first().toURI())
                assertTrue(output.resolve("linkage/Missing.class").delete())
                val cls = loader.loadClass("linkage.Caller")
                val target = if (kotlin) cls.getField("Companion").get(null) else null
                val owner = target?.javaClass ?: cls
                val failure = runCatching { owner.getMethod("run").invoke(target) }.exceptionOrNull()
                observation = (failure as java.lang.reflect.InvocationTargetException).targetException.javaClass.name to
                    cls.getField("trace").getInt(null)
            }
            return observation!!
        }
        assertEquals("java.lang.NoClassDefFoundError" to 7, absentOwner(sources, false))
        assertEquals("java.lang.NoClassDefFoundError" to 7, absentOwner(reference.classes, false))
        val text = candidate.classes.joinToString("\n") { it.source }
        assertTrue(text.contains("nullable loaded-owner Throwable call cannot preserve JVM linkage"), text)
        assertTrue(!AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP), text)
        withCompiledClasses(candidate.classes, true) { loader ->
            val cls = loader.loadClass("linkage.Caller")
            val target = cls.getField("Companion").get(null)
            assertEquals(7, target.javaClass.getMethod("healthy").invoke(target))
            assertTrue(target.javaClass.declaredMethods.none { it.name == "run" })
        }
    }

    private fun assertGetterOwner(loader: ClassLoader, name: String, expected: String) {
        val calls = mutableListOf<Pair<Int, String>>()
        ClassReader(loader.getResourceAsStream(name.replace('.', '/') + ".class")!!.use { it.readBytes() }).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                if (name != "getMessage" || descriptor != "()Ljava/lang/String;") return null
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        if (name == "getMessage") calls += opcode to owner
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        assertEquals(listOf(Opcodes.INVOKESPECIAL to expected), calls)
    }

}
