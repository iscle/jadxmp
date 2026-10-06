package com.jadxmp.input.jvm

import com.jadxmp.input.ClassNesting
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmClassMetadataJavacTest {
    @Test fun declaredInventoryExposesUnusedExternalTypeNameDecoysWithoutLoadingBodies() {
        val directory = Files.createTempDirectory("jadxmp-member-inventory").toFile()
        try {
            val source = directory.resolve("Base.java").apply { writeText("""
                package external;
                public class Base {
                    public @interface Tag { }
                    protected static class android { public static class support { } }
                    private class Hidden { }
                    void unused() { class Local { } }
                }
                class Empty { }
            """.trimIndent()) }
            assertEquals(0, checkNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null,
                "--release", "17", "-d", directory.path, source.path))
            fun declaration(name: String) = JvmInput.readDeclaration(directory.resolve("external/$name.class").readBytes())
            val base = declaration("Base")
            val members = checkNotNull(base.memberTypes).associateBy { it.innerName }
            assertEquals(setOf("Tag", "android", "Hidden"), members.keys)
            assertEquals("Lexternal/Base\$Tag;", members.getValue("Tag").type)
            assertEquals(0x2609, members.getValue("Tag").accessFlags)
            assertEquals(0x000c, members.getValue("android").accessFlags)
            assertEquals(emptyList(), declaration("Empty").memberTypes)
            assertEquals(ClassNesting.Nested("Lexternal/Base;", innerName = "Tag"), declaration("Base\$Tag").nesting)
        } finally { directory.deleteRecursively() }
    }

    @Test fun matchingInnerEntriesForDistinctClassConstantsAreAcceptedByTheJvm() {
        val bytes = ClassBytes().apply {
            u4(0xcafebabeL); u2(0); u2(61); u2(10)
            utf("example/Outer\$Inner"); u1(7); u2(1) // 1,2
            utf("example/Outer"); u1(7); u2(3) // 3,4
            utf("Inner"); utf("InnerClasses"); u1(7); u2(1) // 5,6,7
            utf("java/lang/Object"); u1(7); u2(8) // 8,9
            u2(0x21); u2(2); u2(9); u2(0); u2(0); u2(0)
            u2(1); u2(6); u4(18); u2(2)
            u2(2); u2(4); u2(5); u2(9)
            u2(7); u2(4); u2(5); u2(9)
        }.bytes()
        val loaded = object : ClassLoader() {
            fun load(): Class<*> = defineClass(null, bytes, 0, bytes.size)
        }.load()
        assertEquals("example.Outer\$Inner", loaded.name)
        val metadata = JvmClassMetadata.read(ClassFileParser.parse(bytes))
        assertEquals(ClassNesting.Nested("Lexample/Outer;", innerName = "Inner"), metadata.nesting)
        assertEquals(9, metadata.innerAccessFlags)
    }

    @Test fun javacMemberLocalAnonymousInitializerAndDollarClassesRetainTheirMetadata() {
        val directory = Files.createTempDirectory("jadxmp-class-metadata").toFile()
        try {
            val source = directory.resolve("Outer.java").apply { writeText("""
                public class Outer {
                    private static final class Member { }
                    Object field = new Object() { };
                    Object local(int value) { class Local { } return new Local(); }
                    Object anonymous() { return new Object() { }; }
                }
                class Outer${'$'}Dollar { }
            """.trimIndent()) }
            val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
            assertEquals(0, compiler.run(null, null, null, "--release", "17", "-g", "-d", directory.path, source.path))
            val classes = directory.listFiles().orEmpty().filter { it.extension == "class" }
                .map { ClassFileParser.parse(it.readBytes()) }.associateBy { it.name }
            val metadata = classes.mapValues { JvmClassMetadata.read(it.value) }
            assertEquals(6, metadata.size)
            assertEquals(ClassNesting.TopLevel, metadata.getValue("Outer").nesting)
            assertEquals(ClassNesting.TopLevel, metadata.getValue("Outer\$Dollar").nesting)
            val member = metadata.getValue("Outer\$Member")
            assertEquals(ClassNesting.Nested("LOuter;", innerName = "Member"), member.nesting)
            assertEquals(0x1a, member.innerAccessFlags)
            assertTrue(metadata.values.all { it.sourceFile == "Outer.java" })
            val nested = metadata.values.mapNotNull { it.nesting as? ClassNesting.Nested }
            val local = nested.single { it.innerName == "Local" }
            assertEquals("local", local.enclosingMethod!!.name)
            assertEquals(listOf("I"), local.enclosingMethod!!.parameterTypes)
            val anonymous = nested.filter { it.innerName == null }
            assertEquals(2, anonymous.size)
            assertTrue(anonymous.all { it.enclosingClassType == "LOuter;" })
            assertEquals(setOf(null, "anonymous"), anonymous.map { it.enclosingMethod?.name }.toSet())
            assertNull(member.nesting.let { it as ClassNesting.Nested }.enclosingMethod)
        } finally {
            directory.deleteRecursively()
        }
    }
}
