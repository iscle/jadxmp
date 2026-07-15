package com.jadxmp.api

import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.CodeNodeRef
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
import kotlin.test.assertTrue

/**
 * The user-rename **populator** ([UserRenameStore]) and how it merges into the effective codegen
 * [AliasMap]: name validation, within-scope collision rejection, precedence over the deobfuscation
 * auto-map, and — through the real Java backend — that an accepted class rename spells identically at its
 * definition and at every cross-class reference. Member call/access application is covered by
 * `JavaDeobfuscationTest` (a user rename produces the same shape of map entry); here we prove the store
 * emits the right entries and that the definition site renders, plus the load-bearing
 * byte-identical-when-empty gate. The [Decompiler] wiring (cache invalidation, no-rename identity end to
 * end) is proven on `hello.dex` in `DecompilerRenameTest`.
 */
class UserRenameTest {

    private companion object {
        const val ACC_PUBLIC = 0x0001
        const val ACC_STATIC = 0x0008
        const val ACC_ABSTRACT = 0x0400
        const val ACC_ENUM = 0x4000
    }

    private fun cls(root: IrRoot, fullName: String, flags: Int = ACC_PUBLIC, superType: IrType = IrType.OBJECT): IrClass =
        IrClass(root, fullName, accessFlags = flags, superType = superType).also { root.addClass(it) }

    private fun IrClass.field(name: String, type: IrType = IrType.INT, flags: Int = ACC_PUBLIC) {
        fields.add(IrField(this, name, type, flags))
    }

    private fun IrClass.abstractMethod(name: String, args: List<IrType> = emptyList()) {
        methods.add(IrMethod(this, name, IrType.VOID, args, ACC_PUBLIC or ACC_ABSTRACT))
    }

    /** Build the EFFECTIVE alias map exactly as `Decompiler.rebuildAliasMap` does (deobf ⊕ user, user wins). */
    private fun effectiveMap(store: UserRenameStore, deobf: Map<CodeNodeRef, String> = emptyMap()): AliasMap =
        AliasMap.of(if (store.isEmpty) deobf else deobf + store.overrides())

    // ---- (a) class rename: definition + cross-class references ------------------

    @Test
    fun classRenameAppliesAtDefinitionAndAtEveryCrossClassReference() {
        val root = IrRoot()
        val widget = cls(root, "p.a") // the class to rename (leaf top-level)
        widget.field("count")
        // A second class references `p.a` two ways: as its superclass and as a field type.
        val user = cls(root, "p.User", superType = IrType.objectType("p.a"))
        user.field("held", IrType.objectType("p.a"))

        val store = UserRenameStore()
        assertTrue(store.tryRename(root, emptyMap(), ClassNodeRef("p.a"), "Widget") is RenameResult.Applied)
        val map = effectiveMap(store)

        // Definition site (in the class's own file) + the derived source/file name.
        val widgetCode = JavaCodeGenerator().generate(widget, map).code
        assertTrue(widgetCode.contains("class Widget {"), "definition renamed:\n$widgetCode")
        assertEquals("p.Widget", JavaCodeGenerator.sourceName(widget, map))

        // Reference sites in the OTHER class: the extends clause and the field type both spell `Widget`.
        val userCode = JavaCodeGenerator().generate(user, map).code
        assertTrue(userCode.contains("extends Widget"), "cross-class extends use renamed:\n$userCode")
        assertTrue(userCode.contains("Widget held;"), "cross-class field-type use renamed:\n$userCode")
        assertTrue(!userCode.contains(" a ") && !userCode.contains("extends a"), "no raw name survives:\n$userCode")
    }

    // ---- (b) field / method rename: entries + definition sites ------------------

    @Test
    fun fieldAndMethodRenameRecordCorrectKeysAndRenderAtDefinition() {
        val root = IrRoot()
        val a = cls(root, "p.a")
        a.field("b")
        a.abstractMethod("c", listOf(IrType.INT))

        val store = UserRenameStore()
        assertTrue(store.tryRename(root, emptyMap(), FieldNodeRef("p.a", "b"), "count") is RenameResult.Applied)
        assertTrue(
            store.tryRename(root, emptyMap(), MethodNodeRef("p.a", "c", listOf("int")), "render") is RenameResult.Applied,
        )

        // The store keys the overrides with the SAME CodeNodeRef identity codegen looks up (and find-usages
        // inverts) — the exact `MemberInfo.key` scheme. That is what makes def and every use agree.
        val map = effectiveMap(store)
        assertEquals("count", map.aliasOf(FieldNodeRef("p.a", "b")))
        assertEquals("render", map.aliasOf(MethodNodeRef("p.a", "c", listOf("int"))))

        val code = JavaCodeGenerator().generate(a, map).code
        assertTrue(code.contains("int count;"), "field def renamed:\n$code")
        assertTrue(code.contains("void render(int"), "method def renamed:\n$code")
    }

    @Test
    fun renameKeyMatchesTheMemberInfoNavigationKey() {
        // A UI drives rename off the SAME key it lists in the tree / resolves from find-usages. Prove the
        // MemberInfo.key resolves to a renamable target, so "find usages → rename" is coherent.
        val root = IrRoot()
        val a = cls(root, "p.a")
        a.field("b")
        a.abstractMethod("c", listOf(IrType.INT))

        val members = membersOf(a)
        val fieldKey = members.first { it.kind == MemberKind.FIELD }.key
        val methodKey = members.first { it.kind == MemberKind.METHOD }.key
        assertEquals(FieldNodeRef("p.a", "b"), fieldKey)
        assertEquals(MethodNodeRef("p.a", "c", listOf("int")), methodKey)

        val store = UserRenameStore()
        assertTrue(store.tryRename(root, emptyMap(), fieldKey, "count") is RenameResult.Applied)
        assertTrue(store.tryRename(root, emptyMap(), methodKey, "render") is RenameResult.Applied)
    }

    // ---- (c) invalid names are rejected, store unchanged ------------------------

    @Test
    fun invalidNamesAreRejectedAndLeaveTheStoreUnchanged() {
        val root = IrRoot()
        val a = cls(root, "p.a")
        a.field("b")
        val store = UserRenameStore()

        for (bad in listOf("int", "class", "var", "_", "1bad", "a-b", "a b", "", "a.b", "a\$b")) {
            val result = store.tryRename(root, emptyMap(), FieldNodeRef("p.a", "b"), bad)
            assertTrue(result is RenameResult.InvalidName, "'$bad' must be rejected as an invalid name, got $result")
        }
        assertTrue(store.isEmpty, "no invalid rename was recorded")
        // Output is unchanged from a build with no renames.
        assertEquals(
            JavaCodeGenerator().generate(a).code,
            JavaCodeGenerator().generate(a, effectiveMap(store)).code,
        )
    }

    // ---- (d) collisions are rejected --------------------------------------------

    @Test
    fun collidingRenamesAreRejected() {
        val root = IrRoot()
        val a = cls(root, "p.a")
        a.field("x")
        a.field("count") // sibling field already named `count`
        a.abstractMethod("m", listOf(IrType.INT))
        a.abstractMethod("keep", listOf(IrType.INT)) // sibling method, SAME params → a collision target
        a.abstractMethod("over", listOf(IrType.LONG)) // different params → a legal overload, NOT a collision
        cls(root, "p.Sibling") // sibling top-level class in the same package

        val store = UserRenameStore()
        // field onto a sibling field name.
        assertTrue(store.tryRename(root, emptyMap(), FieldNodeRef("p.a", "x"), "count") is RenameResult.Collision)
        // method onto a same-parameter sibling method name.
        assertTrue(
            store.tryRename(root, emptyMap(), MethodNodeRef("p.a", "m", listOf("int")), "keep") is RenameResult.Collision,
        )
        // method onto a DIFFERENT-parameter sibling's name is allowed (legal overload).
        assertTrue(
            store.tryRename(root, emptyMap(), MethodNodeRef("p.a", "m", listOf("int")), "over") is RenameResult.Applied,
        )
        // class onto a sibling class name in the same package.
        assertTrue(store.tryRename(root, emptyMap(), ClassNodeRef("p.a"), "Sibling") is RenameResult.Collision)
    }

    @Test
    fun renamingTwoSiblingsToTheSameNameRejectsTheSecond() {
        val root = IrRoot()
        val a = cls(root, "p.a")
        a.field("x")
        a.field("y")
        val store = UserRenameStore()
        assertTrue(store.tryRename(root, emptyMap(), FieldNodeRef("p.a", "x"), "same") is RenameResult.Applied)
        // `y` → `same` now collides with the just-applied rename of `x` (effective names include user edits).
        assertTrue(store.tryRename(root, emptyMap(), FieldNodeRef("p.a", "y"), "same") is RenameResult.Collision)
    }

    // ---- precedence over the deobfuscation auto-map -----------------------------

    @Test
    fun userRenameTakesPrecedenceOverADeobfuscationAlias() {
        val root = IrRoot()
        val a = cls(root, "p.a") // short name ⇒ the deobfuscator would rename it to C0001
        val deobf = Deobfuscator.buildOverrides(root)
        assertEquals("C0001", deobf[ClassNodeRef("p.a")], "precondition: deobf renames p.a")

        val store = UserRenameStore()
        assertTrue(store.tryRename(root, deobf, ClassNodeRef("p.a"), "Widget") is RenameResult.Applied)

        val map = effectiveMap(store, deobf)
        assertEquals("Widget", map.aliasOf(ClassNodeRef("p.a")), "the user name wins over the deobf alias")
        assertTrue(JavaCodeGenerator().generate(a, map).code.contains("class Widget {"))
    }

    @Test
    fun renamingOntoANameTheDeobfuscatorVacatedIsAllowed() {
        // `p.b` is obfuscated → deobf renames it to C0001, freeing the emitted name `b`. Renaming a sibling
        // onto `b` must NOT be flagged as colliding with p.b (whose effective name is now C0001, not `b`).
        val root = IrRoot()
        cls(root, "p.b") // obfuscated ⇒ deobf → C0001
        cls(root, "p.Keep")
        val deobf = Deobfuscator.buildOverrides(root)
        assertEquals("C0001", deobf[ClassNodeRef("p.b")])

        val store = UserRenameStore()
        assertTrue(store.tryRename(root, deobf, ClassNodeRef("p.Keep"), "b") is RenameResult.Applied)
    }

    // ---- unrenamable targets ----------------------------------------------------

    @Test
    fun unrenamableTargetsAreRejected() {
        val root = IrRoot()
        val outer = cls(root, "p.a")
        val inner = cls(root, "p.a\$b").also {
            it.outerClass = outer
            outer.innerClasses.add(it)
        }
        inner.field("y")
        outer.methods.add(IrMethod(outer, "<init>", IrType.VOID, emptyList(), ACC_PUBLIC))
        val e = cls(root, "p.E", ACC_PUBLIC or ACC_ENUM)
        e.field("value")

        val store = UserRenameStore()
        // An inner-bearing (outer) class is not a leaf top-level → rejected.
        assertTrue(store.tryRename(root, emptyMap(), ClassNodeRef("p.a"), "Foo") is RenameResult.UnrenamableTarget)
        // A nested class → rejected (references are not flat).
        assertTrue(store.tryRename(root, emptyMap(), ClassNodeRef("p.a\$b"), "Foo") is RenameResult.UnrenamableTarget)
        // A constructor → rejected (spelled specially by codegen).
        val ctor = MethodNodeRef("p.a", "<init>", emptyList())
        assertTrue(store.tryRename(root, emptyMap(), ctor, "Foo") is RenameResult.UnrenamableTarget)
        // A member of an enum → rejected (reconstructor-owned).
        assertTrue(store.tryRename(root, emptyMap(), FieldNodeRef("p.E", "value"), "v2") is RenameResult.UnrenamableTarget)
        // A class / member not in the model → rejected (never silently stored as a dead entry).
        assertTrue(store.tryRename(root, emptyMap(), ClassNodeRef("p.Missing"), "Foo") is RenameResult.UnrenamableTarget)
        assertTrue(store.tryRename(root, emptyMap(), FieldNodeRef("p.a", "nope"), "Foo") is RenameResult.UnrenamableTarget)
        assertTrue(store.isEmpty, "no unrenamable target was recorded")
    }

    // ---- (e) no renames ⇒ empty map by identity, byte-identical output ----------

    @Test
    fun noRenamesLeavesTheMapEmptyByIdentityAndOutputUnchanged() {
        val root = IrRoot()
        val a = cls(root, "p.a")
        a.field("b")
        a.abstractMethod("c")
        val store = UserRenameStore()

        // The exact identity the Decompiler relies on: with nothing to apply the effective map IS EMPTY.
        assertTrue(effectiveMap(store) === AliasMap.EMPTY, "no deobf + no user ⇒ AliasMap.EMPTY by identity")

        // And the render equals a build that never knew about the feature.
        assertEquals(
            JavaCodeGenerator().generate(a).code,
            JavaCodeGenerator().generate(a, effectiveMap(store)).code,
        )
    }

    // ---- (f) determinism --------------------------------------------------------

    @Test
    fun sameRenamesProduceIdenticalOutput() {
        fun render(): String {
            val root = IrRoot()
            val a = cls(root, "p.a")
            a.field("b")
            a.abstractMethod("c")
            val store = UserRenameStore()
            store.tryRename(root, emptyMap(), ClassNodeRef("p.a"), "Widget")
            store.tryRename(root, emptyMap(), FieldNodeRef("p.a", "b"), "count")
            store.tryRename(root, emptyMap(), MethodNodeRef("p.a", "c", emptyList()), "render")
            return JavaCodeGenerator().generate(a, effectiveMap(store)).code
        }
        assertEquals(render(), render(), "identical renames over identical models render identically")
    }
}
