package com.jadxmp.codegen

import com.jadxmp.ir.annotation.*
import com.jadxmp.ir.attr.SourceAttributes
import com.jadxmp.ir.node.*
import com.jadxmp.ir.type.IrType
import kotlin.test.*

class AnnotationEmissionPlanTest {
    private fun cls(root: IrRoot, name: String, flags: Int = 0): IrClass =
        IrClass(root, name, flags, IrType.OBJECT,
            if (flags and 0x2000 != 0) listOf(IrType.objectType("java.lang.annotation.Annotation")) else emptyList()).also {
            root.addClass(it)
            // These value/default tests attach runtime tags, so use a valid runtime contract.
            if (flags and 0x2000 != 0) attach(it, annotation("java.lang.annotation.Retention", values =
                AnnotationMetadata.Ready(mapOf("value" to IrAnnotationValue.EnumConstant(
                    IrType.objectType("java.lang.annotation.RetentionPolicy") as IrType.Object, "RUNTIME")))))
        }
    private fun annotation(name: String, visibility: IrAnnotationVisibility = IrAnnotationVisibility.RUNTIME,
        values: AnnotationMetadata<Map<String, IrAnnotationValue>> = AnnotationMetadata.Ready(emptyMap())) =
        IrAnnotation(IrType.objectType(name) as IrType.Object, visibility, values)
    private fun attach(node: com.jadxmp.ir.attr.AttrNode, vararg annotations: IrAnnotation) {
        node[SourceAttributes.ANNOTATIONS] = AnnotationMetadata.Ready(IrAnnotationSet(annotations.map { AnnotationMetadata.Ready(it) }))
    }

    @Test fun sourceAttachmentsAreDistinctFromDeferredLegacyMetadataAndUnknownSystemFailures() {
        val root = IrRoot()
        val cls = cls(root, "Sample")
        attach(cls, annotation("Tag"), annotation("dalvik.annotation.EnclosingMethod", IrAnnotationVisibility.SYSTEM,
            AnnotationMetadata.Unavailable("unsupported METHOD")), annotation("vendor.Unknown", IrAnnotationVisibility.SYSTEM))
        val node = AnnotationEmissionPlan(root).node(cls)
        assertEquals(2, node.annotations.size)
        assertEquals("Tag", assertIs<AnnotationMetadata.Ready<IrAnnotation>>(node.annotations[0]).value.type.className)
        assertIs<AnnotationMetadata.Unavailable>(node.annotations[1])
        assertEquals("dalvik.annotation.EnclosingMethod", node.deferred.single().type.className)
        assertIs<AnnotationMetadata.Unavailable>(node.deferred.single().values)
    }

    @Test fun zeroParameterMetadataFailureIsNotLostByAnEmptyParameterLoop() {
        val root = IrRoot()
        val owner = cls(root, "Sample")
        val method = IrMethod(owner, "m", IrType.VOID, emptyList(), 1)
        method[SourceAttributes.PARAMETER_ANNOTATIONS] = AnnotationMetadata.Unavailable("unreadable parameters")
        val plan = AnnotationEmissionPlan(root).node(method)
        assertTrue(plan.problems.any { "unreadable parameters" in it })
        assertTrue(plan.requiresEmission)
    }

    @Test fun extraParameterMetadataIsAnExplicitFailure() {
        val root = IrRoot()
        val method = IrMethod(cls(root, "Sample"), "m", IrType.VOID, emptyList(), 1)
        method[SourceAttributes.PARAMETER_ANNOTATIONS] = AnnotationMetadata.Ready(listOf(IrAnnotationSet(emptyList())))
        assertTrue(AnnotationEmissionPlan(root).node(method).problems.any { "parameter count" in it })
    }

    @Test fun shortParameterMetadataIsDiagnosedWithoutInventingSyntheticPrefixPadding() {
        val root = IrRoot()
        val owner = cls(root, "Choice", 0x4001)
        val method = IrMethod(owner, "<init>", IrType.VOID,
            listOf(IrType.STRING, IrType.INT, IrType.LONG, IrType.STRING), 2)
        val raw = listOf(IrAnnotationSet(listOf(AnnotationMetadata.Ready(annotation("Mark")))), IrAnnotationSet(emptyList()))
        method[SourceAttributes.PARAMETER_ANNOTATIONS] = AnnotationMetadata.Ready(raw)
        val plan = AnnotationEmissionPlan(root).node(method)
        assertTrue(plan.problems.any { "source cannot preserve raw parameter mapping" in it })
        assertTrue(plan.parameters.isEmpty(), "do not emit tags at guessed shifted positions")
        assertSame(raw, assertIs<AnnotationMetadata.Ready<List<IrAnnotationSet>>>(method[SourceAttributes.PARAMETER_ANNOTATIONS]).value)
    }

    @Test fun zeroMemberGhostDefaultIsDiagnosedBeforeSourceSuppression() {
        val root = IrRoot()
        val owner = cls(root, "Empty", 0x2601)
        val nested = annotation("Empty", values = AnnotationMetadata.Ready(mapOf("ghost" to IrAnnotationValue.Primitive(7, IrType.INT))))
            .copy(visibility = null)
        attach(owner, annotation("dalvik.annotation.AnnotationDefault", IrAnnotationVisibility.SYSTEM,
            AnnotationMetadata.Ready(mapOf("value" to IrAnnotationValue.Nested(nested)))))
        val plan = AnnotationEmissionPlan(root).node(owner)
        assertTrue(plan.problems.any { "unknown annotation default" in it })
        assertTrue(plan.requiresEmission)
    }

    @Test fun descriptorSizedEnumMetadataKeepsBothSyntheticAndSourceIndices() {
        val root = IrRoot()
        val owner = cls(root, "Choice", 0x4001)
        for (annotatedIndex in listOf(0, 2, 3)) {
            val method = IrMethod(owner, "<init>", IrType.VOID,
                listOf(IrType.STRING, IrType.INT, IrType.LONG, IrType.STRING), 2)
            val raw = List(4) { index -> IrAnnotationSet(if (index == annotatedIndex)
                listOf(AnnotationMetadata.Ready(annotation("Mark"))) else emptyList()) }
            method[SourceAttributes.PARAMETER_ANNOTATIONS] = AnnotationMetadata.Ready(raw)
            val plan = AnnotationEmissionPlan(root).node(method)
            assertTrue(plan.problems.isEmpty())
            assertEquals(4, plan.parameters.size)
            assertEquals(listOf(annotatedIndex), plan.parameters.indices.filter { plan.parameters[it].isNotEmpty() })
            assertSame(raw, assertIs<AnnotationMetadata.Ready<List<IrAnnotationSet>>>(method[SourceAttributes.PARAMETER_ANNOTATIONS]).value)
        }
    }

    @Test fun ordinaryMethodCannotAcquireAnnotationDefaultSyntax() {
        val root = IrRoot()
        val method = IrMethod(cls(root, "Sample"), "m", IrType.INT, emptyList(), 1)
        method[SourceAttributes.ANNOTATION_DEFAULT] = AnnotationMetadata.Ready(IrAnnotationValue.Primitive(7, IrType.INT))
        assertIs<AnnotationMetadata.Unavailable>(AnnotationEmissionPlan(root).node(method).default)
    }

    @Test fun malformedValuesRemainAtomicAndDoNotEraseHealthyAttachment() {
        val root = IrRoot()
        val owner = cls(root, "Sample")
        attach(owner, annotation("Broken", values = AnnotationMetadata.Unavailable("bad value")), annotation("Healthy"))
        val plan = AnnotationEmissionPlan(root).node(owner)
        assertIs<AnnotationMetadata.Unavailable>(plan.annotations[0])
        assertEquals("Healthy", assertIs<AnnotationMetadata.Ready<IrAnnotation>>(plan.annotations[1]).value.type.className)
    }

    @Test fun loadedElementLookupRejectsAmbiguityAndUnknownMembers() {
        val root = IrRoot()
        val annotation = cls(root, "Tag", 0x2601)
        repeat(2) { annotation.methods.add(IrMethod(annotation, "value", IrType.INT, emptyList(), 0x401)) }
        val plan = AnnotationEmissionPlan(root)
        assertFailsWith<IllegalArgumentException> { plan.element(IrType.objectType("Tag") as IrType.Object, "value") }
        assertFailsWith<IllegalArgumentException> { plan.element(IrType.objectType("Tag") as IrType.Object, "missing") }
    }

    @Test fun loadedElementAndDefaultTypeMismatchesCannotBecomeDifferentSuccessfulMetadata() {
        val root = IrRoot()
        val declaration = cls(root, "Tag", 0x2601)
        val member = IrMethod(declaration, "value", IrType.INT, emptyList(), 0x401).also(declaration.methods::add)
        member[SourceAttributes.ANNOTATION_DEFAULT] = AnnotationMetadata.Ready(IrAnnotationValue.Str("wrong"))
        val owner = cls(root, "Sample")
        attach(owner, annotation("Tag", values = AnnotationMetadata.Ready(mapOf("value" to IrAnnotationValue.Str("wrong")))))
        val plan = AnnotationEmissionPlan(root)
        assertIs<AnnotationMetadata.Unavailable>(plan.node(owner).annotations.single())
        assertIs<AnnotationMetadata.Unavailable>(plan.node(member).default)
    }

    @Test fun missingRequiredLoadedElementIsAnExplicitFailureWithHealthySiblingRetained() {
        val root = IrRoot()
        val declaration = cls(root, "Tag", 0x2601)
        declaration.methods.add(IrMethod(declaration, "value", IrType.INT, emptyList(), 0x401))
        val owner = cls(root, "Sample")
        attach(owner, annotation("Tag"), annotation("Healthy"))
        val node = AnnotationEmissionPlan(root).node(owner)
        assertTrue(assertIs<AnnotationMetadata.Unavailable>(node.annotations[0]).reason.contains("missing required annotation element"))
        assertEquals("Healthy", assertIs<AnnotationMetadata.Ready<IrAnnotation>>(node.annotations[1]).value.type.className)
    }

    @Test fun omittedLoadedElementRequiresAValidTypedDefault() {
        for (default in listOf<AnnotationMetadata<IrAnnotationValue>>(
            AnnotationMetadata.Ready(IrAnnotationValue.Primitive(7, IrType.INT)),
            AnnotationMetadata.Ready(IrAnnotationValue.Str("wrong")),
            AnnotationMetadata.Unavailable("unreadable default"),
        )) {
            val root = IrRoot()
            val declaration = cls(root, "Tag", 0x2601)
            val method = IrMethod(declaration, "value", IrType.INT, emptyList(), 0x401).also(declaration.methods::add)
            method[SourceAttributes.ANNOTATION_DEFAULT] = default
            val owner = cls(root, "Sample")
            attach(owner, annotation("Tag"))
            val result = AnnotationEmissionPlan(root).node(owner).annotations.single()
            if (default is AnnotationMetadata.Ready && default.value is IrAnnotationValue.Primitive)
                assertIs<AnnotationMetadata.Ready<IrAnnotation>>(result)
            else assertIs<AnnotationMetadata.Unavailable>(result)
        }
    }

    @Test fun aggregateLimitsFailClosedWithoutPoisoningPreviouslyPlannedNodes() {
        val root = IrRoot()
        val first = cls(root, "First")
        val large = cls(root, "Large")
        attach(first, annotation("Tag"))
        attach(large, annotation("x".repeat(500)))
        val plan = AnnotationEmissionPlan(root, maxWork = 100)
        val before = plan.node(first)
        assertIs<AnnotationMetadata.Ready<IrAnnotation>>(before.annotations.single())
        assertTrue(plan.node(large).requiresEmission)
        assertTrue(plan.node(large).problems.any { "budget" in it } ||
            plan.node(large).annotations.any { it is AnnotationMetadata.Unavailable })
        assertSame(before, plan.node(first))
    }
}
