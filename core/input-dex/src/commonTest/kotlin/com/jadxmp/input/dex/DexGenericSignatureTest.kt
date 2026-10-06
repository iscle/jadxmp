package com.jadxmp.input.dex

import com.jadxmp.input.InvalidGenericSignatureEncoding
import com.jadxmp.input.AnnotationData
import com.jadxmp.input.AnnotationVisibility
import com.jadxmp.input.EncodedValue
import com.jadxmp.input.EncodedValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse

class DexGenericSignatureTest {
    private fun annotation(vararg values: EncodedValue) = AnnotationData("Ldalvik/annotation/Signature;",
        AnnotationVisibility.SYSTEM, mapOf("value" to EncodedValue(EncodedValueType.ARRAY, values.toList())))
    private fun string(value: String) = EncodedValue(EncodedValueType.STRING, value)

    @Test fun piecesRetainTheirExactContents() {
        assertEquals("Ljava/util/List<TT;>;", DexGenericSignature.read(listOf(annotation(string("Ljava/util/List<"), string("TT;>;")))))
        assertNull(DexGenericSignature.read(emptyList()))
    }
    @Test fun malformedDuplicateAndOversizedPayloadsAreExplicit() {
        assertFailsWith<IllegalArgumentException> { DexGenericSignature.read(listOf(annotation(EncodedValue.NULL))) }
        val a = annotation(string("TT;"))
        assertFailsWith<IllegalArgumentException> { DexGenericSignature.read(listOf(a, a)) }
        assertFailsWith<IllegalArgumentException> { DexGenericSignature.read(listOf(a.copy(visibility = AnnotationVisibility.RUNTIME))) }
        assertFailsWith<IllegalArgumentException> { DexGenericSignature.read(listOf(annotation(string("T".repeat(65536))))) }
    }
    @Test fun fragmentationWorkLimitIsNotMalformedMetadata() {
        val pieces = List(65536) { string("") } + string("Ljava/lang/Object;")
        val payload = AnnotationData("Ldalvik/annotation/Signature;", AnnotationVisibility.SYSTEM,
            mapOf("value" to EncodedValue(EncodedValueType.ARRAY, pieces)))
        val failure = assertFailsWith<IllegalArgumentException> { DexGenericSignature.read(listOf(payload)) }
        assertFalse(failure is InvalidGenericSignatureEncoding)
    }
}
