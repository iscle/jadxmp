package com.jadxmp.input.jvm

import com.jadxmp.input.InvalidGenericSignatureEncoding
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmGenericDeclarationsTest {
    @Test fun readsRawClassFieldAndMethodSignaturesWithoutReadingBodies() {
        val signatures = listOf("<T:Ljava/lang/Number;>Ljava/lang/Object;", "TT;", "(TT;)TT;")
        val pool = pool(signatures)
        val file = file(pool).copy(
            attributes = listOf(signature(1)),
            fields = listOf(JvmMember(1, "value", "Ljava/lang/Number;", listOf(signature(2)))),
            // Deliberately undecodable Code: metadata loading must never parse executable bodies.
            methods = listOf(JvmMember(1, "echo", "(Ljava/lang/Number;)Ljava/lang/Number;",
                listOf(signature(3), JvmAttribute("Code", 0, byteArrayOf(0))))),
        )
        val declaration = JvmDeclarationReader.read(file)
        assertEquals("LExample;", declaration.type)
        assertEquals(signatures[0], declaration.genericSignature)
        assertEquals(signatures[1], declaration.fields.single().genericSignature)
        assertEquals(signatures[2], declaration.methods.single().genericSignature)
        assertEquals(listOf("Ljava/lang/Number;"), declaration.methods.single().parameterTypes)
        assertEquals("Ljava/lang/Number;", declaration.methods.single().returnType)
    }

    @Test fun malformedSignatureEncodingCannotMasqueradeAsAbsentMetadata() {
        val pool = pool(listOf("Ljava/lang/Object;"))
        for (attributes in listOf(
            listOf(signature(1), signature(1)),
            listOf(JvmAttribute("Signature", 0, byteArrayOf())),
            listOf(JvmAttribute("Signature", 0, byteArrayOf(0, 1, 0))),
            listOf(signature(0)), listOf(signature(2)),
        )) {
            assertFailsWith<InvalidGenericSignatureEncoding> { JvmGenericSignatures.read(attributes, pool) }
        }
        assertNull(JvmGenericSignatures.read(emptyList(), pool))
    }

    @Test fun executableInputStillRejectsSignatureWhileDeclarationReaderAdmitsIt() {
        val input = file(pool(listOf("<T:Ljava/lang/Number;>Ljava/lang/Object;")))
            .copy(attributes = listOf(signature(1)))
        assertEquals("<T:Ljava/lang/Number;>Ljava/lang/Object;", JvmDeclarationReader.read(input).genericSignature)
        assertFailsWith<ByteReaderException> { JvmClassAdapter.adapt(input, "Example.class") }
    }

    @Test fun sharedLongSignaturesAreBoundedBeforeRepeatedModelParsing() {
        val pool = pool(listOf("L" + "x".repeat(65000) + ";"))
        val fields = List(200) { JvmMember(1, "field$it", "Ljava/lang/Object;", listOf(signature(1))) }
        val input = file(pool).copy(fields = fields)
        assertFailsWith<ByteReaderException> { JvmDeclarationReader.read(input) }
        assertFailsWith<ByteReaderException> { JvmClassAdapter.adapt(input, "Example.class") }
    }

    private fun pool(signatures: List<String>) = JvmConstantPool.read(ByteReader(ClassBytes().apply {
        u2(signatures.size + 1); signatures.forEach(::utf)
    }.bytes()), 61)
    private fun signature(index: Int) = JvmAttribute("Signature", 0, ClassBytes().apply { u2(index) }.bytes())
    private fun file(pool: JvmConstantPool) = ClassFile(0, 61, pool, 0x21, "Example", "java/lang/Object",
        emptyList(), emptyList(), emptyList(), emptyList())
}
