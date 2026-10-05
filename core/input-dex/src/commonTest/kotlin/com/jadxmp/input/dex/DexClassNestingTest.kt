package com.jadxmp.input.dex

import com.jadxmp.input.AnnotationData
import com.jadxmp.input.AnnotationVisibility
import com.jadxmp.input.ClassNesting
import com.jadxmp.input.EncodedValue
import com.jadxmp.input.EncodedValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class DexClassNestingTest {
    @Test
    fun enclosingClassAndInnerNameNormalizeWithoutDiscardingAnnotations() {
        val annotations = listOf(
            annotation("EnclosingClass", mapOf("value" to EncodedValue(EncodedValueType.TYPE, "Lexample/Outer;"))),
            annotation("InnerClass", mapOf("name" to EncodedValue(EncodedValueType.STRING, "Member"),
                "accessFlags" to EncodedValue(EncodedValueType.INT, 9))),
        )
        val data = data(annotations)
        assertEquals(ClassNesting.Nested("Lexample/Outer;", innerName = "Member"), data.nesting)
        assertEquals(9, data.innerAccessFlags)
        assertSame(annotations, data.annotations)
    }

    @Test
    fun enclosingMethodPreservesMethodIdentityAndAnonymousName() {
        val method = DexMethodRef("Lexample/Outer;", "run", "V", emptyList())
        val data = data(listOf(
            annotation("EnclosingMethod", mapOf("value" to EncodedValue(EncodedValueType.METHOD, method))),
            annotation("InnerClass", mapOf("name" to EncodedValue.NULL, "accessFlags" to EncodedValue(EncodedValueType.INT, 0))),
        ))
        val nesting = data.nesting as ClassNesting.Nested
        assertSame(method, nesting.enclosingMethod)
        assertEquals("Lexample/Outer;", nesting.enclosingClassType)
        assertNull(nesting.innerName)
        assertEquals(0, data.innerAccessFlags)
    }

    @Test
    fun flagsOnlyMetadataAndAbsentAnnotationsLeaveEnclosureUnknown() {
        val flagsOnly = data(listOf(annotation("InnerClass", mapOf("accessFlags" to EncodedValue(EncodedValueType.INT, 9)))))
        assertNull(flagsOnly.nesting)
        assertEquals(9, flagsOnly.innerAccessFlags)
        val absent = data(emptyList())
        assertNull(absent.nesting)
        assertNull(absent.innerAccessFlags)
    }

    private fun annotation(name: String, values: Map<String, EncodedValue>) =
        AnnotationData("Ldalvik/annotation/$name;", AnnotationVisibility.SYSTEM, values)

    private fun data(annotations: List<AnnotationData>) = DexClassData(
        "Lexample/Outer\$Member;", 1, "Ljava/lang/Object;", emptyList(), null,
        emptyList(), emptyList(), annotations, "fixture.dex",
    )
}
