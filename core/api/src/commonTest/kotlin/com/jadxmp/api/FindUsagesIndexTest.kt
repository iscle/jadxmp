package com.jadxmp.api

import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.CodeAnnotation
import com.jadxmp.codegen.CodeMetadata
import com.jadxmp.codegen.DefinitionAnnotation
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.codegen.NodeEndAnnotation
import com.jadxmp.codegen.RefKind
import com.jadxmp.codegen.ReferenceAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit test of the find-usages *inversion* ([buildUsageIndex]) over hand-built [CodeMetadata], with
 * no decompiler or binary fixture. This is the part that must hold on every target (including wasm), and
 * it lets us assert the cases a single-class `.dex` fixture cannot: a target referenced from **two**
 * classes, and the deterministic cross-class ordering. The end-to-end inversion over real emitted metadata
 * is proven separately on `hello.dex` (jvmTest).
 *
 * Each synthetic class is a small source string; token offsets are located with `indexOf` so the metadata
 * stays honest to the text (offset→line is then real).
 */
class FindUsagesIndexTest {

    // The shared target both classes reference: t.Tgt#call().
    private val target = MethodNodeRef("t.Tgt", "call", emptyList())

    // Class a.A: references its superclass x.Sup at class scope, then calls t.Tgt.call() inside m().
    private val codeA =
        "class A extends Sup {\n" + // line 1
            "  void m() {\n" + //       line 2
            "    Tgt.call();\n" + //    line 3
            "  }\n" + //                line 4
            "}\n" //                    line 5

    // Class c.C: calls t.Tgt.call() inside n(). Named to sort AFTER a.A so ordering is observable.
    private val codeC =
        "class C {\n" + //     line 1
            "  void n() {\n" + // line 2
            "    Tgt.call();\n" + // line 3
            "  }\n" + //          line 4
            "}\n" //             line 5

    private fun sourceA(): ClassUsageSource {
        val ann = LinkedHashMap<Int, CodeAnnotation>()
        ann[codeA.indexOf("A ")] = DefinitionAnnotation(ClassNodeRef("a.A"))
        ann[codeA.indexOf("Sup")] = ReferenceAnnotation(ClassNodeRef("x.Sup")) // class-scope use → no member
        ann[codeA.indexOf("m(")] = DefinitionAnnotation(MethodNodeRef("a.A", "m", emptyList()))
        ann[codeA.indexOf("Tgt")] = ReferenceAnnotation(target) // inside m() → member = m
        ann[codeA.indexOf("}")] = NodeEndAnnotation // end of m()
        ann[codeA.lastIndexOf("}")] = NodeEndAnnotation // end of A
        return ClassUsageSource("a.A", codeA, CodeMetadata.build(ann, emptyMap()))
    }

    private fun sourceC(): ClassUsageSource {
        val ann = LinkedHashMap<Int, CodeAnnotation>()
        ann[codeC.indexOf("C ")] = DefinitionAnnotation(ClassNodeRef("c.C"))
        ann[codeC.indexOf("n(")] = DefinitionAnnotation(MethodNodeRef("c.C", "n", emptyList()))
        ann[codeC.indexOf("Tgt")] = ReferenceAnnotation(target) // inside n() → member = n
        ann[codeC.indexOf("}")] = NodeEndAnnotation
        ann[codeC.lastIndexOf("}")] = NodeEndAnnotation
        return ClassUsageSource("c.C", codeC, CodeMetadata.build(ann, emptyMap()))
    }

    @Test
    fun aggregatesUsesFromEveryClassInDeterministicOrder() {
        // Pass C before A: the result must still be sorted by class name (a.A before c.C), not insertion.
        val index = buildUsageIndex(listOf(sourceC(), sourceA()))
        val sites = index.query(target)

        assertEquals(2, sites.size, "target is used once in each of the two classes: $sites")
        assertEquals(listOf("a.A", "c.C"), sites.map { it.fromClass }, "sorted by referring class name")
        assertTrue(sites.all { it.kind == RefKind.METHOD }, "the target is a method reference")

        val inA = sites.single { it.fromClass == "a.A" }
        assertEquals(MethodNodeRef("a.A", "m", emptyList()), inA.fromMember, "use in a.A sits inside m()")
        assertEquals(3, inA.line, "the call is on source line 3")
        assertEquals(codeA.indexOf("Tgt"), inA.offset, "offset points at the referencing token")

        val inC = sites.single { it.fromClass == "c.C" }
        assertEquals(MethodNodeRef("c.C", "n", emptyList()), inC.fromMember, "use in c.C sits inside n()")
        assertEquals(3, inC.line)
    }

    @Test
    fun classScopeUseHasNoEnclosingMember() {
        val index = buildUsageIndex(listOf(sourceA()))
        val supUses = index.query(ClassNodeRef("x.Sup"))
        val site = supUses.single()
        assertEquals("a.A", site.fromClass)
        assertNull(site.fromMember, "an extends-clause use is at class scope, not inside a method")
        assertEquals(RefKind.CLASS, site.kind)
        assertEquals(1, site.line, "the superclass is on the class-declaration line")
    }

    @Test
    fun unreferencedSymbolReturnsEmpty() {
        val index = buildUsageIndex(listOf(sourceA(), sourceC()))
        // A field nothing mentions.
        assertTrue(index.query(FieldNodeRef("t.Tgt", "missing")).isEmpty())
        // A method that differs only in signature from the real target is a distinct key → no match.
        assertTrue(index.query(MethodNodeRef("t.Tgt", "call", listOf("int"))).isEmpty())
    }

    @Test
    fun declarationsAreNotCountedAsUses() {
        val index = buildUsageIndex(listOf(sourceA(), sourceC()))
        // a.A is DECLARED (DefinitionAnnotation) but never referenced → find-usages excludes its own decl.
        assertTrue(index.query(ClassNodeRef("a.A")).isEmpty(), "a declaration is not a usage")
        assertTrue(index.query(MethodNodeRef("a.A", "m", emptyList())).isEmpty(), "m is defined, never called")
    }

    @Test
    fun emptyInputYieldsTotalEmptyQuery() {
        val index = buildUsageIndex(emptyList())
        assertTrue(index.query(target).isEmpty(), "empty index never throws, just returns empty")
    }
}
