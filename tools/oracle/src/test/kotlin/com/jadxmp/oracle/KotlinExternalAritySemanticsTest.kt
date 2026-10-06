package com.jadxmp.oracle

import com.jadxmp.input.jvm.JvmInput
import com.jadxmp.pipeline.model.ExternalDeclarationModel
import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KotlinExternalAritySemanticsTest {
    @Test fun externalArityPreservesIdentityConstructorsStaticCallsAndClassLiterals(@TempDir dir: File) {
        JavaCompilation.compile(listOf(DecompiledClass("library.Twin", """
            package library;
            public class Twin<A,B> {
                public final A first;
                public final B second;
                public Twin(A first, B second) { this.first = first; this.second = second; }
                public Object echo(Object value) { return value; }
                public static String label() { return "library"; }
            }
        """.trimIndent()))).use { library ->
            assertTrue(library.result.success, library.result.diagnostics.toString())
            val declarations = ExternalDeclarationModel.build(listOf(JvmInput.readDeclaration(
                library.output.resolve("library/Twin.class").readBytes())))
            assertTrue(declarations.diagnostics.isEmpty(), declarations.diagnostics.toString())
            val fixture = dir.resolve("UseTwin.smali").apply { writeText("""
                .class public LUseTwin;
                .super Ljava/lang/Object;
                .method public static create(Ljava/lang/Object;Ljava/lang/Object;)Llibrary/Twin;
                    .locals 1
                    new-instance v0, Llibrary/Twin;
                    invoke-direct {v0, p0, p1}, Llibrary/Twin;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V
                    return-object v0
                .end method
                .method public static echo(Llibrary/Twin;Ljava/lang/Object;)Ljava/lang/Object;
                    .locals 1
                    invoke-virtual {p0, p1}, Llibrary/Twin;->echo(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v0
                    return-object v0
                .end method
                .method public static label()Ljava/lang/String;
                    .locals 1
                    invoke-static {}, Llibrary/Twin;->label()Ljava/lang/String;
                    move-result-object v0
                    return-object v0
                .end method
                .method public static kind()Ljava/lang/Class;
                    .locals 1
                    const-class v0, Llibrary/Twin;
                    return-object v0
                .end method
            """.trimIndent()) }
            val assembled = SmaliAssembler.assemble(fixture)
            assertTrue(assembled.ok, assembled.error)
            val result = KotlinJadxmpDecompiler(declarations.index).decompileKotlin(fixture.name, assembled.dex!!)
            assertEquals(1, result.classes.size, "External declarations must never become emitted program classes")
            assertEquals(0, result.reportedErrors, result.classes.toString())
            assertTrue(result.classes.single().source.contains("Twin<*, *>"))
            withCompiledClass(result.classes.single(), true, listOf(library.output)) { cls ->
                val companion = cls.getField("Companion").get(null)
                val owner = companion.javaClass
                val value = Any()
                val instance = owner.getMethod("create", Any::class.java, Any::class.java).invoke(companion, value, null)
                assertSame(value, instance.javaClass.getField("first").get(instance))
                assertNull(instance.javaClass.getField("second").get(instance))
                val echo = owner.getMethod("echo", instance.javaClass, Any::class.java)
                assertSame(value, echo.invoke(companion, instance, value))
                assertNull(echo.invoke(companion, instance, null))
                assertSame(instance.javaClass, owner.getMethod("kind").invoke(companion))
                assertEquals("library", owner.getMethod("label").invoke(companion))
            }
        }
    }
}
