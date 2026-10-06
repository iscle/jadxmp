package com.jadxmp.pipeline.model

import com.jadxmp.input.*
import com.jadxmp.ir.attr.SourceAttributes
import com.jadxmp.ir.annotation.*
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.support.*
import kotlin.test.*

class ModelBuilderAnnotationsTest {
    private fun values(annotation: IrAnnotation) =
        assertIs<AnnotationMetadata.Ready<Map<String, IrAnnotationValue>>>(annotation.values).value

    private fun entry(metadata: AnnotationMetadata<IrAnnotationSet>?) =
        assertIs<AnnotationMetadata.Ready<IrAnnotation>>(assertIs<AnnotationMetadata.Ready<IrAnnotationSet>>(metadata).value.entries.single()).value

    private fun ann(values: Map<String, EncodedValue> = emptyMap(), visibility: AnnotationVisibility? = AnnotationVisibility.RUNTIME) =
        AnnotationData("Lexample/Tag;", visibility, values)

    @Test fun preservesAttachmentsParameterPositionsAndDefaultWithoutInputObjects() {
        val nested = ann(mapOf("flag" to EncodedValue(EncodedValueType.BOOLEAN, true)), null)
        val values = linkedMapOf(
            "number" to EncodedValue(EncodedValueType.INT, -253),
            "text" to EncodedValue(EncodedValueType.STRING, "quoted\"\\\n"),
            "type" to EncodedValue(EncodedValueType.TYPE, "[I"),
            "mode" to EncodedValue(EncodedValueType.ENUM, FakeFieldRef("Lexample/Mode;", "SECOND", "Lexample/Mode;")),
            "array" to EncodedValue(EncodedValueType.ARRAY, listOf(EncodedValue(EncodedValueType.LONG, Long.MIN_VALUE))),
            "nested" to EncodedValue(EncodedValueType.ANNOTATION, nested),
        )
        val method = FakeMethodData(FakeMethodRef("Lexample/Test;", "m", "I", listOf("J", "I")),
            annotations = listOf(ann()), parameterAnnotations = listOf(emptyList(), listOf(ann())),
            annotationDefault = EncodedValue(EncodedValueType.INT, 42))
        val field = FakeFieldData(FakeFieldRef("Lexample/Test;", "f", "I"), annotations = listOf(ann()))
        val cls = ModelBuilder.build(FakeCodeLoader(listOf(FakeClassData("Lexample/Test;", annotations = listOf(ann(values)), methods = listOf(method), fields = listOf(field))))).classes.single()
        val annotations = assertIs<AnnotationMetadata.Ready<IrAnnotationSet>>(cls[SourceAttributes.ANNOTATIONS]).value.entries
        val value = assertIs<AnnotationMetadata.Ready<IrAnnotation>>(annotations.single()).value
        assertEquals(IrAnnotationVisibility.RUNTIME, value.visibility)
        assertEquals(IrType.objectType("example.Tag"), value.type)
        assertEquals(values.keys.toList(), values(value).keys.toList())
        assertEquals(IrAnnotationValue.Primitive(-253, IrType.INT), values(value)["number"])
        assertEquals(IrAnnotationValue.ClassLiteral(IrType.array(IrType.INT)), values(value)["type"])
        assertEquals(IrAnnotationValue.EnumConstant(IrType.objectType("example.Mode") as IrType.Object, "SECOND"), values(value)["mode"])
        assertEquals(IrAnnotationValue.ArrayValue(listOf(IrAnnotationValue.Primitive(Long.MIN_VALUE, IrType.LONG))), values(value)["array"])
        assertNull(assertIs<IrAnnotationValue.Nested>(values(value)["nested"]).annotation.visibility)
        assertEquals(1, assertIs<AnnotationMetadata.Ready<IrAnnotationSet>>(cls.fields.single()[SourceAttributes.ANNOTATIONS]).value.entries.size)
        val params = assertIs<AnnotationMetadata.Ready<List<IrAnnotationSet>>>(cls.methods.single()[SourceAttributes.PARAMETER_ANNOTATIONS]).value
        assertEquals(listOf(0, 1), params.map { it.entries.size }) // positions, not register slots
        assertEquals(IrAnnotationValue.Primitive(42, IrType.INT), assertIs<AnnotationMetadata.Ready<IrAnnotationValue?>>(cls.methods.single()[SourceAttributes.ANNOTATION_DEFAULT]).value)
        values.clear()
        assertEquals(6, values(value).size)
    }

    @Test fun unsupportedAndMalformedValuesRemainFailuresInsteadOfAbsentMetadata() {
        for (encoded in listOf(EncodedValue.NULL, EncodedValue(EncodedValueType.INT, "wrong"), EncodedValue(EncodedValueType.TYPE, "Igarbage"))) {
            val cls = ModelBuilder.build(FakeCodeLoader(listOf(FakeClassData("LTest;", annotations = listOf(ann(mapOf("value" to encoded))))))).classes.single()
            assertIs<AnnotationMetadata.Unavailable>(entry(cls[SourceAttributes.ANNOTATIONS]).values)
        }
    }

    @Test fun cyclesAreBoundedAndHealthyBodiesStayAvailable() {
        val list = mutableListOf<EncodedValue>()
        list.add(EncodedValue(EncodedValueType.ARRAY, list))
        val method = FakeMethodData(FakeMethodRef("LTest;", "healthy", "V", emptyList()), codeReader = FakeCodeReader(0, emptyList()))
        val cls = ModelBuilder.build(FakeCodeLoader(listOf(FakeClassData("LTest;", annotations = listOf(ann(mapOf("value" to list.single()))), methods = listOf(method))))).classes.single()
        assertIs<AnnotationMetadata.Unavailable>(entry(cls[SourceAttributes.ANNOTATIONS]).values)
        assertNotNull(cls.methods.single()[com.jadxmp.pipeline.PipelineAttrs.CODE_READER])
        assertEquals(AnnotationMetadata.Ready(null), cls.methods.single()[SourceAttributes.ANNOTATION_DEFAULT])
    }
    @Test fun unsupportedSystemAnnotationsDoNotEraseRuntimeSiblings() {
        val system = AnnotationData("Ldalvik/annotation/EnclosingMethod;", AnnotationVisibility.SYSTEM,
            mapOf("value" to EncodedValue(EncodedValueType.METHOD, FakeMethodRef("LOuter;", "make", "V", emptyList()))))
        val cls = ModelBuilder.build(FakeCodeLoader(listOf(FakeClassData("LTest;", annotations = listOf(ann(), system, ann()))))).classes.single()
        val entries = assertIs<AnnotationMetadata.Ready<IrAnnotationSet>>(cls[SourceAttributes.ANNOTATIONS]).value.entries
        assertIs<AnnotationMetadata.Ready<IrAnnotation>>(entries[0])
        val systemAnnotation = assertIs<AnnotationMetadata.Ready<IrAnnotation>>(entries[1]).value
        assertTrue(assertIs<AnnotationMetadata.Unavailable>(systemAnnotation.values).reason.contains("METHOD"))
        assertEquals(IrType.objectType("dalvik.annotation.EnclosingMethod"), systemAnnotation.type)
        assertEquals(IrAnnotationVisibility.SYSTEM, systemAnnotation.visibility)
        assertIs<AnnotationMetadata.Ready<IrAnnotation>>(entries[2])
    }

    @Test fun rejectsDeclarationAndNestedVisibilityMismatch() {
        val mapper = AnnotationModel()
        assertIs<AnnotationMetadata.Unavailable>(assertIs<AnnotationMetadata.Ready<IrAnnotationSet>>(mapper.annotations { listOf(ann(visibility = null)) }).value.entries.single())
        assertIs<AnnotationMetadata.Unavailable>(mapper.default { EncodedValue(EncodedValueType.ANNOTATION, ann()) })
    }

    @Test fun retainsFailedProviderAndInvalidDefaultSeparatelyFromMissing() {
        val mapper = AnnotationModel()
        assertIs<AnnotationMetadata.Unavailable>(mapper.annotations { error("provider failed") })
        assertIs<AnnotationMetadata.Unavailable>(mapper.default { EncodedValue.NULL })
        assertEquals(AnnotationMetadata.Ready(null), mapper.default { null })
        assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
            mapper.annotations { throw kotlin.coroutines.cancellation.CancellationException("cancel") }
        }
    }

    @Test fun aggregateTextBudgetIncludesRepeatedNamesAcrossAttachments() {
        val mapper = AnnotationModel()
        val longName = "a".repeat(60_000)
        val annotation = ann(mapOf(longName to EncodedValue(EncodedValueType.INT, 1)))
        repeat(10) {
            assertIs<AnnotationMetadata.Ready<IrAnnotation>>(assertIs<AnnotationMetadata.Ready<IrAnnotationSet>>(mapper.annotations { listOf(annotation) }).value.entries.single())
        }
        var failures = 0
        repeat(10) {
            if (entry(mapper.annotations { listOf(annotation) }).values is AnnotationMetadata.Unavailable) failures++
        }
        assertTrue(failures > 0)
        assertEquals(AnnotationMetadata.Ready(null), mapper.default { null })
    }

    @Test fun parameterCountCannotSpillIntoNonexistentArgument() {
        assertIs<AnnotationMetadata.Unavailable>(AnnotationModel().parameters(1) { listOf(emptyList(), listOf(ann())) })
    }

    @Test fun buildRetentionIsNotPromotedToRuntime() {
        val metadata = AnnotationModel().annotations { listOf(ann(visibility = AnnotationVisibility.BUILD)) }
        val entry = assertIs<AnnotationMetadata.Ready<IrAnnotationSet>>(metadata).value.entries.single()
        assertEquals(IrAnnotationVisibility.BUILD, assertIs<AnnotationMetadata.Ready<IrAnnotation>>(entry).value.visibility)
    }

    @Test fun primitiveKindsSignedZeroAndNestedSnapshotsArePreserved() {
        val mapper = AnnotationModel()
        val cases = listOf(
            EncodedValue(EncodedValueType.BYTE, (-7).toByte()) to IrAnnotationValue.Primitive(-7, IrType.BYTE),
            EncodedValue(EncodedValueType.SHORT, (-253).toShort()) to IrAnnotationValue.Primitive(-253, IrType.SHORT),
            EncodedValue(EncodedValueType.CHAR, '\uffff') to IrAnnotationValue.Primitive(65535, IrType.CHAR),
            EncodedValue(EncodedValueType.FLOAT, -0.0f) to IrAnnotationValue.Primitive(Int.MIN_VALUE.toLong(), IrType.FLOAT),
            EncodedValue(EncodedValueType.DOUBLE, -0.0) to IrAnnotationValue.Primitive(Long.MIN_VALUE, IrType.DOUBLE),
        )
        for ((input, expected) in cases) assertEquals(AnnotationMetadata.Ready(expected), mapper.default { input })
        val array = mutableListOf(EncodedValue(EncodedValueType.STRING, "first"))
        val nested = linkedMapOf("values" to EncodedValue(EncodedValueType.ARRAY, array))
        val snapshot = mapper.default { EncodedValue(EncodedValueType.ANNOTATION, ann(nested, null)) }
        nested.clear()
        array.clear()
        val copied = assertIs<IrAnnotationValue.Nested>(assertIs<AnnotationMetadata.Ready<IrAnnotationValue?>>(snapshot).value)
        assertEquals(IrAnnotationValue.ArrayValue(listOf(IrAnnotationValue.Str("first"))), values(copied.annotation)["values"])
    }

    @Test fun failedAnnotationValueRetainsValidatedHeaderWithoutGuessingFromDiagnostics() {
        val values = mapOf("value" to EncodedValue(EncodedValueType.METHOD,
            FakeMethodRef("LOwner;", "m", "V", emptyList())))
        val metadata = AnnotationModel().annotations {
            listOf(ann(values), AnnotationData("not-a-descriptor", AnnotationVisibility.SYSTEM, emptyMap()))
        }
        val entries = assertIs<AnnotationMetadata.Ready<IrAnnotationSet>>(metadata).value.entries
        val preserved = assertIs<AnnotationMetadata.Ready<IrAnnotation>>(entries[0]).value
        assertEquals(IrType.objectType("example.Tag"), preserved.type)
        assertEquals(IrAnnotationVisibility.RUNTIME, preserved.visibility)
        assertIs<AnnotationMetadata.Unavailable>(preserved.values)
        assertIs<AnnotationMetadata.Unavailable>(entries[1])
    }

    @Test fun nestedFailureInvalidatesWholeValueMapAndDefaultWithoutErasingHealthySibling() {
        val nested = EncodedValue(EncodedValueType.ANNOTATION, ann(linkedMapOf(
            "valid" to EncodedValue(EncodedValueType.INT, 7),
            "invalid" to EncodedValue.NULL,
        ), visibility = null))
        val mapper = AnnotationModel()
        val metadata = mapper.annotations { listOf(ann(mapOf("nested" to nested)), ann()) }
        val entries = assertIs<AnnotationMetadata.Ready<IrAnnotationSet>>(metadata).value.entries
        val failed = assertIs<AnnotationMetadata.Ready<IrAnnotation>>(entries[0]).value
        assertIs<AnnotationMetadata.Unavailable>(failed.values)
        assertEquals(emptyMap(), values(assertIs<AnnotationMetadata.Ready<IrAnnotation>>(entries[1]).value))
        assertIs<AnnotationMetadata.Unavailable>(mapper.default { nested })
    }

}
