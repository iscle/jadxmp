package com.jadxmp.api

import com.jadxmp.codegen.ClassNodeRef
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deobfuscation **populator** ([Deobfuscator]): the heuristic (what counts as obfuscated), the
 * deterministic collision-free name scheme, and the leaf/special-class restrictions. The application of
 * the resulting map at codegen is covered by `JavaDeobfuscationTest`; the byte-identical-when-off gate is
 * proven at scale by the full-corpus off-diff.
 */
class DeobfuscatorTest {

    private companion object {
        const val ACC_PUBLIC = 0x0001
        const val ACC_STATIC = 0x0008
        const val ACC_ABSTRACT = 0x0400
        const val ACC_ENUM = 0x4000
        const val ACC_ANNOTATION = 0x2000
    }

    private fun cls(root: IrRoot, fullName: String, flags: Int = ACC_PUBLIC): IrClass =
        IrClass(root, fullName, accessFlags = flags, superType = IrType.OBJECT).also { root.addClass(it) }

    private fun IrClass.field(name: String, flags: Int = ACC_PUBLIC) {
        fields.add(IrField(this, name, IrType.INT, flags))
    }

    private fun IrClass.abstractMethod(name: String, args: List<IrType> = emptyList()) {
        methods.add(IrMethod(this, name, IrType.VOID, args, ACC_PUBLIC or ACC_ABSTRACT))
    }

    @Test
    fun shortNamesAreRenamedReadableOnesAreLeftAlone() {
        val root = IrRoot()
        val a = cls(root, "p.a") // length-1 name ⇒ obfuscated
        cls(root, "p.Widget") // readable ⇒ kept
        a.field("x") // obfuscated field
        a.field("count") // readable field
        a.abstractMethod("m") // obfuscated method
        a.abstractMethod("render") // readable method

        val map = Deobfuscator.buildAliasMap(root)
        assertEquals("C0001", map.aliasOf(ClassNodeRef("p.a")))
        assertNull(map.aliasOf(ClassNodeRef("p.Widget")))
        assertEquals("f0001", map.aliasOf(FieldNodeRef("p.a", "x")))
        assertNull(map.aliasOf(FieldNodeRef("p.a", "count")))
        assertEquals("m0001", map.aliasOf(MethodNodeRef("p.a", "m", emptyList())))
        assertNull(map.aliasOf(MethodNodeRef("p.a", "render", emptyList())))
    }

    @Test
    fun constructorsAndStaticInitAreNeverRenamed() {
        val root = IrRoot()
        val a = cls(root, "p.a")
        a.methods.add(IrMethod(a, "<init>", IrType.VOID, emptyList(), ACC_PUBLIC))
        a.methods.add(IrMethod(a, "<clinit>", IrType.VOID, emptyList(), ACC_STATIC))
        val map = Deobfuscator.buildAliasMap(root)
        assertNull(map.aliasOf(MethodNodeRef("p.a", "<init>", emptyList())))
        assertNull(map.aliasOf(MethodNodeRef("p.a", "<clinit>", emptyList())))
    }

    @Test
    fun namesAreDeterministicAcrossRuns() {
        // Two independent but structurally identical models must yield identical aliases (no dependence on
        // hash/iteration order, randomness, or a clock).
        fun makeRoot(): IrRoot {
            val root = IrRoot()
            val a = cls(root, "p.a")
            val b = cls(root, "p.b")
            a.field("x")
            a.field("y")
            a.abstractMethod("m")
            b.field("z")
            return root
        }

        val first = Deobfuscator.buildAliasMap(makeRoot())
        val second = Deobfuscator.buildAliasMap(makeRoot())
        for (ref in listOf(
            ClassNodeRef("p.a"),
            ClassNodeRef("p.b"),
            FieldNodeRef("p.a", "x"),
            FieldNodeRef("p.a", "y"),
            FieldNodeRef("p.b", "z"),
            MethodNodeRef("p.a", "m", emptyList()),
        )) {
            assertEquals(first.aliasOf(ref), second.aliasOf(ref), "alias for $ref must be stable")
        }
        // The scheme itself.
        assertEquals("C0001", first.aliasOf(ClassNodeRef("p.a")))
        assertEquals("C0002", first.aliasOf(ClassNodeRef("p.b")))
        assertEquals("f0001", first.aliasOf(FieldNodeRef("p.a", "x")))
        assertEquals("f0002", first.aliasOf(FieldNodeRef("p.a", "y")))
    }

    @Test
    fun generatedNamesSkipCollidingKeptNames() {
        val root = IrRoot()
        val a = cls(root, "p.a") // obfuscated ⇒ wants C0001
        cls(root, "p.C0001") // kept class literally named C0001 (in the same package)
        a.field("a") // obfuscated ⇒ wants f0001
        a.field("f0001") // kept field literally named f0001
        val map = Deobfuscator.buildAliasMap(root)
        // C0001 / f0001 are reserved by the kept symbols, so the generated names step past them.
        assertEquals("C0002", map.aliasOf(ClassNodeRef("p.a")))
        assertEquals("f0002", map.aliasOf(FieldNodeRef("p.a", "a")))
        assertNull(map.aliasOf(ClassNodeRef("p.C0001")))
        assertNull(map.aliasOf(FieldNodeRef("p.a", "f0001")))
    }

    @Test
    fun enumAndAnnotationClassesAreSkippedEntirely() {
        val root = IrRoot()
        val e = cls(root, "p.a", ACC_PUBLIC or ACC_ENUM)
        e.field("x")
        e.abstractMethod("m")
        val ann = cls(root, "p.b", ACC_PUBLIC or ACC_ANNOTATION)
        ann.field("y")
        val map = Deobfuscator.buildAliasMap(root)
        assertNull(map.aliasOf(ClassNodeRef("p.a")))
        assertNull(map.aliasOf(FieldNodeRef("p.a", "x")))
        assertNull(map.aliasOf(MethodNodeRef("p.a", "m", emptyList())))
        assertNull(map.aliasOf(ClassNodeRef("p.b")))
        assertNull(map.aliasOf(FieldNodeRef("p.b", "y")))
        assertTrue(map.isEmpty)
    }

    @Test
    fun nestedAndInnerBearingClassesAreNotRenamedButTheirMembersAre() {
        // A class with inner classes is never itself renamed (it could appear as the `Outer` of a nested
        // `$` reference), and a nested class is never renamed (its references are not flat) — but the
        // obfuscated MEMBERS of both are still renamed, since a member reference is flat.
        val root = IrRoot()
        val outer = cls(root, "p.a")
        val inner = cls(root, "p.a\$b").also {
            it.outerClass = outer
            outer.innerClasses.add(it)
        }
        outer.field("x")
        inner.field("y")

        val map = Deobfuscator.buildAliasMap(root)
        assertNull(map.aliasOf(ClassNodeRef("p.a")), "an outer (inner-bearing) class is not a rename candidate")
        assertNull(map.aliasOf(ClassNodeRef("p.a\$b")), "a nested class is not a rename candidate")
        assertEquals("f0001", map.aliasOf(FieldNodeRef("p.a", "x")))
        assertEquals("f0001", map.aliasOf(FieldNodeRef("p.a\$b", "y")))
    }

    @Test
    fun populatorDrivesConsistentRenamedOutputEndToEnd() {
        // The real populator + the Java backend: an obfuscated class renders renamed, and its file path
        // follows the renamed body.
        val root = IrRoot()
        val a = cls(root, "p.a")
        a.field("b")
        a.abstractMethod("c")
        val map = Deobfuscator.buildAliasMap(root)
        val code = JavaCodeGenerator().generate(a, map).code
        assertTrue(code.contains("class C0001 {"), "expected renamed class, got:\n$code")
        assertTrue(code.contains("int f0001;"), "expected renamed field, got:\n$code")
        assertTrue(code.contains("void m0001();"), "expected renamed method, got:\n$code")
        assertEquals("p.C0001", JavaCodeGenerator.sourceName(a, map))
    }
}
