package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

class KotlinConstructorNameCollisionSemanticsTest {
    @Test fun originalWrongCode2CompilesWithoutRenamingItsJvmMembers() {
        val reference = Corpus.root().parentFile.resolve("reference/jadx")
        PinnedReference.verify(reference)
        val source = reference.resolve("jadx-core/src/test/java/jadx/tests/integration/others/TestWrongCode2.java").readText()
        val sample = UpstreamJavaExtractor.extract(source).sample!!
        val fixture = JavaCheckFixture(listOf(sample.source), sample.checkClass, UpstreamJavaInventory.fixtureClasspath)
        val dex = JavaFixtureCompiler.dex(fixture)
        val candidate = KotlinJadxmpDecompiler().decompileKotlin("TestWrongCode2", dex)
        assertTrue(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP))
        for ((classes, kotlin) in listOf(listOf(sample.source) to false,
            ReferenceDecompiler().decompile("TestWrongCode2", dex).classes to false, candidate.classes to true)) {
            withCompiledClasses(classes, kotlin) { loader ->
                val cls = loader.loadClass(sample.checkClass)
                val nested = loader.loadClass(sample.checkClass + "\$A")
                assertEquals(nested, cls.getMethod("A").returnType)
                val instance = cls.getConstructor().newInstance()
                assertEquals(false, cls.getMethod("test4").invoke(instance))
                for (name in listOf("test", "test2", "test3", "A")) {
                    val failure = runCatching { cls.getMethod(name).invoke(instance) }.exceptionOrNull()
                    assertTrue(failure is InvocationTargetException)
                    val cause = (failure as InvocationTargetException).targetException
                    assertTrue(if (name == "A") cause is StackOverflowError else cause is NullPointerException,
                        "$name: $cause")
                }
            }
        }
    }

    @Test fun constructorCallsAndVirtualMethodsRetainNamesDispatchAndEffects() {
        val source = DecompiledClass("collision.Collision", """
            package collision;
            public class Collision {
                public static int trace;
                public static class A {
                    public final int value;
                    public A() { trace = trace * 10 + 1; value = 7; }
                }
                public A A() { trace = trace * 10 + 2; return new A(); }
                public A call() { return A(); }
                public A construct() { return new A(); }
                public A[] array() { return new A[] {new A(), null}; }
                public static class Child extends Collision {
                    @Override public A A() { Collision.trace = Collision.trace * 10 + 3; return new A(); }
                }
            }
        """.trimIndent())
        val callerSource = DecompiledClass("collision.ConstructorUser", """
            package collision;
            public class ConstructorUser {
                public Object JvmA;
                public Collision.A make(Object Collision, Object JvmA, Object KotlinSuppress, Object collision) {
                    return new collision.Collision.A();
                }
            }
        """.trimIndent())
        val originals = listOf(source, callerSource)
        val dex = JavaFixtureCompiler.dex(JavaCheckFixture(originals, source.fullName))
        val candidate = KotlinJadxmpDecompiler().decompileKotlin("collision", dex)
        assertTrue(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP), candidate.classes.joinToString { it.source })
        val bytecodeCaller = DecompiledClass("collision.BytecodeCaller", """
            package collision;
            public class BytecodeCaller {
                public static Collision.A call(Collision receiver) { return receiver.A(); }
                public static Collision.A construct() { return new Collision.A(); }
            }
        """.trimIndent())
        val externalChild = DecompiledClass("collision.ExternalChild", """
            package collision;
            public class ExternalChild extends Collision {
                @Override public Collision.A A() { super.A(); return super.A(); }
            }
        """.trimIndent())
        val nullChild = DecompiledClass("collision.NullChild", """
            package collision;
            public class NullChild extends Collision {
                @Override public Collision.A A() { super.A(); return null; }
            }
        """.trimIndent())
        JavaCompilation.compile(originals + bytecodeCaller + externalChild + nullChild).use { external ->
            assertTrue(external.result.success, external.result.diagnostics.toString())
            external.output.resolve("collision").listFiles()!!.filter {
                it.name.startsWith("Collision") || it.name.startsWith("ConstructorUser")
            }.forEach { assertTrue(it.delete()) }
            for ((classes, kotlin) in listOf(originals to false,
                ReferenceDecompiler().decompile("collision", dex).classes to false, candidate.classes to true)) {
                withCompiledClasses(classes, kotlin, listOf(external.output)) { loader ->
                val cls = loader.loadClass(source.fullName)
                val child = loader.loadClass(source.fullName + "\$Child")
                val nested = loader.loadClass(source.fullName + "\$A")
                val trace = cls.getDeclaredField("trace").apply { isAccessible = true }
                val externalOwner = loader.loadClass(externalChild.fullName)
                val binaryCaller = loader.loadClass(bytecodeCaller.fullName)
                assertEquals(nested, binaryCaller.getMethod("call", cls).returnType)
                assertEquals(0, nested.getConstructor().parameterCount)
                assertTrue(!Modifier.isFinal(cls.getMethod("A").modifiers))
                for ((owner, expected) in listOf(cls to 21, child to 31, externalOwner to 2121)) {
                    val instance = owner.getConstructor().newInstance()
                    assertEquals(nested, owner.getDeclaredMethod("A").returnType)
                    trace.setInt(null, 0)
                    assertEquals(nested, binaryCaller.getMethod("call", cls).invoke(null, instance).javaClass)
                    assertEquals(expected, trace.getInt(null))
                    for ((name, effects) in listOf("A" to expected, "call" to expected, "construct" to 1)) {
                        trace.setInt(null, 0)
                        val value = cls.getMethod(name).invoke(instance)
                        assertEquals(nested, value.javaClass)
                        assertEquals(effects, trace.getInt(null), "$owner.$name")
                    }
                }
                trace.setInt(null, 0)
                val array = cls.getMethod("array").invoke(cls.getConstructor().newInstance()) as Array<*>
                assertEquals(nested, array.javaClass.componentType)
                assertEquals(nested, array[0]!!.javaClass)
                assertEquals(null, array[1])
                assertEquals(1, trace.getInt(null))
                trace.setInt(null, 0)
                assertEquals(nested, binaryCaller.getMethod("construct").invoke(null).javaClass)
                assertEquals(1, trace.getInt(null))
                trace.setInt(null, 0)
                val failure = runCatching { binaryCaller.getMethod("call", cls).invoke(null, null) }.exceptionOrNull()
                assertTrue(failure is InvocationTargetException && failure.targetException is NullPointerException)
                assertEquals(0, trace.getInt(null))
                trace.setInt(null, 0)
                val nullableChild = loader.loadClass(nullChild.fullName).getConstructor().newInstance()
                assertEquals(null, binaryCaller.getMethod("call", cls).invoke(null, nullableChild))
                assertEquals(21, trace.getInt(null))
                val user = loader.loadClass(callerSource.fullName)
                trace.setInt(null, 0)
                val value = user.getMethod("make", Any::class.java, Any::class.java, Any::class.java, Any::class.java)
                    .invoke(user.getConstructor().newInstance(), null, null, null, null)
                assertEquals(nested, value.javaClass)
                assertEquals(1, trace.getInt(null))
                }
            }
        }
    }
    @Test fun constructorAliasesRequireKnownCallerMembersAndAvoidLoadedInheritedCallables() {
        val target = DecompiledClass("namescope.Target", """
            package namescope;
            public class Target {
                public static int constructions;
                public static class A { public A() { constructions++; } }
                public A A() { return new A(); }
            }
        """.trimIndent())
        val base = DecompiledClass("namescope.Base", """
            package namescope;
            public class Base {
                public static kotlin.jvm.functions.Function0<Target.A> JvmA;
            }
        """.trimIndent())
        val caller = DecompiledClass("namescope.Caller", """
            package namescope;
            public class Caller extends Base {
                public Target.A make() { return new Target.A(); }
                public int healthy() { return 7; }
            }
        """.trimIndent())
        val stdlib = java.io.File(Unit::class.java.protectionDomain.codeSource.location.toURI())
        JavaCompilation.compile(listOf(target, base), listOf(stdlib)).use { library ->
            assertTrue(library.result.success, library.result.diagnostics.toString())
            val unknownDex = JavaFixtureCompiler.dex(JavaCheckFixture(listOf(target, caller), caller.fullName,
                listOf(library.output, stdlib)))
            val unknown = KotlinJadxmpDecompiler().decompileKotlin("unknown-scope", unknownDex)
            val flagged = unknown.classes.single { it.fullName == caller.fullName }
            assertTrue(flagged.source.contains("constructor alias requires a complete caller name scope"), flagged.source)
            assertTrue(flagged.source.contains("return 7"), flagged.source)
            assertTrue(!AccuracySignals.noErrors(unknown, ErrorMarkers.JADXMP))
            assertTrue(!flagged.source.contains("return JvmA()"), flagged.source)

            val loadedDex = JavaFixtureCompiler.dex(JavaCheckFixture(listOf(target, base, caller), caller.fullName,
                listOf(stdlib)))
            val loaded = KotlinJadxmpDecompiler().decompileKotlin("loaded-scope", loadedDex)
            // Base remains a separately compiled library at execution; its loaded declaration was
            // used solely to prove/reserve inherited names, never to replace its Function0 field.
            val outputs = loaded.classes.filter { it.fullName != base.fullName }
            assertTrue(outputs.none { it.source.contains("JADXMP ERROR") }, outputs.joinToString { it.source })
            for ((classes, kotlin) in listOf(listOf(target, caller) to false, outputs to true)) {
                withCompiledClasses(classes, kotlin, listOf(library.output, stdlib)) { loader ->
                    val owner = loader.loadClass(target.fullName)
                    val cls = loader.loadClass(caller.fullName)
                    val value = cls.getMethod("make").invoke(cls.getConstructor().newInstance())
                    assertEquals("namescope.Target\$A", value.javaClass.name)
                    val constructions = owner.getDeclaredField("constructions").apply { isAccessible = true }
                    assertEquals(1, constructions.getInt(null))
                    assertEquals(7, cls.getMethod("healthy").invoke(cls.getConstructor().newInstance()))
                }
            }
        }
    }

}
