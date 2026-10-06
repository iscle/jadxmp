package com.jadxmp.codegen

import com.jadxmp.ir.annotation.AnnotationMetadata
import com.jadxmp.ir.annotation.IrAnnotation
import com.jadxmp.ir.annotation.IrAnnotationSet
import com.jadxmp.ir.annotation.IrAnnotationValue
import com.jadxmp.ir.annotation.IrAnnotationVisibility
import com.jadxmp.ir.attr.SourceAttributes
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AnnotationPlanBudgetReviewTest {
    @Test fun enumFieldIndexChargesEveryRepeatedOwnerComparison() {
        val root = IrRoot()
        val owner = IrClass(root, "Enum" + "x".repeat(400), 0x4001, IrType.OBJECT).also(root::addClass)
        val type = IrType.objectType(owner.fullName) as IrType.Object
        repeat(4) { owner.fields.add(IrField(owner, "F$it", type, 0x4019)) }
        assertFailsWith<IllegalArgumentException> { AnnotationEmissionPlan(root, 1500).enumField(type, "F0") }
        assertSame(owner.fields.first(), AnnotationEmissionPlan(root, 10_000).enumField(type, "F0"))
    }

    @Test fun defaultWrapperChargesOwnerTextBeforeComparingIdentities() {
        val root = IrRoot()
        val owner = IrClass(root, "Tag" + "x".repeat(800), 0x2601, IrType.OBJECT, listOf(IrType.objectType("java.lang.annotation.Annotation"))).also(root::addClass)
        val nested = IrAnnotation(IrType.objectType(owner.fullName) as IrType.Object, null,
            AnnotationMetadata.Ready(emptyMap()))
        val wrapper = IrAnnotation(IrType.objectType("dalvik.annotation.AnnotationDefault") as IrType.Object,
            IrAnnotationVisibility.SYSTEM, AnnotationMetadata.Ready(mapOf("value" to IrAnnotationValue.Nested(nested))))
        owner[SourceAttributes.ANNOTATIONS] = AnnotationMetadata.Ready(IrAnnotationSet(listOf(AnnotationMetadata.Ready(wrapper))))
        val limited = AnnotationEmissionPlan(root, 100).node(owner)
        assertTrue(limited.problems.any { "planning budget exhausted" in it })
        assertTrue(AnnotationEmissionPlan(root, 10_000).node(owner).problems.isEmpty())
    }
}
