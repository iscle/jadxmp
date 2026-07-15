package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.CodeNodeRef
import com.jadxmp.codegen.CommentMap
import com.jadxmp.codegen.DefinitionAnnotation
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The user-comment **application seam** in the Kotlin backend: a [CommentMap] entry renders as `//` line(s)
 * immediately before the keyed class/method/field definition, at the definition's indent. `//` line comments
 * are identical in Java and Kotlin, so the SHARED `CodeWriter.emitLineComment` (multi-line split +
 * newline/NUL/`\u`-escape defusal) is reused verbatim — a comment can NEVER break the source. The map is
 * keyed by the same [CodeNodeRef] identity find-usages/rename speak, and a comment is pure decoration (no
 * annotation), so it does not corrupt the definition metadata `nodeAt` relies on. Mirrors
 * `JavaUserCommentTest`.
 *
 * The load-bearing safety property (empty map ⇒ byte-identical output) is guarded by
 * [emptyCommentMapLeavesOutputByteIdentical]; the differential oracle always runs with zero comments.
 */
class KotlinUserCommentTest {

    private fun commentMap(vararg entries: Pair<CodeNodeRef, String>): CommentMap =
        CommentMap.of(mapOf(*entries))

    // ---- empty map ⇒ byte-identical to the pre-feature backend ------------------

    @Test
    fun emptyCommentMapLeavesOutputByteIdentical() {
        val cls = irClass("p.a")
        cls.fields.add(IrField(cls, "b", IrType.INT, Flags.PRIVATE))
        cls.method("c") { body(ret()) }

        // The gate: default args, explicit EMPTY, and an empty-map `of` all produce the identical render.
        assertTrue(CommentMap.of(emptyMap()) === CommentMap.EMPTY, "empty ⇒ EMPTY by identity")
        val default = KotlinCodeGenerator().generate(cls).code
        val explicitEmpty = KotlinCodeGenerator().generate(cls, AliasMap.EMPTY, CommentMap.EMPTY).code
        assertEquals(default, explicitEmpty)
        assertThatCode(default).doesNotContain("//")
    }

    // ---- class comment renders immediately before the declaration ---------------

    @Test
    fun classCommentRendersBeforeTheClassDeclaration() {
        val cls = irClass("p.a")
        val code = KotlinCodeGenerator().generate(cls, AliasMap.EMPTY, commentMap(ClassNodeRef("p.a") to "hello")).code
        // The comment is one `//` line at the class indent (0), on the line directly above `class a`.
        assertThatCode(code)
            .containsOne("// hello")
            .containsLines(0, "// hello", "class a {")
    }

    // ---- method / field comment at the right (member) indent --------------------

    @Test
    fun methodAndFieldCommentsRenderBeforeTheirDefinitionsAtMemberIndent() {
        val cls = irClass("p.a")
        cls.fields.add(IrField(cls, "count", IrType.INT, Flags.PRIVATE))
        cls.method("render") { body(ret()) }

        val map = commentMap(
            FieldNodeRef("p.a", "count") to "the running total",
            MethodNodeRef("p.a", "render", emptyList()) to "draws the widget",
        )
        val code = KotlinCodeGenerator().generate(cls, AliasMap.EMPTY, map).code

        // Each comment sits one indent level in (4 spaces), directly above its member.
        assertThatCode(code)
            .containsLines(1, "// the running total", "private var count: Int = 0")
            .containsLines(1, "// draws the widget", "fun render() {")
    }

    // ---- a multi-line comment becomes multiple `//` lines -----------------------

    @Test
    fun multiLineCommentRendersAsMultipleLineComments() {
        val cls = irClass("p.a")
        // Interior blank line included: it renders as a bare `//` (no trailing space).
        val map = commentMap(ClassNodeRef("p.a") to "first line\n\nthird line")
        val code = KotlinCodeGenerator().generate(cls, AliasMap.EMPTY, map).code
        assertThatCode(code)
            .containsLines(0, "// first line", "//", "// third line", "class a {")
        // No stray trailing space on the blank comment line.
        assertThatCode(code).doesNotContain("// \n")
    }

    // ---- sanitization is shared & language-agnostic (`\u` defusal works in Kotlin) ----

    @Test
    fun everyUnicodeEscapeMarkerIsDefusedInKotlinToo() {
        // The shared emitLineComment defuses every backslash-u marker; the Kotlin backend reuses it verbatim.
        fun render(text: String): String =
            KotlinCodeGenerator().generate(irClass("p.a"), AliasMap.EMPTY, commentMap(ClassNodeRef("p.a") to text)).code

        assertThatCode(render("x " + "\\u000a" + " y")).containsLines(0, "// x \\ u000a y", "class a {")
        assertThatCode(render("C:" + "\\users\\config")).containsOne("// C:\\ users\\config")
        // The invariant: no un-broken backslash-u survives anywhere in the emitted source.
        for (t in listOf("C:\\users", "\\unit", "marker \\u", "\\u0041", "x \\u000a", "\\uu000a")) {
            assertTrue(!render(t).contains("\\u"), "no un-broken backslash-u may survive for <$t>")
        }
    }

    // ---- a comment is decoration: definition metadata still resolves ------------

    @Test
    fun injectedCommentDoesNotCorruptDefinitionMetadataOrNodeAt() {
        val cls = irClass("a.Foo")
        val r = Local(1, IrType.INT)
        cls.method("m", returnType = IrType.INT) {
            body(
                assign(
                    r.ref(),
                    staticInvoke(
                        IrType.objectType("java.lang.Integer"),
                        "parseInt",
                        IrType.INT,
                        listOf(IrType.STRING),
                        listOf(expr(constString("5"))),
                    ),
                ),
                ret(r.ref()),
            )
        }
        val methodRef = MethodNodeRef("a.Foo", "m", emptyList())
        val info = KotlinCodeGenerator().generate(cls, AliasMap.EMPTY, commentMap(methodRef to "computes m"))

        // The comment is present, but carries NO annotation of its own — no offset in its character range is
        // recorded, so it is pure decoration and cannot be a jump-to-def / find-usages target.
        assertThatCode(info.code).containsLines(1, "// computes m", "fun m(): Int {")
        val commentStart = info.code.indexOf("// computes m")
        val commentEnd = commentStart + "// computes m".length
        assertTrue(
            info.metadata.asMap().keys.none { it in commentStart until commentEnd },
            "no annotation may fall inside the injected comment's character range",
        )

        // The method's DEFINITION annotation still resolves — at the (comment-shifted) offset where the `m`
        // name token is actually emitted.
        val defEntry = info.metadata.asMap().entries.single { it.value == DefinitionAnnotation(methodRef) }
        assertEquals('m', info.code[defEntry.key], "the method definition annotation lands on the `m` token")

        // And nodeAt from inside the body still resolves the enclosing method (not corrupted by the shift).
        val insideBody = info.code.indexOf("parseInt")
        assertEquals(methodRef, info.metadata.nodeAt(insideBody))
    }

    // ---- determinism ------------------------------------------------------------

    @Test
    fun renderIsDeterministicWithComments() {
        val cls = irClass("p.a")
        cls.fields.add(IrField(cls, "b", IrType.INT, Flags.PRIVATE))
        cls.method("c") { body(ret()) }
        val map = commentMap(
            ClassNodeRef("p.a") to "the class",
            FieldNodeRef("p.a", "b") to "the field",
            MethodNodeRef("p.a", "c", emptyList()) to "the method",
        )
        assertEquals(
            KotlinCodeGenerator().generate(cls, AliasMap.EMPTY, map).code,
            KotlinCodeGenerator().generate(cls, AliasMap.EMPTY, map).code,
        )
    }
}
