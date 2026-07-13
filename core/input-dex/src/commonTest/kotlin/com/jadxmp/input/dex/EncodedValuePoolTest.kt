package com.jadxmp.input.dex

import com.jadxmp.input.AnnotationData
import com.jadxmp.input.EncodedValue
import com.jadxmp.input.EncodedValueType
import com.jadxmp.input.FieldRef
import com.jadxmp.input.MethodProto
import com.jadxmp.input.MethodRef
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Exercises the pool-resolving encoded-value kinds against a hand-built DEX with real pools. */
class EncodedValuePoolTest {

    private val b = DexBuilder()
    private val sHi = b.addString("hi")
    private val tString = b.addType("Ljava/lang/String;")
    private val tFoo = b.addType("LFoo;")
    private val sField = b.addString("field")
    private val fFoo = b.addField(tFoo, tString, sField)
    private val proto = b.addProto(tString)
    private val sMethod = b.addString("m")
    private val mFoo = b.addMethod(tFoo, proto, sMethod)
    private val tAnn = b.addType("LAnn;")
    private val sValue = b.addString("value")

    private val parser = EncodedValueParser(Dex(b.build(), "t", 0))

    private fun parse(vararg v: Int): EncodedValue = parser.parseValue(ByteReader(ByteArray(v.size) { v[it].toByte() }))

    @Test
    fun parsesString() {
        assertEquals(EncodedValue(EncodedValueType.STRING, "hi"), parse(0x17, sHi))
    }

    @Test
    fun parsesType() {
        assertEquals(EncodedValue(EncodedValueType.TYPE, "Ljava/lang/String;"), parse(0x18, tString))
    }

    @Test
    fun parsesFieldAndEnum() {
        val field = parse(0x19, fFoo)
        assertEquals(EncodedValueType.FIELD, field.type)
        val ref = assertIs<FieldRef>(field.value)
        assertEquals("LFoo;", ref.declaringClassType)
        assertEquals("field", ref.name)
        assertEquals("Ljava/lang/String;", ref.type)

        assertEquals(EncodedValueType.ENUM, parse(0x1b, fFoo).type)
    }

    @Test
    fun parsesMethod() {
        val method = parse(0x1a, mFoo)
        assertEquals(EncodedValueType.METHOD, method.type)
        val ref = assertIs<MethodRef>(method.value)
        assertEquals("LFoo;", ref.declaringClassType)
        assertEquals("m", ref.name)
        assertEquals("Ljava/lang/String;", ref.returnType)
    }

    @Test
    fun parsesMethodType() {
        val mt = parse(0x15, proto)
        assertEquals(EncodedValueType.METHOD_TYPE, mt.type)
        assertEquals("Ljava/lang/String;", assertIs<MethodProto>(mt.value).returnType)
    }

    @Test
    fun parsesNestedAnnotation() {
        // encoded_annotation: type=LAnn;, one element "value" = int 42
        val value = parse(0x1d, tAnn, 0x01, sValue, 0x04, 0x2a)
        assertEquals(EncodedValueType.ANNOTATION, value.type)
        val ann = assertIs<AnnotationData>(value.value)
        assertEquals("LAnn;", ann.annotationType)
        assertEquals(EncodedValue(EncodedValueType.INT, 42), ann.values["value"])
    }

    @Test
    fun methodHandleWithoutSectionFailsGracefully() {
        // No method_handle section in this DEX; a VALUE_METHOD_HANDLE must degrade, not crash.
        assertFailsWith<ByteReaderException> { parse(0x16, 0x00) }
    }
}
