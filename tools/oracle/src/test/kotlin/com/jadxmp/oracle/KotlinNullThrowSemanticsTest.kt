package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException

class KotlinNullThrowSemanticsTest {
    @Test fun originalNullInlineAndResolverFixturesCompileAndThrowNpe() {
        val reference = Corpus.root().parentFile.resolve("reference/jadx")
        PinnedReference.verify(reference)
        for (path in listOf("others/TestNullInline.java", "types/TestTypeResolver24.java")) {
            val original = reference.resolve("jadx-core/src/test/java/jadx/tests/integration/$path").readText()
            val sample = UpstreamJavaExtractor.extract(original).sample!!
            assertEquals(OriginalFixtureStatus.COMPILED_NO_CHECK, UpstreamJavaInventory.validate(sample).status)
            val fixture = JavaCheckFixture(listOf(sample.source), sample.checkClass, UpstreamJavaInventory.fixtureClasspath)
            val dex = JavaFixtureCompiler.dex(fixture)
            val candidate = KotlinJadxmpDecompiler().decompileKotlin(path, dex)
            assertTrue(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP), candidate.classes.joinToString { it.source })
            fun verify(classes: List<DecompiledClass>, kotlin: Boolean) {
                withCompiledClasses(classes, kotlin) { loader ->
                    val cls = loader.loadClass(sample.checkClass)
                    val methodOwner = if (kotlin && path.contains("NullInline")) cls.getField("Companion").get(null) else null
                    val owner = methodOwner?.javaClass ?: cls
                    val method = owner.declaredMethods.single { it.name == "test" }
                    val target = methodOwner ?: if (java.lang.reflect.Modifier.isStatic(method.modifiers)) null else cls.getConstructor().newInstance()
                    val arguments = if (path.contains("NullInline")) listOf(arrayOf<Any?>(null), arrayOf<Any?>(1.5)) else listOf(emptyArray())
                    for (args in arguments) {
                        val thrown = runCatching { method.invoke(target, *args) }.exceptionOrNull()
                        assertTrue(thrown is InvocationTargetException && thrown.targetException is NullPointerException,
                            "$path: $thrown")
                    }
                }
            }
            verify(listOf(sample.source), false)
            verify(ReferenceDecompiler().decompile(path, dex).classes, false)
            verify(candidate.classes, true)
        }
    }

    @Test fun effectfulAndUnknownThrowOperandsKeepEvaluationAndExceptionIdentity() {
        val source = DecompiledClass("fixtures.ThrowOperands", """
            package fixtures;
            public class ThrowOperands {
                public static int trace;
                public static void known() {
                    trace = 7;
                    RuntimeException failure = null;
                    throw failure;
                }
                public static RuntimeException next(RuntimeException value) { trace = trace * 10 + 1; return value; }
                public static void effect(RuntimeException value) throws RuntimeException { throw next(value); }
                public static void unknown(RuntimeException value) throws RuntimeException { throw value; }
                public static void reassigned(RuntimeException value) throws RuntimeException {
                    RuntimeException failure = null;
                    failure = next(value);
                    throw failure;
                }
            }
        """.trimIndent())
        val fixture = JavaCheckFixture(listOf(source), source.fullName)
        val dex = JavaFixtureCompiler.dex(fixture)
        for (kotlin in listOf(false, true)) {
            val output = if (kotlin) KotlinJadxmpDecompiler().decompileKotlin("throws.dex", dex)
                else JadxmpDecompiler().decompile("throws.dex", dex)
            assertTrue(AccuracySignals.noErrors(output, ErrorMarkers.JADXMP), output.classes.joinToString { it.source })
            withCompiledClasses(output.classes, kotlin) { loader ->
                val cls = loader.loadClass(source.fullName)
                val target = if (kotlin) cls.getField("Companion").get(null) else null
                val owner = target?.javaClass ?: cls
                val trace = cls.getDeclaredField("trace").apply { isAccessible = true }
                trace.setInt(null, 0)
                val knownFailure = runCatching { owner.getMethod("known").invoke(target) }.exceptionOrNull()
                assertTrue(knownFailure is InvocationTargetException && knownFailure.targetException is NullPointerException)
                assertEquals(7, trace.getInt(null))
                for (name in listOf("effect", "unknown", "reassigned")) for (failure in listOf(null, IllegalStateException("original"))) {
                    trace.setInt(null, 0)
                    val thrown = runCatching { owner.getMethod(name, RuntimeException::class.java).invoke(target, failure) }.exceptionOrNull()
                    assertTrue(thrown is InvocationTargetException)
                    val actual = (thrown as InvocationTargetException).targetException
                    if (failure == null) assertTrue(actual is NullPointerException) else assertSame(failure, actual)
                    assertEquals(if (name == "unknown") 0 else 1, trace.getInt(null))
                }
            }
        }
    }
}
