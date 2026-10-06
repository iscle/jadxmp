package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KotlinPlainInheritanceSemanticsTest {
    @Test fun constructorsAndLoadedOverridesPreserveDispatchNullsAndEffects() {
        val source = DecompiledClass("fixtures.PlainInheritance", """
            package fixtures;
            public class PlainInheritance {
                public static int trace;
                public static class ObjectBase {
                    public Object value;
                    public int tag;
                    public ObjectBase(Object value) { this.value = value; tag = 5; }
                    public ObjectBase(String value) { this.value = value; tag = 50; }
                }
                public static class Delegating extends ObjectBase {
                    public Delegating(String value) { super((Object) value); }
                }
                public static class DelegatingArray extends ObjectBase {
                    public DelegatingArray(Object[] value) { super((Object) value); }
                }
                public static int delegatedTag(String value) { return new Delegating(value).tag; }
                public static Object delegatedIdentity(String value) { return new Delegating(value).value; }
                public static Object delegatedArray(Object[] value) { return new DelegatingArray(value).value; }
                public interface Contract { int choose(Object value); }
                public static class Base implements Contract {
                    public Object value;
                    public Base(int n, Object value) {
                        trace = trace * 10 + 1;
                        if (n < 0) throw new IllegalArgumentException();
                        this.value = value;
                    }
                    public int choose(Object value) { return 3; }
                    public Object echo(Object value) { return value; }
                    protected int protectedValue() { return 4; }
                }
                public static class Child extends Base {
                    public Child(int n, Object value) { super(n, value); trace = trace * 10 + 2; }
                    public Child(Object value) { this(5, value); trace = trace * 10 + 3; }
                    @Override public final int choose(Object value) { return 30; }
                    @Override public Object echo(Object value) { return value; }
                    @Override protected int protectedValue() { return 40; }
                    public int exposed() { return protectedValue(); }
                }
                public static class Grandchild extends Child {
                    public Grandchild(Object value) { super(value); trace = trace * 10 + 4; }
                }
                public static int run(int n) {
                    trace = 0;
                    Base value = new Child(n, null);
                    return value.choose(null);
                }
                public static Object identity(Object value) { return new Grandchild(value).echo(value); }
                public static int chainExpression() {
                    trace = 0;
                    Child value = new Grandchild(null);
                    return trace * 100 + value.exposed();
                }
                public static int chain() {
                    trace = 0;
                    Child value = new Grandchild(null);
                    return value.exposed();
                }
            }
        """.trimIndent())
        val fixture = JavaCheckFixture(listOf(source), source.fullName)
        val dex = JavaFixtureCompiler.dex(fixture)
        val reference = ReferenceDecompiler().decompile("inheritance.dex", dex)
        val candidate = KotlinJadxmpDecompiler().decompileKotlin("inheritance.dex", dex)
        val output = candidate.classes.joinToString("\n") { it.source }
        assertTrue(AccuracySignals.noErrors(candidate, ErrorMarkers.JADXMP), output)
        fun measure(classes: List<DecompiledClass>, kotlin: Boolean): List<Any?> {
            val results = mutableListOf<Any?>()
            withCompiledClasses(classes, kotlin) { loader ->
                val cls = loader.loadClass(source.fullName)
                val target = if (kotlin) cls.getField("Companion").get(null) else null
                val owner = target?.javaClass ?: cls
                val trace = cls.getDeclaredField("trace").apply { isAccessible = true }
                for (n in listOf(-1, 0, 7)) {
                    results.add(try { owner.getMethod("run", Int::class.javaPrimitiveType).invoke(target, n) }
                        catch (failure: java.lang.reflect.InvocationTargetException) { failure.targetException.javaClass.name })
                    results.add(trace.getInt(null))
                }
                results.add(owner.getMethod("chain").invoke(target))
                results.add(trace.getInt(null))
                val identity = owner.getMethod("identity", Any::class.java)
                results.add(identity.invoke(target, null) == null)
                val value = Any()
                results.add(identity.invoke(target, value) === value)
                val child = loader.loadClass("fixtures.PlainInheritance\$Child")
                results.add(java.lang.reflect.Modifier.isFinal(child.getMethod("choose", Any::class.java).modifiers))
                results.add(owner.getMethod("chainExpression").invoke(target))
                results.add(trace.getInt(null))
                for (value in listOf(null, "value")) {
                    results.add(owner.getMethod("delegatedTag", String::class.java).invoke(target, value))
                    results.add(owner.getMethod("delegatedIdentity", String::class.java).invoke(target, value) === value)
                }
                val arrayMethod = owner.getMethod("delegatedArray", arrayOfNulls<Any>(0).javaClass)
                results.add(arrayMethod.invoke(target, null) == null)
                val array = arrayOf("a", "b")
                results.add(arrayMethod.invoke(target, array as Any) === array)
            }
            return results
        }
        val original = measure(listOf(source), false)
        assertEquals(123440, original[11])
        assertEquals(1234, original[12])
        // The pinned oracle moves allocation after reading trace: (trace * 100) + new Grandchild(null).exposed().
        // Keep the exact wrong-reference result visible; candidate execution must still match the original.
        val pinnedExpected = original.toMutableList().also { it[11] = 40 }
        assertEquals(pinnedExpected, measure(reference.classes, false), "pinned reference: " + reference.classes.joinToString("\n") { it.source })
        assertEquals(original, measure(candidate.classes, true), output)
    }
}
