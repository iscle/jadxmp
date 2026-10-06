package com.jadxmp.codegen

import com.jadxmp.ir.annotation.*
import com.jadxmp.ir.attr.SourceAttributes
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import kotlin.test.*

class PlatformAnnotationRetentionTest {
    private fun annotation(name: String, visibility: IrAnnotationVisibility) = IrAnnotation(
        IrType.objectType(name) as IrType.Object, visibility, AnnotationMetadata.Ready(emptyMap()))
    private fun attach(owner: IrClass, values: List<IrAnnotation>) {
        owner[SourceAttributes.ANNOTATIONS] = AnnotationMetadata.Ready(IrAnnotationSet(values.map { AnnotationMetadata.Ready(it) }))
    }

    @Test fun sourceRetainedPlatformAnnotationCannotPreserveAnEncodedAttachment() {
        for (visibility in listOf(IrAnnotationVisibility.RUNTIME, IrAnnotationVisibility.BUILD)) {
            val root = IrRoot()
            val owner = IrClass(root, "Use", 1, IrType.OBJECT).also(root::addClass)
            val raw = annotation("java.lang.Override", visibility)
            val healthy = annotation("external.Healthy", IrAnnotationVisibility.RUNTIME)
            attach(owner, listOf(raw, healthy))
            val before = owner[SourceAttributes.ANNOTATIONS]
            val plan = AnnotationEmissionPlan(root).node(owner)
            assertEquals("annotation java.lang.Override: encoded $visibility visibility conflicts with SOURCE retention",
                assertIs<AnnotationMetadata.Unavailable>(plan.annotations[0]).reason)
            assertSame(healthy, assertIs<AnnotationMetadata.Ready<IrAnnotation>>(plan.annotations[1]).value)
            assertSame(before, owner[SourceAttributes.ANNOTATIONS], "the raw encoded attachment must remain inspectable")
        }
    }

    @Test fun loadedDeclarationMetadataTakesPrecedenceOverPlatformFallback() {
        val root = IrRoot()
        val declaration = IrClass(root, "java.lang.Override", 0x2601, IrType.OBJECT,
            listOf(IrType.objectType("java.lang.annotation.Annotation"))).also(root::addClass)
        val retention = IrAnnotation(IrType.objectType("java.lang.annotation.Retention") as IrType.Object,
            IrAnnotationVisibility.RUNTIME, AnnotationMetadata.Ready(mapOf("value" to IrAnnotationValue.EnumConstant(
                IrType.objectType("java.lang.annotation.RetentionPolicy") as IrType.Object, "RUNTIME"))))
        attach(declaration, listOf(retention))
        val owner = IrClass(root, "Use", 1, IrType.OBJECT).also(root::addClass)
        attach(owner, listOf(annotation("java.lang.Override", IrAnnotationVisibility.RUNTIME)))
        assertIs<AnnotationMetadata.Ready<IrAnnotation>>(AnnotationEmissionPlan(root).node(owner).annotations.single())
        declaration[SourceAttributes.ANNOTATIONS] = AnnotationMetadata.Unavailable("original metadata unreadable")
        assertTrue(assertIs<AnnotationMetadata.Unavailable>(AnnotationEmissionPlan(root).node(owner).annotations.single())
            .reason.contains("unreadable"))
    }

    @Test fun otherExternalNamesDoNotAcquireInventedPlatformRetention() {
        val root = IrRoot()
        val owner = IrClass(root, "Use", 1, IrType.OBJECT).also(root::addClass)
        attach(owner, listOf(annotation("external.Override", IrAnnotationVisibility.RUNTIME),
            annotation("android.support.annotation.NonNull", IrAnnotationVisibility.BUILD)))
        assertTrue(AnnotationEmissionPlan(root).node(owner).annotations.all { it is AnnotationMetadata.Ready })
    }
}
