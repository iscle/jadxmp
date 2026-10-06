package com.jadxmp.oracle

import com.jadxmp.input.jvm.JvmInput
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.model.ExternalDeclarationDiagnosticKind
import com.jadxmp.pipeline.model.ExternalDeclarationModel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URLClassLoader

/** Compiled real class files feed metadata only; executable input/source reconstruction are unchanged. */
class NativeDeclarationCatalogTest {
    @Test fun classFileMetadataPreservesErasedIdentityLexicalBoundsAndOriginalExecution() {
        val source = DecompiledClass("CatalogBox", """
            public class CatalogBox<T extends Number, U extends T> {
                public U value;
                public <T extends String> U echo(U input) { return input; }
                public java.util.List<? extends Number>[] arrays;
            }
        """.trimIndent())
        JavaCompilation.compile(listOf(source), release = 11).use { original ->
            assertTrue(original.result.success, original.result.diagnostics.toString())
            val declaration = JvmInput.readDeclaration(original.output.resolve("CatalogBox.class").readBytes())
            val result = ExternalDeclarationModel.build(listOf(declaration))
            assertEquals(emptyList<Any>(), result.diagnostics)
            val indexed = result.index.findClass("CatalogBox")!!
            assertEquals(listOf("T", "U"), indexed.parameters.map { it.name })
            assertEquals(listOf(IrType.objectType("java.lang.Number"), IrType.objectType("java.lang.Number")),
                indexed.parameters.map { it.erasedType })
            val echo = indexed.methods.single { it.name == "echo" }
            assertEquals(listOf(IrType.objectType("java.lang.Number")), echo.argumentTypes)
            assertEquals(IrType.objectType("java.lang.Number"), echo.returnType)
            assertEquals(IrType.STRING, echo.signature!!.parameters.single().erasedType)
            assertEquals(IrType.typeVariable("U"), echo.signature!!.returnType)
            assertEquals(IrType.typeVariable("U"), indexed.fields.single { it.name == "value" }.sourceType)
            val array = indexed.fields.single { it.name == "arrays" }.sourceType as IrType.ArrayType
            assertEquals("java.util.List", (array.element as IrType.Object).className)
            URLClassLoader(arrayOf(original.output.toURI().toURL()), javaClass.classLoader).use { loader ->
                val cls = loader.loadClass("CatalogBox")
                val instance = cls.getConstructor().newInstance()
                val method = cls.getMethod("echo", Number::class.java)
                for (value in listOf(null, 1000)) assertSame(value, method.invoke(instance, value))
            }
        }
    }

    @Test fun externalGenericOuterScopesAreExplicitlyUnsupportedWhileStaticSiblingsWork() {
        val source = DecompiledClass("CatalogOuter", """
            public class CatalogOuter<T> {
                public class Inner { public T get() { return null; } }
                public class Shadow<T extends String> { public T get() { return null; } }
                public static class Static<U> { public U get() { return null; } }
            }
        """.trimIndent())
        JavaCompilation.compile(listOf(source), release = 11).use { original ->
            assertTrue(original.result.success, original.result.diagnostics.toString())
            val declarations = original.output.walkTopDown().filter { it.extension == "class" }
                .map { JvmInput.readDeclaration(it.readBytes()) }.toList()
            val result = ExternalDeclarationModel.build(declarations)
            assertNotNull(result.index.findClass("CatalogOuter"))
            assertNotNull(result.index.findClass("CatalogOuter\$Static"))
            for (name in listOf("CatalogOuter\$Inner", "CatalogOuter\$Shadow")) {
                assertNull(result.index.findClass(name))
                assertTrue(result.diagnostics.any { it.owner == name && it.kind == ExternalDeclarationDiagnosticKind.ERROR &&
                    it.message.contains("enclosing generic scope") }, result.diagnostics.toString())
            }
        }
    }
}
