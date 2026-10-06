package com.jadxmp.input.jvm

import com.jadxmp.input.ClassNesting
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JvmClassMetadataTest {
    @Test fun repeatedAliasesReuseLongMetadataNames() {
        val count = 5000
        val prefix = "a".repeat(4096)
        val bytes = ClassBytes().apply {
            u2(count + 5)
            utf("Current"); utf(prefix + "A"); utf(prefix + "B"); utf("Inner")
            repeat(count) { u1(7); u2(2 + it % 2) }
        }.bytes()
        val payload = ClassBytes().apply {
            u2(count)
            repeat(count) { u2(5 + it); u2(if (it % 2 == 0) 6 else 5); u2(4); u2(1) }
        }.bytes()
        val file = ClassFile(0, 61, JvmConstantPool.read(ByteReader(bytes), 61), 1,
            "Current", "java/lang/Object", emptyList(), emptyList(), emptyList(),
            listOf(JvmAttribute("InnerClasses", 0, payload)))
        assertEquals(ClassNesting.TopLevel, JvmClassMetadata.read(file).nesting)
    }

    @Test fun equivalentClassPoolAliasesCanHaveMatchingInnerEntries() {
        val result = JvmClassMetadata.read(file(attribute("InnerClasses", 2, 2, 4, 5, 9, 14, 4, 5, 9)))
        assertEquals(ClassNesting.Nested("Lexample/Outer;", innerName = "Inner"), result.nesting)
        assertEquals(9, result.innerAccessFlags)
    }

    @Test fun readsOwnMemberEntryInsteadOfAnotherReferencedInnerClass() {
        val metadata = JvmClassMetadata.read(file(
            attribute("SourceFile", 6),
            attribute("InnerClasses", 2, 13, 4, 5, 1, 2, 4, 5, 10),
        ))
        assertEquals("Outer.java", metadata.sourceFile)
        assertEquals(ClassNesting.Nested("Lexample/Outer;", innerName = "Inner"), metadata.nesting)
        assertEquals(10, metadata.innerAccessFlags)
    }

    @Test fun localClassPreservesEnclosingMethodAndAnonymousClassInitializerOwner() {
        val local = JvmClassMetadata.read(file(
            attribute("InnerClasses", 1, 2, 0, 5, 0), attribute("EnclosingMethod", 4, 9),
        ))
        val nesting = local.nesting as ClassNesting.Nested
        assertEquals("Lexample/Outer;", nesting.enclosingClassType)
        val method = nesting.enclosingMethod!!
        assertEquals("run", method.name)
        assertEquals(listOf("I"), method.parameterTypes)
        assertEquals("V", method.returnType)
        assertEquals("Inner", nesting.innerName)
        val initializer = JvmClassMetadata.read(file(
            attribute("InnerClasses", 1, 2, 0, 0, 0), attribute("EnclosingMethod", 4, 0),
        )).nesting as ClassNesting.Nested
        assertEquals("Lexample/Outer;", initializer.enclosingClassType)
        assertNull(initializer.enclosingMethod)
        assertNull(initializer.innerName)
    }

    @Test fun accessControlNestAndDollarNameDoNotInventLexicalEnclosure() {
        val result = JvmClassMetadata.read(file(attribute("NestHost", 4), attribute("NestMembers", 1, 13)))
        assertEquals(ClassNesting.TopLevel, result.nesting)
        assertNull(result.innerAccessFlags)
        assertNull(result.sourceFile)
    }

    @Test fun olderInnerEntryWithoutEnclosingMetadataRemainsUnknown() {
        val result = JvmClassMetadata.read(file(attribute("InnerClasses", 1, 2, 0, 5, 0)).copy(majorVersion = 48))
        assertNull(result.nesting)
        assertEquals(0, result.innerAccessFlags)
    }

    @Test fun validatesLengthsIndicesTagsDuplicatesAndContradictions() {
        val cases = listOf(
            listOf(attribute("SourceFile", 2)), // class entry, not UTF8
            listOf(attribute("SourceFile", 6, 6)),
            listOf(attribute("SourceFile", 6), attribute("SourceFile", 6)),
            listOf(attribute("InnerClasses", 1, 2, 4, 5)), // truncated row
            listOf(attribute("InnerClasses", 0, 2)), // trailing bytes
            listOf(attribute("InnerClasses", 1, 0, 4, 5, 0)),
            listOf(attribute("InnerClasses", 1, 2, 2, 5, 0)), // self enclosure
            listOf(attribute("InnerClasses", 1, 2, 4, 0, 0)), // anonymous member >= 51
            listOf(attribute("InnerClasses", 2, 2, 4, 5, 0, 2, 4, 5, 0)),
            listOf(attribute("InnerClasses", 2, 2, 4, 5, 9, 14, 4, 5, 1)), // contradictory alias flags
            listOf(attribute("InnerClasses", 0), attribute("InnerClasses", 0)),
            listOf(attribute("EnclosingMethod", 4)),
            listOf(attribute("EnclosingMethod", 4, 2)), // wrong tag
            listOf(attribute("EnclosingMethod", 4, 11)), // field descriptor
            listOf(attribute("EnclosingMethod", 4, 0), attribute("EnclosingMethod", 4, 0)),
            listOf(attribute("InnerClasses", 1, 2, 4, 5, 0), attribute("EnclosingMethod", 13, 0)),
        )
        for ((index, attributes) in cases.withIndex()) {
            assertFailsWith<ByteReaderException>("case $index") { JvmClassMetadata.read(file(*attributes.toTypedArray())) }
        }
    }

    private fun attribute(name: String, vararg words: Int) =
        JvmAttribute(name, 0, ClassBytes().apply { words.forEach(::u2) }.bytes())

    private fun file(vararg attributes: JvmAttribute): ClassFile {
        val pool = ClassBytes().apply {
            u2(15)
            utf("example/Outer\$Inner"); u1(7); u2(1) // 1,2
            utf("example/Outer"); u1(7); u2(3) // 3,4
            utf("Inner"); utf("Outer.java") // 5,6
            utf("run"); utf("(I)V"); u1(12); u2(7); u2(8) // 7,8,9
            utf("I"); u1(12); u2(7); u2(10) // 10,11
            utf("example/Other"); u1(7); u2(12) // 12,13
            u1(7); u2(1) // 14: a distinct class constant for the same current class
        }.bytes()
        return ClassFile(0, 61, JvmConstantPool.read(ByteReader(pool), 61), 1,
            "example/Outer\$Inner", "java/lang/Object", emptyList(), emptyList(), emptyList(), attributes.toList())
    }
}
