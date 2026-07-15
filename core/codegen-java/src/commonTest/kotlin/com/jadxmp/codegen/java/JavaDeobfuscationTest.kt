package com.jadxmp.codegen.java

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
 * The deobfuscation **application seam**: an [AliasMap] override must rename a class/field/method at its
 * definition AND at every reference, and an override that clashes with a kept name must still be
 * disambiguated by the existing within-scope uniqueness machinery. The map is built by `core:api`'s
 * `Deobfuscator`; here we drive the codegen application directly with hand-built maps.
 *
 * The load-bearing safety property (empty map ⇒ byte-identical output) is guarded by
 * [emptyMapLeavesObfuscatedNamesUntouched] and proven at scale by the full-corpus off-diff.
 */
class JavaDeobfuscationTest {

    @Test
    fun emptyMapLeavesObfuscatedNamesUntouched() {
        // The gate: with no overrides the obfuscated raw names are emitted verbatim, identical to a
        // build without the feature (default arg == EMPTY == explicit EMPTY).
        val cls = irClass("p.a")
        cls.fields.add(IrField(cls, "b", IrType.INT, Flags.PRIVATE))
        cls.method("c", accessFlags = Flags.PUBLIC or Flags.ABSTRACT)

        val default = JavaCodeGenerator().generate(cls).code
        val explicitEmpty = JavaCodeGenerator().generate(cls, AliasMap.EMPTY).code
        assertEquals(default, explicitEmpty)
        assertThatCode(default)
            .containsOne("class a {")
            .containsOne("private int b;")
            .containsOne("void c();")
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
        a.method("c", returnType = IrType.VOID, accessFlags = Flags.PUBLIC or Flags.STATIC) { body() }

        val main = irClass("p.Main", root = root)
        main.fields.add(IrField(main, "holder", aType, Flags.PUBLIC))
        main.method("run", accessFlags = Flags.PUBLIC or Flags.STATIC) {
            body(staticInvoke(aType, "c", IrType.VOID, emptyList(), emptyList()))
        }
        main.method("read", returnType = IrType.INT, accessFlags = Flags.PUBLIC or Flags.STATIC) {
            body(ret(expr(staticGet(FieldRef(aType, "b", IrType.INT), reg(-1, IrType.INT)))))
        }

        val map = AliasMap.of(
            mapOf(
                ClassNodeRef("p.a") to "C0001",
                FieldNodeRef("p.a", "b") to "f0001",
                MethodNodeRef("p.a", "c", emptyList()) to "m0001",
            ),
        )

        // Definition sites (in a's own file) + the output file path derived from the renamed body.
        assertThatCode(JavaCodeGenerator().generate(a, map).code)
            .containsOne("class C0001 {")
            .containsOne("int f0001;")
            .containsOne("void m0001() {")
            .doesNotContain("class a {")
        assertEquals("p.C0001", JavaCodeGenerator.sourceName(a, map))

        // Reference sites (in Main's file): the renamed class as a field type, and the renamed class +
        // members in a cross-class static call and static read.
        assertThatCode(JavaCodeGenerator().generate(main, map).code)
            .containsOne("C0001 holder;")
            .containsOne("C0001.m0001();")
            .containsOne("C0001.f0001")
            .doesNotContain("p.a")
            .doesNotContain("a.m0001")
    }

    @Test
    fun renamedClassReferenceMatchesDefinitionThroughExtends() {
        // Mirrors the reserved-word cross-class test, but for a deobfuscation rename: the `extends`
        // reference must spell the exact alias the superclass definition uses.
        val root = IrRoot()
        val superclass = irClass("pkg.a", root = root)
        val sub = irClass("pkg.B", superType = IrType.objectType("pkg.a"), root = root)
        val map = AliasMap.of(mapOf(ClassNodeRef("pkg.a") to "C0001"))
        assertThatCode(JavaCodeGenerator().generate(superclass, map).code).containsOne("class C0001 {")
        assertThatCode(JavaCodeGenerator().generate(sub, map).code).containsOne("class B extends C0001 {")
    }

    @Test
    fun overrideThatClashesWithAKeptNameIsDisambiguated() {
        // Field `a` is overridden to `f0001`, but a DISTINCT kept field is literally named `f0001`. The
        // existing within-class uniqueness pass must keep them apart (declaration order: the override
        // keeps the base, the later kept field is suffixed) — no "already defined" collision.
        val cls = irClass("p.C")
        cls.fields.add(IrField(cls, "a", IrType.INT, Flags.PUBLIC))
        cls.fields.add(IrField(cls, "f0001", IrType.INT, Flags.PUBLIC))
        val map = AliasMap.of(mapOf(FieldNodeRef("p.C", "a") to "f0001"))
        assertThatCode(JavaCodeGenerator().generate(cls, map).code)
            .containsOne("int f0001;")
            .containsOne("int f00012;")
    }
}
