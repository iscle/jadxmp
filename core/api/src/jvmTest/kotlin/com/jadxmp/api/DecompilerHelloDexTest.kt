package com.jadxmp.api

import com.jadxmp.testsupport.assertThatCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end proof of the whole facade: `hello.dex` bytes → [Decompiler] → decompiled `HelloWorld`
 * Java. Exercises input → IR build → analysis pipeline (CFG/SSA/types) → out-of-SSA bridge →
 * codegen-java. hello.dex's `<init>` and `main` are straight-line (φ-free), so they must render to
 * plausible, compilable Java.
 *
 * A jvmTest (not commonTest) purely because it reads the `.dex` fixture from the JVM classpath; the
 * facade it drives is all `commonMain`.
 */
class DecompilerHelloDexTest {

    private fun helloDexBytes(): ByteArray =
        javaClass.classLoader.getResourceAsStream("hello.dex")?.readBytes()
            ?: error("hello.dex test resource not found")

    private fun decompileHelloWorld(args: DecompilerArgs = DecompilerArgs()): DecompiledClass {
        val decompiler = Decompiler(args)
        val count = decompiler.load("hello.dex", helloDexBytes())
        assertTrue(count >= 1, "expected at least one class, got $count")
        val cls = decompiler.decompileClass("HelloWorld")
        return assertNotNull(cls, "HelloWorld should decompile; classes=${decompiler.classNames}")
    }

    @Test
    fun classInfoReportsHelloWorldAsAPlainClass() {
        // End-to-end proof of the new facade accessor on real dex bytes: no decompilation needed, read
        // straight from the loaded model. HelloWorld is a top-level plain public class.
        val decompiler = Decompiler()
        decompiler.load("hello.dex", helloDexBytes())
        val info = assertNotNull(
            decompiler.classInfo("HelloWorld"),
            "classInfo must resolve a loaded class; classes=${decompiler.classNames}",
        )
        assertEquals(ClassKind.CLASS, info.kind, "HelloWorld is a plain class, not interface/enum/annotation")
        // HelloWorld is package-private in the fixture, so no source visibility modifier — reported honestly
        // (an empty set), never a bogus modifier synthesized from ACC_SUPER.
        assertTrue(Modifier.SYNCHRONIZED !in info.modifiers, "ACC_SUPER must not leak in as a modifier")
        assertTrue(!info.isInner, "HelloWorld is top-level")
        // An absent name is fault-isolated to null (never a crash).
        assertEquals(null, decompiler.classInfo("does.not.Exist"))
    }

    @Test
    fun smaliDisassemblesHelloWorldFromTheInputModel() {
        // The smali facade reads straight from the loaded input model — no pipeline decompile needed.
        val decompiler = Decompiler()
        decompiler.load("hello.dex", helloDexBytes())
        val smali = assertNotNull(
            decompiler.smali("HelloWorld"),
            "smali must resolve a loaded class; classes=${decompiler.classNames}",
        )
        assertTrue(smali.contains(".class LHelloWorld;"), smali)
        assertTrue(smali.contains(".method public static main([Ljava/lang/String;)V"), smali)
        assertTrue(smali.contains("invoke-"), smali)
        assertTrue(smali.contains("return-void"), smali)
        // Fault isolation (rule 4): an unknown class name yields null, never a crash.
        assertEquals(null, decompiler.smali("does.not.Exist"))
        // Nothing loaded also yields null.
        assertEquals(null, Decompiler().smali("HelloWorld"))
    }

    @Test
    fun decompilesHelloWorldToJava() {
        val cls = decompileHelloWorld()
        assertEquals("HelloWorld", cls.fullName)

        assertThatCode(cls.code)
            .containsOne("class HelloWorld")
            // constructor delegates to the Object super-constructor (invoke-direct, but a super() call).
            .containsOne("super();")
            // the greeting string literal survives to the source.
            .containsOne("\"Hello, World!\"")
            // main's signature and the println call are recovered.
            .contains("static void main(")
            .contains("println")
    }

    @Test
    fun helloWorldMethodsAreStraightLineAndErrorFree() {
        val cls = decompileHelloWorld()
        assertEquals(0, cls.metadata.errorCount, "no method should fault:\n${cls.code}")
        assertTrue(cls.metadata.fullyStructured, "hello.dex methods are single-block:\n${cls.code}")
    }

    @Test
    fun parallelSchedulerProducesSameResult() = runBlocking {
        val decompiler = Decompiler()
        decompiler.load("hello.dex", helloDexBytes())
        val result = decompiler.decompileAllParallel()
        val hello = result.classes.single { it.fullName == "HelloWorld" }
        assertThatCode(hello.code)
            .containsOne("\"Hello, World!\"")
            .containsOne("super();")
        assertEquals(0, result.errorCount)
    }
}
