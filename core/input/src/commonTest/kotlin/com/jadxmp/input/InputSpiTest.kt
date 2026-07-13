package com.jadxmp.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guards the small invariants of the input SPI's value types. */
class InputSpiTest {

    @Test
    fun accessFlagsHasBit() {
        val flags = AccessFlags.PUBLIC or AccessFlags.STATIC
        assertTrue(AccessFlags.has(flags, AccessFlags.PUBLIC))
        assertTrue(AccessFlags.has(flags, AccessFlags.STATIC))
        assertFalse(AccessFlags.has(flags, AccessFlags.PRIVATE))
    }

    @Test
    fun methodHandleTypeFieldClassification() {
        assertTrue(MethodHandleType.STATIC_GET.isField)
        assertTrue(MethodHandleType.INSTANCE_PUT.isField)
        assertFalse(MethodHandleType.INVOKE_STATIC.isField)
        assertFalse(MethodHandleType.INVOKE_INTERFACE.isField)
    }

    @Test
    fun encodedNullSingleton() {
        assertEquals(EncodedValueType.NULL, EncodedValue.NULL.type)
        assertNull(EncodedValue.NULL.value)
    }

    @Test
    fun annotationDefaultValueLookup() {
        val ann = AnnotationData(
            annotationType = "Lcom/example/Ann;",
            visibility = AnnotationVisibility.RUNTIME,
            values = mapOf("value" to EncodedValue(EncodedValueType.INT, 42)),
        )
        assertEquals(EncodedValue(EncodedValueType.INT, 42), ann.defaultValue)
    }

    @Test
    fun listCodeLoaderEmptiness() {
        assertTrue(ListCodeLoader(emptyList()).isEmpty)
        // a non-empty loader carrying a trivial stub is not empty
        val loader = ListCodeLoader(listOf(StubClass))
        assertFalse(loader.isEmpty)
        assertEquals(1, loader.classes.size)
    }

    private object StubClass : ClassData {
        override val type = "Lx;"
        override val accessFlags = 0
        override val superType: String? = null
        override val interfaces = emptyList<String>()
        override val sourceFile: String? = null
        override val fields = emptyList<FieldData>()
        override val methods = emptyList<MethodData>()
        override val annotations = emptyList<AnnotationData>()
        override val inputFileName = "x"
        override fun disassemble() = ""
    }
}
