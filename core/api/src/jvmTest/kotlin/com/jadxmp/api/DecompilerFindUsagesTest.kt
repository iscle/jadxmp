package com.jadxmp.api

import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.CodeNodeRef
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.codegen.RefKind
import com.jadxmp.codegen.ReferenceAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end proof that [Decompiler.findUsages] correctly **inverts** the real click-to-definition metadata
 * the Java backend emits — over the `hello.dex` fixture. `HelloWorld.main` calls `System.out.println(...)`,
 * so the fixture references an external field (`java.lang.System.out`), an external method
 * (`java.io.PrintStream.println`) and external types; find-usages of each must point back at the site in
 * `HelloWorld`. (Multi-class aggregation and cross-class ordering are proven purely in [FindUsagesIndexTest];
 * `hello.dex` is single-class, so it proves the inversion against *real emitted* metadata instead.)
 *
 * A jvmTest only because it reads the `.dex` from the classpath; the facade it drives is all `commonMain`.
 */
class DecompilerFindUsagesTest {

    private fun helloDex(): Decompiler {
        val bytes = javaClass.classLoader.getResourceAsStream("hello.dex")?.readBytes()
            ?: error("hello.dex test resource not found")
        val d = Decompiler()
        assertTrue(d.load("hello.dex", bytes) >= 1, "expected at least one class")
        return d
    }

    /** Every (offset, target) reference the backend actually emitted into HelloWorld's source. */
    private fun referencesIn(d: Decompiler): List<Pair<Int, CodeNodeRef>> {
        val meta = assertNotNull(d.decompileClass("HelloWorld")?.metadata?.code, "expected code metadata")
        return meta.asMap().entries
            .mapNotNull { (offset, ann) -> (ann as? ReferenceAnnotation)?.let { offset to it.ref } }
    }

    @Test
    fun findUsagesInvertsEveryEmittedReference() {
        val d = helloDex()
        val refs = referencesIn(d)
        assertTrue(refs.isNotEmpty(), "HelloWorld.main references System.out/println/types")

        // The core inversion contract: for every reference the backend emitted at some offset, querying
        // that exact target must yield a usage site back at that class and offset.
        for ((offset, target) in refs) {
            val sites = d.findUsages(target)
            assertTrue(
                sites.any { it.fromClass == "HelloWorld" && it.offset == offset },
                "findUsages($target) must contain the HelloWorld use at offset $offset; got $sites",
            )
            assertTrue(sites.all { it.kind == target.refKind }, "site kind mirrors the target kind")
        }

        // hello.dex exercises both a method call and a field read, so both ref kinds are present.
        assertTrue(refs.any { it.second is MethodNodeRef }, "expected a method reference (println)")
        assertTrue(refs.any { it.second is FieldNodeRef }, "expected a field reference (System.out)")
    }

    @Test
    fun findsExternalMethodAndFieldWithHandBuiltKeys() {
        val d = helloDex()

        // A UI builds the query key from names/descriptors; the backend records references with the SAME
        // binary-name scheme, so a hand-built key resolves. Discover the exact emitted method ref for
        // println (its erased descriptors), then assert the hand-built field key for System.out matches.
        val refs = referencesIn(d).map { it.second }

        val println = refs.filterIsInstance<MethodNodeRef>().firstOrNull { it.name == "println" }
        assertNotNull(println, "main() calls println; refs=$refs")
        val printlnSites = d.findUsages(println)
        assertTrue(printlnSites.isNotEmpty(), "println is called from HelloWorld.main")
        val printlnSite = printlnSites.single { it.fromClass == "HelloWorld" }
        assertEquals(RefKind.METHOD, printlnSite.kind)
        val enclosing = assertNotNull(printlnSite.fromMember, "the call sits inside a method body")
        assertTrue(enclosing is MethodNodeRef && enclosing.name == "main", "enclosed by main(): $enclosing")

        // System.out is a field read; the backend keys it FieldNodeRef("java.lang.System","out") (dotted
        // binary owner) — the exact key a UI would build from the tree — so it resolves directly.
        val systemOut = FieldNodeRef("java.lang.System", "out")
        assertTrue(systemOut in refs, "expected System.out among the emitted references: $refs")
        val outSites = d.findUsages(systemOut)
        assertTrue(
            outSites.any { it.fromClass == "HelloWorld" && it.kind == RefKind.FIELD },
            "field read of System.out is found in HelloWorld: $outSites",
        )
    }

    @Test
    fun resultsAreDeterministicAndSorted() {
        val d = helloDex()
        val target = referencesIn(d).map { it.second }.first { it is MethodNodeRef }

        val first = d.findUsages(target)
        val second = d.findUsages(target) // served from the cached index
        assertEquals(first, second, "repeated queries return the identical, stable list")

        // Sorted by (fromClass, offset): verify the ordering invariant holds on the returned list.
        val sortedByContract = first.sortedWith(compareBy({ it.fromClass }, { it.offset }))
        assertEquals(sortedByContract, first, "sites come pre-sorted by class name then offset")
    }

    @Test
    fun unknownAndUnloadedQueriesAreEmptyNeverThrow() {
        val d = helloDex()
        // A symbol nothing references → empty (never a throw), even after the index is built.
        assertTrue(d.findUsages(MethodNodeRef("does.not.Exist", "nope", emptyList())).isEmpty())
        assertTrue(d.findUsages(ClassNodeRef("does.not.Exist")).isEmpty())

        // Nothing loaded at all → empty, index never built, no crash (rule 4).
        assertTrue(Decompiler().findUsages(ClassNodeRef("Whatever")).isEmpty())
        assertTrue(Decompiler().findUsages(FieldNodeRef("a.B", "c")).isEmpty())
    }
}
