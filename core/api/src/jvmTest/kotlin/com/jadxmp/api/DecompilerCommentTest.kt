package com.jadxmp.api

import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.MethodNodeRef
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end proof of the user-comment **wiring** on the [Decompiler] facade over real `hello.dex` bytes:
 * an added comment re-renders the affected class with the note before the symbol's definition (its stale
 * cached render is dropped), a blank/removed comment reverts, and — the load-bearing gate — with no
 * comments the output is byte-for-byte identical to a decompile that never touched the feature (and
 * [Decompiler.clearComments] restores that exactly). The populator logic (trim, blank-removes, key scheme)
 * is proven on hand-built models in [UserCommentTest] and the render/sanitization in the codegen module's
 * `JavaUserCommentTest`; here we prove the facade rebuilds, invalidates and reverts correctly.
 *
 * A jvmTest only because it reads the `.dex` fixture from the classpath; the facade it drives is all
 * `commonMain`. `hello.dex` is a single top-level class `HelloWorld` with `main`/`<init>`.
 */
class DecompilerCommentTest {

    private fun helloDexBytes(): ByteArray =
        javaClass.classLoader.getResourceAsStream("hello.dex")?.readBytes()
            ?: error("hello.dex test resource not found")

    private fun loaded(): Decompiler {
        val d = Decompiler()
        assertTrue(d.load("hello.dex", helloDexBytes()) >= 1, "expected at least one class")
        return d
    }

    /** The navigation-tree key for `main` — the exact ref a UI would carry from the tree / find-usages. */
    private fun mainKey(d: Decompiler): MethodNodeRef =
        d.classMembers("HelloWorld").first { it.displayName == "main" }.key as MethodNodeRef

    @Test
    fun commentingTheClassReRendersItWithTheNoteAndDropsTheStaleCache() {
        val d = loaded()
        // Warm the cache first, so the re-render can only pass if invalidation actually happened.
        val before = assertNotNull(d.decompileClass("HelloWorld"))
        assertTrue(before.code.contains("class HelloWorld"))
        assertTrue(!before.code.contains("//"), "no comment yet")

        d.setComment(ClassNodeRef("HelloWorld"), "reversed HelloWorld")

        val after = assertNotNull(d.decompileClass("HelloWorld"))
        assertTrue(after.code.contains("// reversed HelloWorld"), "class comment rendered:\n${after.code}")
        assertTrue(
            after.code.indexOf("// reversed HelloWorld") < after.code.indexOf("class HelloWorld"),
            "the comment precedes the class declaration:\n${after.code}",
        )
        assertEquals(1, d.comments.size, "the comment is enumerable")
        assertEquals("reversed HelloWorld", d.comments[ClassNodeRef("HelloWorld")], "enumerated under its binary key")
    }

    @Test
    fun commentingAMethodRendersBeforeItsDefinition() {
        val d = loaded()
        val main = mainKey(d)
        assertTrue(d.decompileClass("HelloWorld")!!.code.contains("void main("))

        d.setComment(main, "program entry point")

        val code = d.decompileClass("HelloWorld")!!.code
        assertTrue(code.contains("// program entry point"), "method comment rendered:\n$code")
        assertTrue(
            code.indexOf("// program entry point") < code.indexOf("void main("),
            "the comment precedes the method:\n$code",
        )
    }

    @Test
    fun multiLineCommentRendersAsMultipleLineCommentsInContext() {
        val d = loaded()
        d.setComment(ClassNodeRef("HelloWorld"), "line A\nline B")
        val code = d.decompileClass("HelloWorld")!!.code
        assertTrue(code.contains("// line A"), "first line:\n$code")
        assertTrue(code.contains("// line B"), "second line:\n$code")
    }

    @Test
    fun blankTextAndRemoveCommentRevertTheOutput() {
        val d = loaded()
        val original = d.decompileClass("HelloWorld")!!.code

        d.setComment(ClassNodeRef("HelloWorld"), "temporary")
        assertTrue(d.decompileClass("HelloWorld")!!.code.contains("// temporary"))

        // Blank text removes (same call serves edit + delete) → output reverts byte-for-byte.
        d.setComment(ClassNodeRef("HelloWorld"), "   ")
        assertTrue(d.comments.isEmpty(), "blank text removed the comment")
        assertEquals(original, d.decompileClass("HelloWorld")!!.code, "blank revert is byte-identical")

        // removeComment reverts identically.
        d.setComment(ClassNodeRef("HelloWorld"), "again")
        d.removeComment(ClassNodeRef("HelloWorld"))
        assertTrue(d.comments.isEmpty())
        assertEquals(original, d.decompileClass("HelloWorld")!!.code, "removeComment revert is byte-identical")
    }

    @Test
    fun withNoCommentsOutputIsByteIdenticalAndClearRestoresIt() {
        // Baseline: a fresh instance that never saw the feature used.
        val baseline = loaded().decompileClass("HelloWorld")!!.code

        // Determinism across instances with no comments — the exact pre-feature codegen path (EMPTY map).
        assertEquals(baseline, loaded().decompileClass("HelloWorld")!!.code)

        // Apply comments, then clear: output must revert to byte-for-byte the baseline.
        val d = loaded()
        d.setComment(ClassNodeRef("HelloWorld"), "note")
        d.setComment(mainKey(d), "entry")
        assertTrue(d.decompileClass("HelloWorld")!!.code != baseline, "precondition: comments changed the output")

        d.clearComments()
        assertTrue(d.comments.isEmpty())
        assertEquals(baseline, d.decompileClass("HelloWorld")!!.code, "clearComments reverts to the pre-comment output")
    }

    @Test
    fun commentsAreDeterministicAcrossInstances() {
        fun renderCommented(): String {
            val d = loaded()
            d.setComment(ClassNodeRef("HelloWorld"), "a note\nover two lines")
            d.setComment(mainKey(d), "entry")
            return d.decompileClass("HelloWorld")!!.code
        }
        assertEquals(renderCommented(), renderCommented(), "identical comments render identically")
    }

    @Test
    fun commentApiOnAnUnloadedInstanceNeverThrows() {
        // No validation is performed, so nothing throws; there is simply no render to affect.
        val d = Decompiler()
        d.setComment(ClassNodeRef("Whatever"), "note")
        d.removeComment(ClassNodeRef("Whatever"))
        d.clearComments()
        // A subsequent load starts clean (comments are session-local to the loaded input).
        d.setComment(ClassNodeRef("HelloWorld"), "will be wiped by load")
        d.load("hello.dex", helloDexBytes())
        assertTrue(d.comments.isEmpty(), "load() resets user comments")
    }

    /**
     * The real source-safety proof: the emitted class must actually **compile under javac** after a comment
     * is injected — the only test that recompiles the output rather than asserting string structure. Each
     * hazard is a way free text can escape a `//` comment: a backslash-u NOT followed by four hex digits is
     * an *illegal unicode escape* that Java rejects pre-lex (whole-file), even inside a comment — so
     * `C:\users`, `\unit`, a trailing `\u`, `\u00` would each make the class uncompilable without the
     * broadened defusal; a real newline would spill code; a valid escape must survive over-defusing. Fails
     * without the fix (javac errors on the first four), passes with it.
     */
    @Test
    fun sanitizedCommentsKeepTheEmittedSourceCompilableUnderJavac() {
        // Precondition: the un-commented class is a clean javac round-trip, so any failure below is the comment.
        val baseErrors = compileJava("HelloWorld", loaded().decompileClass("HelloWorld")!!.code)
        assertTrue(baseErrors.isEmpty(), "precondition: un-commented HelloWorld must compile; javac errors:\n$baseErrors")

        val hazards = mapOf(
            "windows path" to "C:\\users\\test",
            "word starting backslash-u" to "use the \\unit variable",
            "trailing backslash-u" to "trailing marker \\u",
            "short (2-hex) escape" to "short escape \\u00 here",
            "real newline" to "line A\nline B",
            "valid escape (over-defused, still fine)" to "value \\u0041 here",
        )
        for ((label, text) in hazards) {
            val d = loaded()
            d.setComment(ClassNodeRef("HelloWorld"), text)
            val src = d.decompileClass("HelloWorld")!!.code
            val errors = compileJava("HelloWorld", src)
            assertTrue(errors.isEmpty(), "comment [$label] must keep the class compilable; javac errors:\n$errors\n\n$src")
        }
    }

    /** Compile [source] as `<className>.java` with the JDK compiler; return the ERROR diagnostics (empty ⇒ OK). */
    private fun compileJava(className: String, source: String): List<String> {
        // No system compiler (a JRE-only run) ⇒ nothing to assert against; treat as a pass rather than fail.
        val compiler = ToolProvider.getSystemJavaCompiler() ?: return emptyList()
        val dir = Files.createTempDirectory("jadxmp-comment-javac").toFile()
        try {
            val srcFile = File(dir, "$className.java")
            srcFile.writeText(source)
            val diagnostics = DiagnosticCollector<JavaFileObject>()
            val out = File(dir, "out").apply { mkdirs() }
            compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8).use { fm ->
                val units = fm.getJavaFileObjectsFromFiles(listOf(srcFile))
                compiler.getTask(null, fm, diagnostics, listOf("-d", out.path), null, units).call()
            }
            return diagnostics.diagnostics
                .filter { it.kind == Diagnostic.Kind.ERROR }
                .map { it.toString() }
        } finally {
            dir.deleteRecursively()
        }
    }
}
