package com.jadxmp.api

import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.MethodNodeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end proof of the user-rename **wiring** on the [Decompiler] facade over real `hello.dex` bytes:
 * an accepted rename re-renders the affected class with the new spelling (its stale cached render is
 * dropped), a rejected rename changes nothing, and — the load-bearing gate — with no renames the output
 * is byte-for-byte identical to a decompile that never touched the feature (and [clearRenames] restores
 * that exactly). The populator logic itself (validation, collision, precedence, cross-class references) is
 * proven on hand-built models in [UserRenameTest]; here we prove the facade merges, invalidates and
 * reverts correctly.
 *
 * A jvmTest only because it reads the `.dex` fixture from the classpath; the facade it drives is all
 * `commonMain`. `hello.dex` is a single top-level class `HelloWorld` with `main`/`<init>`.
 */
class DecompilerRenameTest {

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
    fun renamingTheClassReRendersItWithTheNewNameAndDropsTheStaleCache() {
        val d = loaded()
        // Warm the cache first, so the re-render can only pass if invalidation actually happened.
        val before = assertNotNull(d.decompileClass("HelloWorld"))
        assertEquals("HelloWorld", before.fullName)
        assertTrue(before.code.contains("class HelloWorld"))

        val result = d.rename(ClassNodeRef("HelloWorld"), "Greeter")
        assertEquals(RenameResult.Applied(ClassNodeRef("HelloWorld"), "Greeter"), result)

        // The binary key is unchanged (an alias is a codegen spelling only), so the same lookup resolves —
        // now rendering the new name at the definition AND as the emitted source/file name.
        val after = assertNotNull(d.decompileClass("HelloWorld"))
        assertEquals("Greeter", after.fullName, "the emitted source name follows the rename")
        assertTrue(after.code.contains("class Greeter"), "class definition renamed:\n${after.code}")
        assertTrue(!after.code.contains("class HelloWorld"), "no stale name survives:\n${after.code}")
        assertEquals(1, d.renames.size, "the rename is enumerable")
        assertEquals("Greeter", d.renames[ClassNodeRef("HelloWorld")], "enumerated under its binary key")
    }

    @Test
    fun renamingAMethodReRendersItsDefinition() {
        val d = loaded()
        val main = mainKey(d)
        assertTrue(d.decompileClass("HelloWorld")!!.code.contains("void main("))

        assertEquals(RenameResult.Applied(main, "entryPoint"), d.rename(main, "entryPoint"))

        val code = d.decompileClass("HelloWorld")!!.code
        assertTrue(code.contains("void entryPoint("), "method definition renamed:\n$code")
        assertTrue(!code.contains("void main("), "no stale method name:\n$code")
    }

    @Test
    fun rejectedRenamesChangeNothing() {
        val d = loaded()
        val original = d.decompileClass("HelloWorld")!!.code

        // Invalid name (reserved word / illegal identifier) → rejected, no override recorded, output intact.
        assertTrue(d.rename(ClassNodeRef("HelloWorld"), "int") is RenameResult.InvalidName)
        assertTrue(d.rename(ClassNodeRef("HelloWorld"), "1Bad") is RenameResult.InvalidName)
        // Unrenamable targets → rejected.
        assertTrue(d.rename(MethodNodeRef("HelloWorld", "<init>", emptyList()), "ctor") is RenameResult.UnrenamableTarget)
        assertTrue(d.rename(ClassNodeRef("does.not.Exist"), "Foo") is RenameResult.UnrenamableTarget)

        assertTrue(d.renames.isEmpty(), "no rejected rename was recorded")
        assertEquals(original, d.decompileClass("HelloWorld")!!.code, "rejected renames leave output byte-identical")
    }

    @Test
    fun withNoRenamesOutputIsByteIdenticalAndClearRestoresIt() {
        // Baseline: a fresh instance that never saw the feature used.
        val baseline = loaded().decompileClass("HelloWorld")!!.code

        // Determinism across instances with no renames — the exact pre-feature codegen path (EMPTY map).
        assertEquals(baseline, loaded().decompileClass("HelloWorld")!!.code)

        // Apply renames, then clear: output must revert to byte-for-byte the baseline.
        val d = loaded()
        assertTrue(d.rename(ClassNodeRef("HelloWorld"), "Greeter") is RenameResult.Applied)
        assertTrue(d.rename(mainKey(d), "entryPoint") is RenameResult.Applied)
        assertTrue(d.decompileClass("HelloWorld")!!.code != baseline, "precondition: renames changed the output")

        d.clearRenames()
        assertTrue(d.renames.isEmpty())
        assertEquals(baseline, d.decompileClass("HelloWorld")!!.code, "clearRenames reverts to the pre-rename output")
    }

    @Test
    fun renamesAreDeterministicAcrossInstances() {
        fun renderRenamed(): String {
            val d = loaded()
            d.rename(ClassNodeRef("HelloWorld"), "Greeter")
            d.rename(mainKey(d), "entryPoint")
            return d.decompileClass("HelloWorld")!!.code
        }
        assertEquals(renderRenamed(), renderRenamed(), "identical renames render identically")
    }

    @Test
    fun renameOnAnUnloadedInstanceIsRejectedNeverThrows() {
        assertTrue(Decompiler().rename(ClassNodeRef("Whatever"), "Foo") is RenameResult.UnrenamableTarget)
        assertTrue(Decompiler().renames.isEmpty())
        // clearRenames on an untouched instance is a harmless no-op.
        Decompiler().clearRenames()
    }
}
