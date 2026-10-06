package com.jadxmp.codegen

import com.jadxmp.ir.annotation.*
import com.jadxmp.ir.attr.SourceAttributes
import com.jadxmp.ir.node.*
import com.jadxmp.ir.type.IrType
import kotlin.test.*

class AnnotationAttachmentTrackerTest {
    @Test fun parametersAndDefaultsNeedSeparateCoverageAndRollbackRestoresAllSlots() {
        val root = IrRoot()
        val cls = IrClass(root, "Tag", 0x2601, IrType.OBJECT, listOf(IrType.objectType("java.lang.annotation.Annotation"))).also(root::addClass)
        val method = IrMethod(cls, "value", IrType.INT, emptyList(), 0x401).also(cls.methods::add)
        val annotation = IrAnnotation(IrType.objectType("Mark") as IrType.Object, IrAnnotationVisibility.RUNTIME,
            AnnotationMetadata.Ready(emptyMap()))
        method[SourceAttributes.ANNOTATIONS] = AnnotationMetadata.Ready(IrAnnotationSet(listOf(AnnotationMetadata.Ready(annotation))))
        method[SourceAttributes.ANNOTATION_DEFAULT] = AnnotationMetadata.Ready(IrAnnotationValue.Primitive(7, IrType.INT))
        val tracker = AnnotationAttachmentTracker(AnnotationEmissionPlan(root))
        assertEquals(2, tracker.unhandled(cls).size)
        tracker.declaration(method)
        assertEquals(1, tracker.unhandled(cls).size)
        val checkpoint = tracker.checkpoint()
        tracker.default(method)
        assertTrue(tracker.unhandled(cls).isEmpty())
        tracker.restore(checkpoint)
        assertEquals(1, tracker.unhandled(cls).size)
        tracker.restore(0)
        assertEquals(2, tracker.unhandled(cls).size)
    }

    @Test fun markingOneParameterCannotHideAnotherOrAnUnreadableZeroParameterContainer() {
        val root = IrRoot()
        val cls = IrClass(root, "Sample", 1, IrType.OBJECT).also(root::addClass)
        val method = IrMethod(cls, "m", IrType.VOID, listOf(IrType.INT, IrType.INT), 1).also(cls.methods::add)
        val ann = AnnotationMetadata.Ready(IrAnnotation(IrType.objectType("Mark") as IrType.Object,
            IrAnnotationVisibility.RUNTIME, AnnotationMetadata.Ready(emptyMap())))
        method[SourceAttributes.PARAMETER_ANNOTATIONS] = AnnotationMetadata.Ready(List(2) { IrAnnotationSet(listOf(ann)) })
        val tracker = AnnotationAttachmentTracker(AnnotationEmissionPlan(root))
        tracker.parameter(method, 0)
        assertEquals(1, tracker.unhandled(cls).size)
        assertTrue(tracker.unhandled(cls).single().reason.contains("parameter 1"))
        tracker.parameter(method, 1)
        assertTrue(tracker.unhandled(cls).isEmpty())
    }

    @Test fun auditFindsAttachmentsOnHiddenNestedMembersAndBoundsCycles() {
        val root = IrRoot()
        val cls = IrClass(root, "Sample", 1, IrType.OBJECT).also(root::addClass)
        val inner = IrClass(root, "Sample\$Hidden", 1, IrType.OBJECT).also(root::addClass)
        cls.innerClasses.add(inner)
        inner.innerClasses.add(cls)
        val field = IrField(inner, "field", IrType.INT, 1).also(inner.fields::add)
        field[SourceAttributes.ANNOTATIONS] = AnnotationMetadata.Unavailable("malformed annotation")
        val tracker = AnnotationAttachmentTracker(AnnotationEmissionPlan(root))
        assertEquals(field, tracker.unhandled(cls).single().node)
        tracker.declaration(field)
        assertTrue(tracker.unhandled(cls).isEmpty())
    }
}
