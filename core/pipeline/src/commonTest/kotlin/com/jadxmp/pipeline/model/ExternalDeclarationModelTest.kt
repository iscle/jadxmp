package com.jadxmp.pipeline.model

import com.jadxmp.input.ClassDeclarationData
import com.jadxmp.input.ClassNesting
import com.jadxmp.input.FieldDeclarationData
import com.jadxmp.input.MethodDeclarationData
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExternalDeclarationModelTest {
    @Test fun validatesGenericMetadataWithoutImportingProgramBodies() {
        val input = declaration().copy(
            genericSignature = "<T:Ljava/lang/Number;>Ljava/lang/Object;",
            fields = listOf(FieldDeclarationData("value", "Ljava/lang/Number;", 1, "TT;")),
            methods = listOf(MethodDeclarationData("get", emptyList(), "Ljava/lang/Number;", 1, "()TT;")),
        )
        val result = ExternalDeclarationModel.build(listOf(input))
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val cls = assertNotNull(result.index.findClass("example.Box"))
        assertEquals("T", cls.parameters.single().name)
        assertEquals(IrType.objectType("java.lang.Number"), cls.methods.single().returnType)
        assertNotNull(cls.methods.single().signature)
        assertNotNull(cls.fields.single().sourceType)
    }

    @Test fun invalidOptionalMetadataIsReportedAsRecoveryWithErasedDeclarationRetained() {
        val input = declaration().copy(fields = listOf(FieldDeclarationData("value", "Z", 9, "Lint;")))
        val result = ExternalDeclarationModel.build(listOf(input))
        assertTrue(result.diagnostics.isNotEmpty())
        assertTrue(result.diagnostics.all { it.kind == ExternalDeclarationDiagnosticKind.RECOVERY })
        assertEquals(IrType.BOOLEAN, result.index.findClass("example.Box")!!.fields.single().erasedType)
    }

    @Test fun duplicateDeclarationsAreRejectedBeforeNameResolution() {
        assertFailsWith<IllegalArgumentException> { ExternalDeclarationModel.build(listOf(declaration(), declaration())) }
    }

    @Test fun validButOverBudgetMetadataIsAnErrorAndExcludedFromTheIndex() {
        val signature = "[".repeat(65) + "Ljava/lang/String;"
        val input = declaration().copy(fields = listOf(FieldDeclarationData("array", signature, 1, signature)))
        val result = ExternalDeclarationModel.build(listOf(input))
        assertTrue(result.diagnostics.any { it.kind == ExternalDeclarationDiagnosticKind.ERROR })
        assertEquals(null, result.index.findClass("example.Box"))
    }

    @Test fun deferredConstructorMetadataCannotEnterValidatedClasspathDeclarations() {
        val input = declaration().copy(methods = listOf(MethodDeclarationData(
            "<init>", listOf("I", "Ljava/lang/String;"), "V", 1, "(Ljava/lang/String;)V",
        )))
        val result = ExternalDeclarationModel.build(listOf(input))
        assertTrue(result.diagnostics.any {
            it.kind == ExternalDeclarationDiagnosticKind.ERROR && it.message.contains("synthetic-parameter proof")
        })
        assertEquals(null, result.index.findClass("example.Box"))
    }

    @Test fun genericOuterCaptureIsNotExportedAsAnUnboundInnerSignature() {
        val outer = declaration().copy(genericSignature = "<T:Ljava/lang/Object;>Ljava/lang/Object;")
        val inner = declaration().copy(type = "Lexample/Box\$Inner;",
            nesting = ClassNesting.Nested(outer.type, innerName = "Inner"),
            methods = listOf(MethodDeclarationData("get", emptyList(), "Ljava/lang/Object;", 1, "()TT;")))
        val result = ExternalDeclarationModel.build(listOf(inner, outer))
        assertEquals(null, result.index.findClass("example.Box\$Inner"))
        assertTrue(result.diagnostics.any { it.kind == ExternalDeclarationDiagnosticKind.ERROR && it.message.contains("enclosing generic scope") })
    }

    private fun declaration() = ClassDeclarationData("Lexample/Box;", 1, "Ljava/lang/Object;", emptyList(), emptyList(), emptyList())
}
