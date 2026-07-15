package com.jadxmp.api

import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.CodeNodeRef
import com.jadxmp.codegen.CommentMap
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.codegen.java.JavaCodeGenerator
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The user-comment **populator** ([UserCommentStore]) and how it merges into the effective codegen
 * [CommentMap]: trimming, blank-removes, remove/clear, snapshot independence, the change signal the
 * [Decompiler] rebuilds on, and — the load-bearing gate — that an empty store yields [CommentMap.EMPTY] by
 * identity so output is byte-identical. Rendering the sanitized comment text is covered in the codegen
 * module (`JavaUserCommentTest`); the [Decompiler] wiring end to end is proven on `hello.dex` in
 * `DecompilerCommentTest`. The store keys on the SAME [com.jadxmp.codegen.CodeNodeRef] a UI lists from the
 * tree / resolves from find-usages / passes to rename, so "click symbol → comment" is coherent.
 */
class UserCommentTest {

    private companion object {
        const val ACC_PUBLIC = 0x0001
        const val ACC_ABSTRACT = 0x0400
    }

    private fun cls(root: IrRoot, fullName: String): IrClass =
        IrClass(root, fullName, accessFlags = ACC_PUBLIC, superType = IrType.OBJECT).also { root.addClass(it) }

    private fun IrClass.field(name: String) {
        fields.add(IrField(this, name, IrType.INT, ACC_PUBLIC))
    }

    private fun IrClass.abstractMethod(name: String, args: List<IrType> = emptyList()) {
        methods.add(IrMethod(this, name, IrType.VOID, args, ACC_PUBLIC or ACC_ABSTRACT))
    }

    /** Build the effective comment map exactly as `Decompiler.rebuildCommentMap` does. */
    private fun effectiveMap(store: UserCommentStore): CommentMap = CommentMap.of(store.comments())

    // ---- set / trim / blank-removes ---------------------------------------------

    @Test
    fun setStoresTrimmedTextAndReportsChange() {
        val store = UserCommentStore()
        assertTrue(store.isEmpty)

        assertTrue(store.set(ClassNodeRef("p.a"), "  hello  "), "a new comment changes the store")
        assertFalse(store.isEmpty)
        assertEquals("hello", store.comments()[ClassNodeRef("p.a")], "surrounding whitespace is trimmed")

        // Setting the SAME (post-trim) text again is not a change ⇒ the Decompiler skips invalidation.
        assertFalse(store.set(ClassNodeRef("p.a"), "hello"), "an identical comment is a no-op")
        assertTrue(store.set(ClassNodeRef("p.a"), "world"), "different text is a change")
    }

    @Test
    fun blankTextRemovesTheComment() {
        val store = UserCommentStore()
        store.set(ClassNodeRef("p.a"), "note")
        assertFalse(store.isEmpty)

        // Whitespace-only ⇒ blank ⇒ removes; reports the change.
        assertTrue(store.set(ClassNodeRef("p.a"), "   \n  "), "blank text removes and reports a change")
        assertTrue(store.isEmpty, "the comment was removed")
        // Blank on an absent key changes nothing.
        assertFalse(store.set(ClassNodeRef("p.a"), ""), "blank on an absent key is a no-op")
    }

    @Test
    fun multiLineCommentIsPreservedInteriorNewlinesAndAll() {
        val store = UserCommentStore()
        // Only the OUTER whitespace is trimmed; the interior newline stays so the note remains multi-line.
        store.set(ClassNodeRef("p.a"), "\n  line one\nline two  \n")
        assertEquals("line one\nline two", store.comments()[ClassNodeRef("p.a")])
    }

    // ---- remove / clear / snapshot ----------------------------------------------

    @Test
    fun removeAndClearReportChangeAndEmptyTheStore() {
        val store = UserCommentStore()
        store.set(FieldNodeRef("p.a", "b"), "one")
        store.set(MethodNodeRef("p.a", "c", listOf("int")), "two")

        assertTrue(store.remove(FieldNodeRef("p.a", "b")), "removing an existing comment is a change")
        assertFalse(store.remove(FieldNodeRef("p.a", "b")), "removing an absent comment is a no-op")
        assertFalse(store.isEmpty, "the method comment remains")

        store.clear()
        assertTrue(store.isEmpty)
    }

    @Test
    fun snapshotIsIndependentOfLaterEdits() {
        val store = UserCommentStore()
        store.set(ClassNodeRef("p.a"), "first")
        val snap = store.snapshot()
        store.set(ClassNodeRef("p.a"), "second")
        store.set(ClassNodeRef("p.b"), "another")
        assertEquals(mapOf<CodeNodeRef, String>(ClassNodeRef("p.a") to "first"), snap, "snapshot is frozen at capture")
    }

    // ---- key scheme matches find-usages / rename --------------------------------

    @Test
    fun commentKeyMatchesTheMemberInfoNavigationKeyAndRendersBeforeTheMember() {
        // A UI drives comments off the SAME key it lists in the tree / resolves from find-usages / renames.
        val root = IrRoot()
        val a = cls(root, "p.a")
        a.field("b")
        a.abstractMethod("c", listOf(IrType.INT))

        val members = membersOf(a)
        val fieldKey = members.first { it.kind == MemberKind.FIELD }.key
        val methodKey = members.first { it.kind == MemberKind.METHOD }.key
        assertEquals(FieldNodeRef("p.a", "b"), fieldKey)
        assertEquals(MethodNodeRef("p.a", "c", listOf("int")), methodKey)

        val store = UserCommentStore()
        store.set(fieldKey, "the field")
        store.set(methodKey, "the method")

        // Rendered through the real backend, each note lands before its member's definition.
        val code = JavaCodeGenerator().generate(a, AliasMap.EMPTY, effectiveMap(store)).code
        assertTrue(code.contains("// the field"), "field comment rendered:\n$code")
        assertTrue(code.contains("// the method"), "method comment rendered:\n$code")
        assertTrue(code.indexOf("// the field") < code.indexOf("int b;"), "field comment precedes the field")
        assertTrue(code.indexOf("// the method") < code.indexOf("void c("), "method comment precedes the method")
    }

    // ---- empty store ⇒ empty map by identity, byte-identical output -------------

    @Test
    fun emptyStoreYieldsEmptyMapByIdentityAndOutputUnchanged() {
        val root = IrRoot()
        val a = cls(root, "p.a")
        a.field("b")
        a.abstractMethod("c")
        val store = UserCommentStore()

        // The exact identity the Decompiler relies on: with nothing to inject the effective map IS EMPTY.
        assertTrue(effectiveMap(store) === CommentMap.EMPTY, "no user comments ⇒ CommentMap.EMPTY by identity")

        // And the render equals a build that never knew about the feature.
        assertEquals(
            JavaCodeGenerator().generate(a).code,
            JavaCodeGenerator().generate(a, AliasMap.EMPTY, effectiveMap(store)).code,
        )
    }
}
