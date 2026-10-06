package com.jadxmp.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KotlinConstructorNameLanguageContractTest {
    @Test fun scopedSuppressionPreservesJvmNamesAndDispatch() {
        val source = DecompiledClass("probe.Collision", """
            package probe
            open class Collision {
                class A {
                    @Suppress("CONFLICTING_OVERLOADS") constructor()
                }
                @Suppress("CONFLICTING_OVERLOADS")
                open fun A(): A = Collision.A()
                fun call(): A = this.A()
                fun construct(): A = Collision.A()
                class Child : Collision() {
                    override fun A(): A = Collision.A()
                }
            }
        """.trimIndent())
        val external = DecompiledClass("probe.External", """
            package probe
            class External : Collision() {
                override fun A(): Collision.A = Collision.A()
            }
        """.trimIndent())
        withCompiledClasses(listOf(source, external), true) { loader ->
            val cls = loader.loadClass(source.fullName)
            val externalOwner = loader.loadClass(external.fullName)
            assertEquals("probe.Collision\$A", cls.getMethod("call").invoke(externalOwner.getConstructor().newInstance()).javaClass.name)
            val child = cls.declaredClasses.single { it.simpleName == "Child" }.getConstructor().newInstance()
            assertEquals("probe.Collision\$A", cls.getMethod("call").invoke(child).javaClass.name)
            assertEquals("A", cls.getMethod("A").name)
        }
    }
    @Test fun unknownAncestorCallableReallyCapturesAnUnprovedConstructorAlias() {
        val target = DecompiledClass("unknown.Target", """
            package unknown;
            public class Target {
                public static class A {}
                public A A() { return new A(); }
            }
        """.trimIndent())
        val base = DecompiledClass("unknown.Base", """
            package unknown;
            public class Base {
                public static kotlin.jvm.functions.Function0<Target.A> JvmA = () -> null;
            }
        """.trimIndent())
        val stdlib = java.io.File(Unit::class.java.protectionDomain.codeSource.location.toURI())
        JavaCompilation.compile(listOf(target, base), listOf(stdlib)).use { library ->
            org.junit.jupiter.api.Assertions.assertTrue(library.result.success, library.result.diagnostics.toString())
            val caller = DecompiledClass("probe.Caller", """
                package probe
                import unknown.Target.A as JvmA
                class Caller : unknown.Base() {
                    fun make(): JvmA = JvmA()
                    fun explicit(): JvmA = unknown.Target.A()
                }
            """.trimIndent())
            withCompiledClass(caller, true, listOf(library.output)) { cls ->
                // Negative language control: compiler success alone does not prove constructor dispatch.
                val failure = org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                    cls.getMethod("make").invoke(cls.getConstructor().newInstance())
                }
                org.junit.jupiter.api.Assertions.assertTrue(failure.targetException is NullPointerException)
                assertEquals("unknown.Target\$A", cls.getMethod("explicit").invoke(cls.getConstructor().newInstance()).javaClass.name)
            }
        }
    }

}
