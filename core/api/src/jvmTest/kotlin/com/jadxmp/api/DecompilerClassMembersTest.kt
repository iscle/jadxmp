package com.jadxmp.api

import com.jadxmp.codegen.DefinitionAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end test of [Decompiler.classMembers] over the real `hello.dex` fixture, plus the load-bearing
 * navigation proof: a [MemberInfo.key] taken from the (cheap, un-decompiled) member list must match a
 * `DefinitionAnnotation` in the *decompiled* class metadata, so a UI tree click can scroll to the source.
 *
 * jvmTest only because it reads the `.dex` fixture from the classpath; the facade it drives is commonMain.
 */
class DecompilerClassMembersTest {

    private fun helloDex(): Decompiler {
        val bytes = javaClass.classLoader.getResourceAsStream("hello.dex")?.readBytes()
            ?: error("hello.dex test resource not found")
        val d = Decompiler()
        val count = d.load("hello.dex", bytes)
        assertTrue(count >= 1, "expected classes, got $count")
        return d
    }

    @Test
    fun enumeratesHelloWorldMembersWithoutDecompiling() {
        val members = helloDex().classMembers("HelloWorld")
        // HelloWorld declares a constructor and static main(String[]).
        val ctor = members.singleOrNull { it.kind == MemberKind.CONSTRUCTOR }
        assertNotNull(ctor, "expected a constructor; got $members")
        assertEquals("HelloWorld", ctor.displayName, "constructor renders as the class name")

        val main = members.singleOrNull { it.displayName == "main" }
        assertNotNull(main, "expected main(); got $members")
        assertEquals(MemberKind.METHOD, main.kind)
        assertTrue(main.signature.startsWith("main("), "signature: ${main.signature}")
        assertTrue(main.signature.endsWith(": void"), "main returns void: ${main.signature}")
        assertTrue(Modifier.STATIC in main.modifiers, "main is static: ${main.modifiers}")
    }

    @Test
    fun unknownClassYieldsEmptyList() {
        assertTrue(helloDex().classMembers("does.not.Exist").isEmpty())
        // Nothing loaded → still empty, never a crash.
        assertTrue(Decompiler().classMembers("Anything").isEmpty())
    }

    @Test
    fun memberKeyResolvesToADefinitionOffsetInTheDecompiledMetadata() {
        val d = helloDex()
        val main = d.classMembers("HelloWorld").single { it.displayName == "main" }

        val decompiled = assertNotNull(d.decompileClass("HelloWorld"))
        val metadata = assertNotNull(decompiled.metadata.code, "expected code metadata")

        // The navigation contract: the member key equals the DefinitionAnnotation ref at some offset.
        val offset = metadata.asMap().entries.firstOrNull { (_, ann) ->
            ann is DefinitionAnnotation && ann.ref == main.key
        }?.key
        assertNotNull(offset, "main's key ${main.key} must align with a DefinitionAnnotation offset")
        // And that offset really points into the emitted source at main's declaration.
        assertTrue(offset in decompiled.code.indices, "offset $offset within code")
    }
}
