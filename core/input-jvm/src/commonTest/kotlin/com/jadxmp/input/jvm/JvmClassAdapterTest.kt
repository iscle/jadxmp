package com.jadxmp.input.jvm

import com.jadxmp.input.ClassNesting
import com.jadxmp.input.Opcode
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JvmClassAdapterTest {
    @Test fun exposesNativeDescriptorsAndLazilyNormalizesPrimitiveBodies() {
        val cls = JvmClassAdapter.adapt(file(methods = listOf(method("value", "(I)I", code(1, 1, 0x1a, 0xac)))), "Example.class")
        assertEquals("LExample;", cls.type)
        assertEquals("Ljava/lang/Object;", cls.superType)
        assertEquals(ClassNesting.TopLevel, cls.nesting)
        assertEquals("Example.class", cls.inputFileName)
        val method = cls.methods.single()
        assertEquals(listOf("I"), method.ref.parameterTypes)
        assertEquals("I", method.ref.returnType)
        assertEquals("LExample;", method.ref.declaringClassType)
        val reader = method.codeReader!!
        assertSame(reader, method.codeReader)
        val opcodes = mutableListOf<Opcode>()
        reader.visitInstructions { it.decode(); opcodes += it.opcode }
        assertEquals(listOf(Opcode.MOVE, Opcode.MOVE, Opcode.RETURN), opcodes)
        assertTrue(cls.disassemble().contains("iload_0"))
    }

    @Test fun unsupportedBodiesRemainIsolatedAndCacheTheirOriginalFailure() {
        val cls = JvmClassAdapter.adapt(file(methods = listOf(
            method("broken", "()V", code(0, 1, 0x01, 0x57, 0xb1)),
            method("good", "()I", code(0, 1, 0x04, 0xac)),
        )), "Example.class")
        val bad = cls.methods[0]
        val first = assertFailsWith<ByteReaderException> { bad.codeReader }
        assertSame(first, assertFailsWith<ByteReaderException> { bad.codeReader })
        assertTrue(first.message.orEmpty().contains("unsupported"))
        assertTrue(cls.methods[1].codeReader != null)
        assertTrue(cls.disassemble().contains("aconst_null"))
    }

    @Test fun abstractAndNativeBodiesAreAbsentButMalformedCodeIsDiagnosedPerMethod() {
        val members = listOf(
            JvmMember(0x401, "abstractValue", "()I", emptyList()),
            JvmMember(0x109, "nativeValue", "()I", emptyList()),
            JvmMember(9, "missing", "()I", emptyList()),
            method("duplicate", "()V", code(0, 0, 0xb1), code(0, 0, 0xb1)),
            JvmMember(0x109, "nativeWithCode", "()V", listOf(code(0, 0, 0xb1))),
        )
        val cls = JvmClassAdapter.adapt(file(methods = members, flags = 0x421), "Example.class")
        assertNull(cls.methods[0].codeReader)
        assertNull(cls.methods[1].codeReader)
        for (member in cls.methods.drop(2)) assertFailsWith<ByteReaderException> { member.codeReader }
    }

    @Test fun unsupportedSemanticMethodMetadataIsNotSilentlyErased() {
        for (attribute in listOf("Signature", "Exceptions", "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations",
            "RuntimeVisibleParameterAnnotations", "RuntimeInvisibleTypeAnnotations", "AnnotationDefault", "MethodParameters")) {
            val cls = JvmClassAdapter.adapt(file(methods = listOf(
                method("annotated", "()I", code(0, 1, 0x04, 0xac), JvmAttribute(attribute, 0, byteArrayOf())),
                method("good", "()I", code(0, 1, 0x05, 0xac)),
            )), "Example.class")
            val failure = assertFailsWith<ByteReaderException>(attribute) { cls.methods[0].codeReader }
            assertTrue(failure.message.orEmpty().contains(attribute))
            assertTrue(cls.methods[1].codeReader != null)
        }
    }

    @Test fun synchronizedMethodsRetainJvmFlagsAndPrimitiveBodies() {
        for (flags in listOf(0x21, 0x29)) {
            val synchronized = JvmMember(flags, "locked", "()I", listOf(code(1, 1, 0x04, 0xac)))
            val cls = JvmClassAdapter.adapt(file(methods = listOf(synchronized,
                method("good", "()I", code(0, 1, 0x05, 0xac)))), "Example.class")
            assertEquals(flags, cls.methods[0].accessFlags)
            assertTrue(cls.methods[0].codeReader != null)
            assertTrue(cls.methods[1].codeReader != null)
        }
    }

    @Test fun nestedCodeTypeAnnotationsFailOnlyTheirOwningMethod() {
        for (name in listOf("RuntimeVisibleTypeAnnotations", "RuntimeInvisibleTypeAnnotations")) {
            val pool = JvmConstantPool.read(ByteReader(ClassBytes().apply {
                u2(3); utf(name); utf("LTag;")
            }.bytes()), 61)
            val annotated = JvmAttribute("Code", 100, ClassBytes().apply {
                u2(1); u2(1); u4(4); u1(0x04); u1(0x3b); u1(0x1a); u1(0xac); u2(0)
                u2(1); u2(1); u4(16); u2(1) // one type annotation
                u1(0x40); u2(1); u2(2); u2(2); u2(0) // local variable target, local0 at [2,4)
                u1(0); u2(2); u2(0) // empty type path, @Tag without elements
            }.bytes())
            val cls = JvmClassAdapter.adapt(file(methods = listOf(
                method("annotated", "()I", annotated), method("good", "()I", code(0, 1, 0x05, 0xac)),
            )).copy(constants = pool), "Example.class")
            val failure = assertFailsWith<ByteReaderException> { cls.methods[0].codeReader }
            assertTrue(failure.message.orEmpty().contains(name))
            assertTrue(cls.methods[1].codeReader != null)
        }
    }

    @Test fun rejectsUnsupportedClassMetadataIllegalFormsAndDuplicateDeclarations() {
        for (attribute in listOf("Signature", "Record", "PermittedSubclasses", "NestHost", "RuntimeVisibleAnnotations")) {
            assertFailsWith<ByteReaderException>(attribute) {
                JvmClassAdapter.adapt(file(attributes = listOf(JvmAttribute(attribute, 0, byteArrayOf()))), "Example.class")
            }
        }
        for (flags in listOf(0x8000, 0x431, 0x201, 0x2021)) {
            assertFailsWith<ByteReaderException> { JvmClassAdapter.adapt(file(flags = flags), "Example.class") }
        }
        val method = method("same", "()V", code(0, 0, 0xb1))
        assertFailsWith<ByteReaderException> { JvmClassAdapter.adapt(file(methods = listOf(method, method)), "Example.class") }
    }

    @Test fun preservesStaticNonFinalConstantsAndRejectsUnsupportedFieldMetadata() {
        val pool = JvmConstantPool.read(ByteReader(ClassBytes().apply { u2(2); u1(3); u4(42) }.bytes()), 61)
        val field = JvmMember(9, "number", "I", listOf(JvmAttribute("ConstantValue", 0, byteArrayOf(0, 1))))
        val cls = JvmClassAdapter.adapt(file().copy(constants = pool, fields = listOf(field)), "Example.class")
        assertEquals(42, cls.fields.single().constValue!!.value)
        assertEquals(9, cls.fields.single().accessFlags)
        assertFailsWith<ByteReaderException> {
            val unsupported = field.copy(attributes = listOf(JvmAttribute("Signature", 0, byteArrayOf())))
            JvmClassAdapter.adapt(file().copy(fields = listOf(unsupported)), "Example.class")
        }
    }

    @Test fun publicEntryParsesActualClassBytesWithoutDexConversion() {
        val bytes = ClassBytes().apply {
            u4(0xCAFEBABEL); u2(0); u2(61); u2(8)
            utf("Example"); u1(7); u2(1); utf("java/lang/Object"); u1(7); u2(3)
            utf("value"); utf("()I"); utf("Code")
            u2(0x21); u2(2); u2(4); u2(0); u2(0); u2(1)
            u2(9); u2(5); u2(6); u2(1); u2(7); u4(14)
            u2(1); u2(0); u4(2); u1(0x04); u1(0xac); u2(0); u2(0); u2(0)
        }.bytes()
        val cls = JvmInput.loadClass("Example.class", bytes).classes.single()
        assertEquals("LExample;", cls.type)
        assertTrue(cls.methods.single().codeReader != null)
        assertFailsWith<ByteReaderException> { JvmInput.loadClass("bad.class", byteArrayOf()) }
    }

    @Test fun cancellationDoesNotPoisonBodyCacheAndSuccessfulNullIsCached() {
        var calls = 0
        val body = JvmMethodBody {
            if (++calls == 1) throw CancellationException("cancelled")
            null
        }
        assertFailsWith<CancellationException> { body.read() }
        assertNull(body.read())
        assertNull(body.read())
        assertEquals(2, calls)
    }

    @Test fun boundsRepeatedLongDeclarationWorkBeforeMaterializingDescriptors() {
        val descriptor = "(L" + "x".repeat(65000) + ";)V"
        val members = List(200) { JvmMember(0x109, "method$it", descriptor, emptyList()) }
        val failure = assertFailsWith<ByteReaderException> { JvmClassAdapter.adapt(file(methods = members), "Example.class") }
        assertTrue(failure.message.orEmpty().contains("declaration work limit"))
    }

    private fun method(name: String, descriptor: String, vararg attributes: JvmAttribute) =
        JvmMember(9, name, descriptor, attributes.toList())
    private fun file(methods: List<JvmMember> = emptyList(), flags: Int = 0x21,
        attributes: List<JvmAttribute> = emptyList(),
    ) = ClassFile(0, 61, JvmConstantPool.read(ByteReader(byteArrayOf(0, 1)), 61), flags,
        "Example", "java/lang/Object", emptyList(), emptyList(), methods, attributes)
    private fun code(locals: Int, stack: Int, vararg instructions: Int) = JvmAttribute("Code", 100,
        ClassBytes().apply {
            u2(stack); u2(locals); u4(instructions.size.toLong()); instructions.forEach(::u1); u2(0); u2(0)
        }.bytes())
}
