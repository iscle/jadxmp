package com.jadxmp.codegen.java

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
 * The user-comment **application seam** in the Java backend: a [CommentMap] entry renders as `//` line(s)
 * immediately before the keyed class/method/field definition, at the definition's indent, and free text is
 * sanitized so a comment can NEVER break the source (multi-line splits into `//` lines; a block-comment
 * terminator, a raw newline, or a Unicode-escape-to-newline is defused). The [CommentMap] is keyed by the
 * same [CodeNodeRef]
 * identity find-usages/rename speak, and a comment is pure decoration — it carries no annotation and does
 * not corrupt the definition metadata `nodeAt` relies on.
 *
 * The load-bearing safety property (empty map ⇒ byte-identical output) is guarded by
 * [emptyCommentMapLeavesOutputByteIdentical] and, at scale, by the full-corpus off-diff.
 */
class JavaUserCommentTest {

    private fun commentMap(vararg entries: Pair<CodeNodeRef, String>): CommentMap =
        CommentMap.of(mapOf(*entries))

    // ---- (f) empty map ⇒ byte-identical to the pre-feature backend --------------

    @Test
    fun emptyCommentMapLeavesOutputByteIdentical() {
        val cls = irClass("p.a")
        cls.fields.add(IrField(cls, "b", IrType.INT, Flags.PRIVATE))
        cls.method("c", accessFlags = Flags.PUBLIC or Flags.ABSTRACT)

        // The gate: default args, explicit EMPTY, and an empty-map `of` all produce the identical render.
        assertTrue(CommentMap.of(emptyMap()) === CommentMap.EMPTY, "empty ⇒ EMPTY by identity")
        val default = JavaCodeGenerator().generate(cls).code
        val explicitEmpty = JavaCodeGenerator().generate(cls, AliasMap.EMPTY, CommentMap.EMPTY).code
        assertEquals(default, explicitEmpty)
        assertThatCode(default).doesNotContain("//")
    }

    // ---- (a) class comment renders immediately before the declaration -----------

    @Test
    fun classCommentRendersBeforeTheClassDeclaration() {
        val cls = irClass("p.a")
        val code = JavaCodeGenerator().generate(cls, AliasMap.EMPTY, commentMap(ClassNodeRef("p.a") to "hello")).code
        // The comment is one `//` line at the class indent (0), on the line directly above `class a`.
        assertThatCode(code)
            .containsOne("// hello")
            .containsLines(0, "// hello", "public class a {")
    }

    // ---- (b) method / field comment at the right (member) indent ----------------

    @Test
    fun methodAndFieldCommentsRenderBeforeTheirDefinitionsAtMemberIndent() {
        val cls = irClass("p.a")
        cls.fields.add(IrField(cls, "count", IrType.INT, Flags.PRIVATE))
        cls.method("render", accessFlags = Flags.PUBLIC or Flags.ABSTRACT)

        val map = commentMap(
            FieldNodeRef("p.a", "count") to "the running total",
            MethodNodeRef("p.a", "render", emptyList()) to "draws the widget",
        )
        val code = JavaCodeGenerator().generate(cls, AliasMap.EMPTY, map).code

        // Each comment sits one indent level in (4 spaces), directly above its member.
        assertThatCode(code)
            .containsLines(1, "// the running total", "private int count;")
            .containsLines(1, "// draws the widget", "public abstract void render();")
    }

    // ---- (c) a multi-line comment becomes multiple `//` lines -------------------

    @Test
    fun multiLineCommentRendersAsMultipleLineComments() {
        val cls = irClass("p.a")
        // Interior blank line included: it renders as a bare `//` (no trailing space).
        val map = commentMap(ClassNodeRef("p.a") to "first line\n\nthird line")
        val code = JavaCodeGenerator().generate(cls, AliasMap.EMPTY, map).code
        assertThatCode(code)
            .containsLines(0, "// first line", "//", "// third line", "public class a {")
        // No stray trailing space on the blank comment line.
        assertThatCode(code).doesNotContain("// \n")
    }

    // ---- (d) sanitization: nothing can escape the line comment ------------------

    @Test
    fun commentWithBlockTerminatorStaysOnOneHarmlessLine() {
        // `*/` is inert inside a `//` comment (only a block comment would care), so it renders verbatim on
        // one line and never opens/closes a comment.
        val cls = irClass("p.a")
        val map = commentMap(ClassNodeRef("p.a") to "danger */ still a comment /* ok")
        val code = JavaCodeGenerator().generate(cls, AliasMap.EMPTY, map).code
        assertThatCode(code)
            .containsOne("// danger */ still a comment /* ok")
            .containsLines(0, "// danger */ still a comment /* ok", "public class a {")
    }

    @Test
    fun embeddedCarriageReturnAndNewlineAreSplitNotEscaped() {
        // A CRLF and a lone CR must split like an LF, so no physical newline is ever left inside one `//`.
        val cls = irClass("p.a")
        val map = commentMap(ClassNodeRef("p.a") to "a\r\nb\rc")
        val code = JavaCodeGenerator().generate(cls, AliasMap.EMPTY, map).code
        assertThatCode(code).containsLines(0, "// a", "// b", "// c", "public class a {")
    }

    @Test
    fun everyUnicodeEscapeMarkerIsDefusedNotJustNewlineOnes() {
        // Java expands `\ u XXXX` BEFORE it recognizes comments (JLS 3.3 before 3.7), so ANY backslash-u in a
        // `//` comment is processed: a newline escape ends the line, and one NOT followed by 4 hex digits is
        // an illegal escape that fails the whole file. Every `\u` marker is broken with a space after the
        // backslash; over-breaking a valid escape is harmless (it stays inside the decorative comment).
        fun render(text: String): String =
            JavaCodeGenerator().generate(irClass("p.a"), AliasMap.EMPTY, commentMap(ClassNodeRef("p.a") to text)).code

        // The newline escape (would spill code) is still defused, on its own line above the declaration.
        assertThatCode(render("x " + "\\u000a" + " y")).containsLines(0, "// x \\ u000a y", "public class a {")
        // Everyday hazards: `\u` starts `\users`/`\unit`, a trailing `\u`, and a valid escape (over-broken).
        assertThatCode(render("C:" + "\\users\\config")).containsOne("// C:\\ users\\config")
        assertThatCode(render("use the " + "\\unit")).containsOne("// use the \\ unit")
        assertThatCode(render("marker " + "\\u")).containsOne("// marker \\ u")
        assertThatCode(render("value " + "\\u0041")).containsOne("// value \\ u0041")

        // The invariant that makes the source compilable: no un-broken backslash-u survives anywhere.
        for (t in listOf("C:\\users", "\\unit", "marker \\u", "\\u0041", "x \\u000a", "\\uu000a")) {
            assertTrue(!render(t).contains("\\u"), "no un-broken backslash-u may survive for <$t>")
        }
    }

    // ---- (g) a comment is decoration: definition metadata still resolves --------

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
        val info = JavaCodeGenerator().generate(cls, AliasMap.EMPTY, commentMap(methodRef to "computes m"))

        // The comment is present, but carries NO annotation of its own — no offset in its character range
        // is recorded, so it is pure decoration and cannot be a jump-to-def / find-usages target.
        assertThatCode(info.code).containsLines(1, "// computes m", "public int m() {")
        val commentStart = info.code.indexOf("// computes m")
        val commentEnd = commentStart + "// computes m".length
        assertTrue(
            info.metadata.asMap().keys.none { it in commentStart until commentEnd },
            "no annotation may fall inside the injected comment's character range",
        )

        // The method's DEFINITION annotation still resolves — at the offset (shifted by the comment) where
        // the `m` name token is actually emitted.
        val defEntry = info.metadata.asMap().entries.single { it.value == DefinitionAnnotation(methodRef) }
        assertEquals('m', info.code[defEntry.key], "the method definition annotation lands on the `m` token")

        // And nodeAt from inside the body still resolves the enclosing method (not corrupted by the shift).
        val insideBody = info.code.indexOf("parseInt")
        assertEquals(methodRef, info.metadata.nodeAt(insideBody))
    }
}
