package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InvokeOverloadBindingSemanticsTest {
    @Test fun originalReferenceOverloadCheckBindsJavaDescriptor() = verify(false)
    @Test fun originalReferenceOverloadCheckBindsKotlinDescriptor() = verify(true)

    @Test fun loadedOverloadsPreserveJavaDispatchOrderAndArrayIdentity() = verifyCalls(false)
    @Test fun loadedOverloadsPreserveKotlinDispatchOrderAndArrayIdentity() = verifyCalls(true)

    private fun verifyCalls(kotlinOutput: Boolean) {
        val source = DecompiledClass("fixtures.OverloadCalls", """
            package fixtures;
            public class OverloadCalls {
                public static int trace;
                public static class Left {}
                public static class Right extends Left {}
                public static class Base {
                    public int choose(Left a, Left b) { return 3; }
                    public int choose(Right a, Right b) { return 9; }
                }
                public static class Child extends Base {
                    @Override public int choose(Left a, Left b) { return 30; }
                    public int choose(String a, String b) { return 90; }
                }
                public static class Box {
                    public int tag;
                    public Box(Object value) { tag = 5; }
                    public Box(String value) { tag = 50; }
                }
                public static class Delegating extends Box {
                    public Delegating(String value) { super((Object)value); }
                }
                public static int delegated(String value) { return new Delegating(value).tag; }
                public static int select(Object a, Object b) { return 7; }
                public static int select(String a, Object b) { return 70; }
                public static int select(Object a, String b) { return 700; }
                public static int select(String a, String b) { return 7000; }
                public static int arrays(Object[] value) { return value == null ? 8 : value.length; }
                public static int arrays(String[] value) { return 80; }
                public static int multi(String a, String b) { return select((Object)a, (Object)b); }
                public static int inherited() { return new Child().choose((Left)new Right(), (Left)new Right()); }
                public static int array(String[] value) { return arrays((Object[])value); }
                public static int constructor(String value) { return new Box((Object)value).tag; }
                public static Right mark(int tag, int divisor) {
                    trace = trace * 10 + tag;
                    int quotient = 12 / divisor;
                    return new Right();
                }
                public static int effect(boolean absent, int divisor) {
                    Base receiver = absent ? null : new Child();
                    return receiver.choose((Left)mark(1, 1), (Left)mark(2, divisor));
                }
                public static int reverse(boolean absent, int divisor) {
                    Base receiver = absent ? null : new Child();
                    return receiver.choose((Left)mark(2, divisor), (Left)mark(1, 1));
                }
            }
        """.trimIndent())
        val fixture = JavaCheckFixture(listOf(source), source.fullName)
        val dex = JavaFixtureCompiler.dex(fixture)
        val reference = ReferenceDecompiler().decompile("calls.dex", dex)
        val candidate = if (kotlinOutput) KotlinJadxmpDecompiler().decompileKotlin("calls.dex", dex)
            else JadxmpDecompiler().decompile("calls.dex", dex)
        assertTrue(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP), candidate.classes.joinToString { it.source })
        val cases = buildList<Pair<String, List<Any?>>>() {
            for (a in listOf(null, "a")) for (b in listOf(null, "b")) add("multi" to listOf(a, b))
            add("inherited" to emptyList())
            for (value in listOf(null, arrayOf("a", "b"))) add("array" to listOf(value))
            for (value in listOf(null, "value")) {
                add("constructor" to listOf(value))
                add("delegated" to listOf(value))
            }
            for (absent in listOf(false, true)) for (divisor in listOf(0, 3)) {
                add("effect" to listOf(absent, divisor))
                add("reverse" to listOf(absent, divisor))
            }
        }
        fun measure(classes: List<DecompiledClass>, kotlin: Boolean): List<Pair<Any?, Int>> {
            val results = mutableListOf<Pair<Any?, Int>>()
            withCompiledClasses(classes, kotlin) { loader ->
                val cls = loader.loadClass(source.fullName)
                val target = if (kotlin) cls.getField("Companion").get(null) else null
                val owner = target?.javaClass ?: cls
                val trace = cls.getDeclaredField("trace").apply { isAccessible = true }
                for ((name, arguments) in cases) {
                    trace.setInt(null, 0)
                    val method = owner.methods.single { it.name == name }
                    val result = try { method.invoke(target, *arguments.toTypedArray()) }
                        catch (failure: java.lang.reflect.InvocationTargetException) { failure.targetException.javaClass.name }
                    results.add(result to trace.getInt(null))
                }
            }
            return results
        }
        val original = measure(listOf(source), false)
        assertEquals(original, measure(reference.classes, false), "pinned reference")
        assertEquals(original, measure(candidate.classes, kotlinOutput), candidate.classes.joinToString("\n") { it.source })
    }

    @Test fun javaBindingPreservesOriginalMissingAncestorFailure() {
        val observed = missingAncestorOutcomes(kotlinOutput = false)
        assertEquals("java.lang.NoClassDefFoundError:linkage/Parent", observed[0])
        assertEquals("returned:1", observed[1], "pinned reference still loses this linkage failure")
        assertEquals(observed[0], observed[2], "candidate Java must retain the original failure")
    }

    @Test fun kotlinAndReferenceStillLoseMissingAncestorLinkageFailure() {
        // A visible, pre-existing limitation, not execution parity: before source binding both
        // candidates returned 2; Kotlin now selects the correct overload but still misses NCDFE.
        val observed = missingAncestorOutcomes(kotlinOutput = true)
        assertEquals("java.lang.NoClassDefFoundError:linkage/Parent", observed[0])
        assertEquals("returned:1", observed[1], "pinned reference limitation")
        assertEquals("returned:1", observed[2], "candidate Kotlin linkage limitation remains")
    }

    private fun missingAncestorOutcomes(kotlinOutput: Boolean): List<String> {
        // This call-site test compiles generated Caller against unchanged library helpers. The full
        // generated inheritance/override fixture above separately tests emitted helper definitions.
        val directory = java.nio.file.Files.createTempDirectory("jadxmp-overload-linkage").toFile()
        try {
            val sources = listOf(
                DecompiledClass("linkage.Parent", "package linkage; public class Parent {}"),
                DecompiledClass("linkage.Child", "package linkage; public class Child extends Parent {}"),
                DecompiledClass("linkage.Target", """package linkage; public class Target {
                    public static int pick(Parent value) { return 1; }
                    public static int pick(Child value) { return 2; }
                }"""),
                DecompiledClass("linkage.Caller", """package linkage; public class Caller {
                    public static int call(Object value) { Child child = (Child) value; return Target.pick((Parent)child); }
                }"""),
            )
            val files = sources.map { source -> directory.resolve(source.fullName.replace('.', '/') + ".java").apply {
                parentFile.mkdirs(); writeText(source.source)
            } }
            assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", directory.path, *files.map { it.path }.toTypedArray()))
            val missing = directory.resolve("linkage/Parent.class")
            val parentBytes = missing.readBytes()
            val dex = JavaFixtureCompiler.dex(JavaCheckFixture(sources, "linkage.Caller"))
            val candidate = if (kotlinOutput) KotlinJadxmpDecompiler().decompileKotlin("linkage.dex", dex)
                else JadxmpDecompiler().decompile("linkage.dex", dex)
            val reference = ReferenceDecompiler().decompile("linkage.dex", dex)
            val originalCaller = sources.last()
            val outcomes = mutableListOf<String>()
            for ((caller, isKotlin) in listOf(originalCaller to false,
                reference.classes.single { it.fullName == "linkage.Caller" } to false,
                candidate.classes.single { it.fullName == "linkage.Caller" } to kotlinOutput)) {
                assertTrue(!caller.source.contains("JADXMP ERROR"), caller.source)
                withCompiledClasses(listOf(caller), isKotlin, listOf(directory)) { loader ->
                    assertTrue(missing.delete())
                    try {
                        // The original JVM verifier resolves this superclass relation before call
                        // execution, even for null. Preserve that observed linkage failure; do not
                        // claim that source widening casts are always erased by the compiler.
                        val observed = try {
                            val cls = loader.loadClass("linkage.Caller")
                            val target = if (isKotlin) cls.getField("Companion").get(null) else null
                            val owner = target?.javaClass ?: cls
                            "returned:" + owner.getMethod("call", Any::class.java).invoke(target, null)
                        } catch (failure: NoClassDefFoundError) {
                            failure.javaClass.name + ":" + failure.message
                        }
                        outcomes.add(observed)
                    } finally { missing.writeBytes(parentBytes) }
                }
            }
            return outcomes
        } finally { directory.deleteRecursively() }
    }

    private fun verify(kotlinOutput: Boolean) {
        val reference = Corpus.root().parentFile.resolve("reference/jadx")
        PinnedReference.verify(reference)
        val source = reference.resolve("jadx-core/src/test/java/jadx/tests/integration/invoke/TestOverloadedMethodInvoke.java").readText()
        val sample = UpstreamJavaExtractor.extract(source).sample!!
        assertEquals(OriginalFixtureStatus.CHECK_PASSED, UpstreamJavaInventory.validate(sample).status)
        val fixture = JavaCheckFixture(listOf(sample.source), sample.checkClass, UpstreamJavaInventory.fixtureClasspath)
        val dex = JavaFixtureCompiler.dex(fixture)
        val oracle = ReferenceDecompiler().decompile("overloads.dex", dex)
        assertEquals(ExecuteCheckResult.Evaluated(true), AccuracySignals.executeCheck(oracle.classes, fixture))
        val candidate = if (kotlinOutput) KotlinJadxmpDecompiler().decompileKotlin("overloads.dex", dex)
            else JadxmpDecompiler().decompile("overloads.dex", dex)
        assertTrue(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP))
        val output = candidate.classes.joinToString("\n") { it.source }
        val check = if (kotlinOutput) KotlinAccuracySignals.executeCheck(candidate.classes, fixture)
            else AccuracySignals.executeCheck(candidate.classes, fixture)
        assertEquals(ExecuteCheckResult.Evaluated(true), check, output)
    }
}
