package com.jadxmp.codegen

import com.jadxmp.ir.annotation.*
import com.jadxmp.ir.attr.SourceAttributes
import com.jadxmp.ir.node.*
import com.jadxmp.ir.type.IrType
import kotlin.test.*

class AnnotationRetentionPlanTest {
    private fun annotation(name: String, visibility: IrAnnotationVisibility? = IrAnnotationVisibility.RUNTIME,
        values: Map<String, IrAnnotationValue> = emptyMap()) = IrAnnotation(
        IrType.objectType(name) as IrType.Object, visibility, AnnotationMetadata.Ready(values))
    private fun retention(policy: String, visibility: IrAnnotationVisibility = IrAnnotationVisibility.RUNTIME) =
        annotation("java.lang.annotation.Retention", visibility, mapOf("value" to IrAnnotationValue.EnumConstant(
            IrType.objectType("java.lang.annotation.RetentionPolicy") as IrType.Object, policy)))
    private fun attach(owner: IrClass, entries: List<IrAnnotation>) {
        owner[SourceAttributes.ANNOTATIONS] = AnnotationMetadata.Ready(IrAnnotationSet(entries.map { AnnotationMetadata.Ready(it) }))
    }
    private fun declaration(root: IrRoot, metadata: List<IrAnnotation>) = IrClass(root, "Tag", 0x2601, IrType.OBJECT,
        listOf(IrType.objectType("java.lang.annotation.Annotation"))).also { root.addClass(it); attach(it, metadata) }

    @Test fun loadedRetentionMustMatchEveryEncodedAttachmentVisibility() {
        for (policy in listOf("CLASS", "RUNTIME", "SOURCE")) for (visibility in listOf(IrAnnotationVisibility.BUILD, IrAnnotationVisibility.RUNTIME)) {
            val root = IrRoot()
            declaration(root, listOf(retention(policy)))
            val owner = IrClass(root, "Use", 1, IrType.OBJECT).also(root::addClass)
            attach(owner, listOf(annotation("Tag", visibility), annotation("ExternalHealthy")))
            val result = AnnotationEmissionPlan(root).node(owner).annotations
            val supported = policy == "CLASS" && visibility == IrAnnotationVisibility.BUILD ||
                policy == "RUNTIME" && visibility == IrAnnotationVisibility.RUNTIME
            if (supported) assertIs<AnnotationMetadata.Ready<IrAnnotation>>(result[0])
            else assertTrue(assertIs<AnnotationMetadata.Unavailable>(result[0]).reason.contains("retention"))
            assertIs<AnnotationMetadata.Ready<IrAnnotation>>(result[1])
        }
    }

    @Test fun absentRetentionMeansClassAndDoesNotAuthorizeRuntimeAttachment() {
        val root = IrRoot()
        declaration(root, emptyList())
        val owner = IrClass(root, "Use", 1, IrType.OBJECT).also(root::addClass)
        attach(owner, listOf(annotation("Tag", IrAnnotationVisibility.BUILD), annotation("Tag")))
        val result = AnnotationEmissionPlan(root).node(owner).annotations
        assertIs<AnnotationMetadata.Ready<IrAnnotation>>(result[0])
        assertIs<AnnotationMetadata.Unavailable>(result[1])
    }

    @Test fun malformedDuplicateAndUnavailableRetentionCannotInventAContract() {
        val malformed = listOf(
            listOf(retention("RUNTIME"), retention("CLASS")),
            listOf(retention("UNKNOWN")),
            listOf(retention("RUNTIME", IrAnnotationVisibility.BUILD)),
            listOf(annotation("java.lang.annotation.Retention")),
            listOf(annotation("java.lang.annotation.Retention", values = mapOf("value" to IrAnnotationValue.Str("RUNTIME")))),
        )
        for (metadata in malformed) {
            val root = IrRoot()
            val declaration = declaration(root, metadata)
            val owner = IrClass(root, "Use", 1, IrType.OBJECT).also(root::addClass)
            attach(owner, listOf(annotation("Tag")))
            val plan = AnnotationEmissionPlan(root)
            assertIs<AnnotationMetadata.Unavailable>(plan.node(owner).annotations.single())
            assertTrue(plan.node(declaration).problems.any { "retention" in it })
        }
        val root = IrRoot()
        val declaration = declaration(root, emptyList())
        declaration[SourceAttributes.ANNOTATIONS] = AnnotationMetadata.Unavailable("retention container unreadable")
        val owner = IrClass(root, "Use", 1, IrType.OBJECT).also(root::addClass)
        attach(owner, listOf(annotation("Tag")))
        assertIs<AnnotationMetadata.Unavailable>(AnnotationEmissionPlan(root).node(owner).annotations.single())
    }

    @Test fun nestedValuesAreNotIndependentRetentionAttachments() {
        val root = IrRoot()
        declaration(root, listOf(retention("SOURCE")))
        val owner = IrClass(root, "Use", 1, IrType.OBJECT).also(root::addClass)
        attach(owner, listOf(annotation("ExternalContainer", values = mapOf("value" to IrAnnotationValue.Nested(annotation("Tag", null))))))
        assertIs<AnnotationMetadata.Ready<IrAnnotation>>(AnnotationEmissionPlan(root).node(owner).annotations.single())
    }

    @Test fun absentExternalDeclarationRemainsAnUnprovedBoundary() {
        val root = IrRoot()
        val owner = IrClass(root, "Use", 1, IrType.OBJECT).also(root::addClass)
        attach(owner, listOf(annotation("External", IrAnnotationVisibility.BUILD), annotation("External")))
        // No invented external retention facts; this existing path is not a retention-parity proof.
        assertTrue(AnnotationEmissionPlan(root).node(owner).annotations.all { it is AnnotationMetadata.Ready })
    }
    @Test fun retentionCachingIsIdentityBasedAndStopsGrowingAfterExhaustion() {
        val root = IrRoot()
        val first = declaration(root, emptyList())
        val plan = AnnotationEmissionPlan(root, maxWork = 20)
        val proof = plan.retention(first)
        assertIs<AnnotationMetadata.Ready<AnnotationEmissionPlan.DeclaredRetention>>(proof)
        repeat(1000) { assertSame(proof, plan.retention(first)) }
        val long = declaration(root, listOf(annotation("x".repeat(1000))))
        assertIs<AnnotationMetadata.Unavailable>(plan.retention(long))
        val retained = plan.retainedRetentionProofs
        repeat(1000) {
            assertIs<AnnotationMetadata.Unavailable>(plan.retention(IrClass(root, "New$it", 0x2601, IrType.OBJECT)))
        }
        assertEquals(retained, plan.retainedRetentionProofs)
        assertSame(proof, plan.retention(first))
    }

}
