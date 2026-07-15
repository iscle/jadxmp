package com.jadxmp.codegen.kotlin

import com.jadxmp.codegen.AliasMap
import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.ir.insn.FieldRef
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import com.jadxmp.testsupport.assertThatCode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The deobfuscation/rename **application seam** in the Kotlin backend: an [AliasMap] override must rename a
 * class/field/method at its definition AND at every reference, keyed by the SAME [ClassNodeRef] /
 * [FieldNodeRef] / [MethodNodeRef] identities the Java backend uses — so a rename shows consistently in both
 * source views and find-usages / go-to-def align across them. Mirrors `JavaDeobfuscationTest`.
 *
 * The load-bearing safety property (empty map ⇒ byte-identical output) is guarded by
 * [emptyMapLeavesObfuscatedNamesUntouched]; the differential oracle always runs with zero overrides.
 */
class KotlinDeobfuscationTest {

    @Test
    fun emptyMapLeavesObfuscatedNamesUntouched() {
        // The gate: with no overrides the obfuscated raw names are emitted verbatim, identical to a build
        // without the feature (default arg == EMPTY == explicit EMPTY).
        val cls = irClass("p.a")
        cls.fields.add(IrField(cls, "b", IrType.INT, Flags.PRIVATE))
        cls.method("c") { body(ret()) }

        val default = KotlinCodeGenerator().generate(cls).code
        val explicitEmpty = KotlinCodeGenerator().generate(cls, AliasMap.EMPTY).code
        assertEquals(default, explicitEmpty)
        assertThatCode(default)
            .containsOne("class a {")
            .containsOne("fun c() {")
    }

    @Test
    fun renamesClassFieldAndMethodAtDefinitionAndEveryReference() {
        // Class `p.a` → C0001, its static field `b` → f0001, static method `c` → m0001. A second class in
        // the same package references all three; the reference sites must spell the SAME aliases as the
        // definition — never the raw names — so find-usages / go-to-def / imports stay coherent.
        val root = IrRoot()
        val aType = IrType.objectType("p.a")
        val a = irClass("p.a", root = root)
        a.fields.add(IrField(a, "b", IrType.INT, Flags.PUBLIC or Flags.STATIC))
        a.method("c", returnType = IrType.VOID, accessFlags = Flags.PUBLIC or Flags.STATIC) { body(ret()) }

        val main = irClass("p.Main", root = root)
        main.fields.add(IrField(main, "holder", aType, Flags.PUBLIC))
        main.method("run", accessFlags = Flags.PUBLIC or Flags.STATIC) {
            body(staticInvoke(aType, "c", IrType.VOID, emptyList(), emptyList()), ret())
        }
        main.method("read", returnType = IrType.INT, accessFlags = Flags.PUBLIC or Flags.STATIC) {
            body(ret(expr(staticGet(FieldRef(aType, "b", IrType.INT)))))
        }

        val map = AliasMap.of(
            mapOf(
                ClassNodeRef("p.a") to "C0001",
                FieldNodeRef("p.a", "b") to "f0001",
                MethodNodeRef("p.a", "c", emptyList()) to "m0001",
            ),
        )

        // Definition sites (in a's own file): the renamed class, field and method — never the raw names.
        assertThatCode(KotlinCodeGenerator().generate(a, map).code)
            .containsOne("class C0001 {")
            .containsOne("f0001")
            .containsOne("fun m0001() {")
            .doesNotContain("class a {")
            .doesNotContain("fun c(")

        // Reference sites (in Main's file): the renamed class as a property type, and the renamed class +
        // members in a cross-class static call and static read.
        assertThatCode(KotlinCodeGenerator().generate(main, map).code)
            .contains(": C0001")
            .containsOne("C0001.m0001()")
            .containsOne("C0001.f0001")
            .doesNotContain("fun c(")
            .doesNotContain(".b")
    }

    @Test
    fun renamedClassReferenceMatchesDefinitionThroughSupertype() {
        // The supertype reference must spell the exact alias the superclass definition uses.
        val root = IrRoot()
        val superclass = irClass("pkg.a", accessFlags = Flags.PUBLIC, root = root)
        val sub = irClass("pkg.B", superType = IrType.objectType("pkg.a"), root = root)
        val map = AliasMap.of(mapOf(ClassNodeRef("pkg.a") to "C0001"))
        assertThatCode(KotlinCodeGenerator().generate(superclass, map).code).containsOne("class C0001 {")
        // A super constructor call carries `()`; the reference is the alias, not the raw `a`.
        assertThatCode(KotlinCodeGenerator().generate(sub, map).code)
            .containsOne("class B : C0001() {")
            .doesNotContain(": a")
    }

    @Test
    fun renderIsDeterministicWithOverrides() {
        // Rendering the same class twice with the same non-empty map produces byte-identical output (pure
        // read of the immutable model + the read-only map — no cross-node state).
        val cls = irClass("p.a")
        cls.fields.add(IrField(cls, "b", IrType.INT, Flags.PRIVATE))
        cls.method("c") { body(ret()) }
        val map = AliasMap.of(
            mapOf(
                ClassNodeRef("p.a") to "Renamed",
                FieldNodeRef("p.a", "b") to "field0",
                MethodNodeRef("p.a", "c", emptyList()) to "method0",
            ),
        )
        assertEquals(
            KotlinCodeGenerator().generate(cls, map).code,
            KotlinCodeGenerator().generate(cls, map).code,
        )
    }
}
