package com.jadxmp.pipeline.model

import com.jadxmp.input.AnnotationData
import com.jadxmp.input.AnnotationVisibility
import com.jadxmp.input.EncodedValue
import com.jadxmp.input.EncodedValueType
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.pipeline.support.FakeClassData
import com.jadxmp.pipeline.support.FakeCodeLoader
import com.jadxmp.pipeline.support.FakeMethodRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies [ModelBuilder] builds the inner/nested-class tree (jadx: RootNode.initInnerClasses): a nested
 * class moves under its enclosing class and off the top level, via the `$` binary name and via the DEX
 * `EnclosingClass`/`EnclosingMethod` annotations, while a `$`-name whose outer is absent stays top-level.
 */
class ModelBuilderNestingTest {

    private fun build(vararg classes: FakeClassData): IrRoot =
        ModelBuilder.build(FakeCodeLoader(classes.toList()))

    private fun topLevel(root: IrRoot): List<IrClass> = root.classes.filter { it.outerClass == null }

    private fun enclosingClassAnnotation(outerDescriptor: String) = AnnotationData(
        annotationType = "Ldalvik/annotation/EnclosingClass;",
        visibility = AnnotationVisibility.SYSTEM,
        values = mapOf("value" to EncodedValue(EncodedValueType.TYPE, outerDescriptor)),
    )

    private fun innerClassAnnotation(name: String?, accessFlags: Int) = AnnotationData(
        annotationType = "Ldalvik/annotation/InnerClass;",
        visibility = AnnotationVisibility.SYSTEM,
        values = buildMap {
            put("accessFlags", EncodedValue(EncodedValueType.INT, accessFlags))
            put("name", if (name == null) EncodedValue.NULL else EncodedValue(EncodedValueType.STRING, name))
        },
    )

    @Test
    fun nestsByBinaryName() {
        val root = build(
            FakeClassData(type = "Lcom/example/Outer;"),
            FakeClassData(type = "Lcom/example/Outer\$Inner;"),
        )
        val outer = root.findClass("com.example.Outer")!!
        val inner = root.findClass("com.example.Outer\$Inner")!!

        assertSame(inner, outer.innerClasses.single())
        assertSame(outer, inner.outerClass)
        // Only Outer is top-level; Inner comes through it (one file per outer).
        assertEquals(listOf("com.example.Outer"), topLevel(root).map { it.fullName })
        assertEquals("Inner", inner.shortName)
    }

    @Test
    fun nestsByEnclosingClassAnnotationEvenWithoutDollarInName() {
        // Obfuscated names with no '$' separator: only the annotation reveals the nesting.
        val root = build(
            FakeClassData(type = "Lcom/example/A;"),
            FakeClassData(
                type = "Lcom/example/B;",
                annotations = listOf(enclosingClassAnnotation("Lcom/example/A;")),
            ),
        )
        val outer = root.findClass("com.example.A")!!
        val inner = root.findClass("com.example.B")!!
        assertSame(inner, outer.innerClasses.single())
        assertSame(outer, inner.outerClass)
        assertEquals(listOf("com.example.A"), topLevel(root).map { it.fullName })
    }

    @Test
    fun nestsAnonymousByEnclosingMethodAnnotation() {
        // A local/anonymous class points at its enclosing class through EnclosingMethod's method ref.
        val enclosingMethod = AnnotationData(
            annotationType = "Ldalvik/annotation/EnclosingMethod;",
            visibility = AnnotationVisibility.SYSTEM,
            values = mapOf(
                "value" to EncodedValue(
                    EncodedValueType.METHOD,
                    FakeMethodRef("Lcom/example/Outer;", "run", "V", emptyList()),
                ),
            ),
        )
        val root = build(
            FakeClassData(type = "Lcom/example/Outer;"),
            FakeClassData(type = "Lcom/example/Outer\$1;", annotations = listOf(enclosingMethod)),
        )
        val outer = root.findClass("com.example.Outer")!!
        val anon = root.findClass("com.example.Outer\$1")!!
        assertSame(anon, outer.innerClasses.single())
        assertSame(outer, anon.outerClass)
        // Anonymous class is NOT emitted top-level (full inlining is a later feature).
        assertEquals(listOf("com.example.Outer"), topLevel(root).map { it.fullName })
    }

    @Test
    fun nestsMultipleLevels() {
        val root = build(
            FakeClassData(type = "Lcom/example/Outer;"),
            FakeClassData(type = "Lcom/example/Outer\$Inner;"),
            FakeClassData(type = "Lcom/example/Outer\$Inner\$Deep;"),
        )
        val outer = root.findClass("com.example.Outer")!!
        val inner = root.findClass("com.example.Outer\$Inner")!!
        val deep = root.findClass("com.example.Outer\$Inner\$Deep")!!

        assertSame(inner, outer.innerClasses.single())
        assertSame(deep, inner.innerClasses.single())
        assertSame(inner, deep.outerClass)
        assertSame(outer, inner.outerClass)
        assertTrue(deep.innerClasses.isEmpty())
        assertEquals(listOf("com.example.Outer"), topLevel(root).map { it.fullName })
    }

    @Test
    fun leavesNonInnerDollarNameTopLevelWhenOuterAbsent() {
        // A '$' in the name but no matching outer class in the model: non-lossy — keep it top-level.
        val root = build(FakeClassData(type = "Lcom/example/Weird\$Name;"))
        val cls = root.findClass("com.example.Weird\$Name")!!
        assertNull(cls.outerClass)
        assertEquals(listOf("com.example.Weird\$Name"), topLevel(root).map { it.fullName })
    }

    @Test
    fun innerClassAnnotationSuppliesMemberModifiers() {
        // class_def flags for a nested class omit static/private; the InnerClass annotation carries them.
        val static = 0x0001 or 0x0008 or 0x0010 // public static final
        val root = build(
            FakeClassData(type = "Lcom/example/R;", accessFlags = 0x0011),
            FakeClassData(
                type = "Lcom/example/R\$color;",
                accessFlags = 0, // class_def has no modifiers for the member class
                annotations = listOf(innerClassAnnotation("color", static)),
            ),
        )
        val inner = root.findClass("com.example.R\$color")!!
        assertEquals(static, inner.accessFlags)
        assertSame(root.findClass("com.example.R"), inner.outerClass)
    }

    @Test
    fun doesNotDropClassesOnCyclicEnclosure() {
        // Hostile input: A and B claim each other as enclosing class. Nesting must not loop or lose a class.
        val root = build(
            FakeClassData(type = "Lcom/example/A;", annotations = listOf(enclosingClassAnnotation("Lcom/example/B;"))),
            FakeClassData(type = "Lcom/example/B;", annotations = listOf(enclosingClassAnnotation("Lcom/example/A;"))),
        )
        // Both classes survive; the cycle guard keeps at least one on the top level (no infinite structure).
        assertEquals(2, root.classes.size)
        assertTrue(topLevel(root).isNotEmpty())
    }
}
