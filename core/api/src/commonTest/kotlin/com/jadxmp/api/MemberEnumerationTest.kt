package com.jadxmp.api

import com.jadxmp.codegen.ClassNodeRef
import com.jadxmp.codegen.FieldNodeRef
import com.jadxmp.codegen.MethodNodeRef
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrField
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-model unit tests for member enumeration ([membersOf], which backs [Decompiler.classMembers]).
 * Builds a synthetic [IrClass] directly — no dex fixture, no decompilation — to lock the ordering,
 * display names/signatures, modifiers, synthetic filtering, and the metadata-aligned member keys.
 * commonTest so it runs on every target (the tree/search run in-browser too).
 */
class MemberEnumerationTest {

    private companion object {
        const val PUBLIC = 0x0001
        const val PRIVATE = 0x0002
        const val STATIC = 0x0008
        const val FINAL = 0x0010
        const val SYNTHETIC = 0x1000
        const val BRIDGE = 0x0040 // methods
        const val VOLATILE = 0x0040 // fields (same bit as BRIDGE)
    }

    /** A class with two fields, a constructor, a normal method, a static initializer and a nested class. */
    private fun buildModel(): IrClass {
        val root = IrRoot()
        val cls = IrClass(root, "com.example.Widget", PUBLIC)
        root.addClass(cls)

        cls.fields.add(IrField(cls, "count", IrType.INT, PRIVATE))
        cls.fields.add(IrField(cls, "name", IrType.STRING, PRIVATE or FINAL))

        // Constructor Widget(String).
        cls.methods.add(IrMethod(cls, "<init>", IrType.VOID, listOf(IrType.STRING), PUBLIC))
        // boolean process(int, String).
        cls.methods.add(
            IrMethod(cls, "process", IrType.BOOLEAN, listOf(IrType.INT, IrType.STRING), PUBLIC),
        )
        // static void main(String[]).
        cls.methods.add(
            IrMethod(cls, "main", IrType.VOID, listOf(IrType.array(IrType.STRING)), PUBLIC or STATIC),
        )
        // static initializer.
        cls.methods.add(IrMethod(cls, "<clinit>", IrType.VOID, emptyList(), STATIC))
        // A synthetic bridge method that must be filtered out (never a real source member).
        cls.methods.add(
            IrMethod(cls, "process", IrType.OBJECT, listOf(IrType.INT, IrType.STRING), PUBLIC or SYNTHETIC or BRIDGE),
        )

        val inner = IrClass(root, "com.example.Widget\$Handler", PUBLIC or STATIC)
        inner.outerClass = cls
        cls.innerClasses.add(inner)
        root.addClass(inner)

        return cls
    }

    @Test
    fun enumeratesMembersInFieldsThenMethodsThenNestedOrder() {
        val members = membersOf(buildModel())
        // Synthetic bridge dropped; declaration order preserved within each group.
        assertContentEquals(
            listOf(
                MemberKind.FIELD, MemberKind.FIELD,
                MemberKind.CONSTRUCTOR, MemberKind.METHOD, MemberKind.METHOD, MemberKind.STATIC_INITIALIZER,
                MemberKind.NESTED_CLASS,
            ),
            members.map { it.kind },
        )
        assertContentEquals(
            listOf("count", "name", "Widget", "process", "main", "static", "Handler"),
            members.map { it.displayName },
        )
    }

    @Test
    fun rendersReadableSignatures() {
        val members = membersOf(buildModel()).associateBy { it.signature }
        assertTrue(members.containsKey("count: int"), "fields render name: type")
        assertTrue(members.containsKey("name: String"), "object type uses simple name")
        assertTrue(members.containsKey("Widget(String)"), "constructor uses the class name, no return")
        assertTrue(members.containsKey("process(int, String): boolean"), "method renders params and return")
        assertTrue(members.containsKey("main(String[]): void"), "array param renders as T[]")
        assertTrue(members.containsKey("static { … }"), "static initializer rendered as a block")
    }

    @Test
    fun extractsSourceModifiers() {
        val byName = membersOf(buildModel()).associateBy { it.displayName }
        assertEquals(setOf(Modifier.PRIVATE), byName.getValue("count").modifiers)
        assertEquals(setOf(Modifier.PRIVATE, Modifier.FINAL), byName.getValue("name").modifiers)
        assertEquals(setOf(Modifier.PUBLIC, Modifier.STATIC), byName.getValue("main").modifiers)
    }

    @Test
    fun filtersSyntheticButNotRealMembers() {
        val members = membersOf(buildModel())
        // Exactly one `process` survives (the real one); the synthetic bridge overload is gone.
        assertEquals(1, members.count { it.displayName == "process" })
        assertFalse(members.any { it.isSynthetic }, "no surviving member is synthetic")
    }

    @Test
    fun volatileFieldSurvivesButGenuineBridgeMethodIsDropped() {
        // ACC_VOLATILE(0x0040) on a field is the SAME bit as ACC_BRIDGE(0x0040) on a method. The synthetic
        // filter must be field/method-aware, or `volatile` fields get silently dropped (rule-4 loss).
        val root = IrRoot()
        val cls = IrClass(root, "com.example.Sync", PUBLIC)
        root.addClass(cls)
        cls.fields.add(IrField(cls, "counter", IrType.INT, PRIVATE or VOLATILE))
        // A real bridge method (ACC_BRIDGE|ACC_SYNTHETIC) — must be dropped.
        cls.methods.add(
            IrMethod(cls, "compareTo", IrType.INT, listOf(IrType.OBJECT), PUBLIC or BRIDGE or SYNTHETIC),
        )

        val members = membersOf(cls)
        val counter = members.singleOrNull { it.displayName == "counter" }
        assertTrue(counter != null, "volatile field must survive enumeration: $members")
        assertFalse(counter.isSynthetic, "a volatile field is not synthetic")
        assertTrue(Modifier.VOLATILE in counter.modifiers, "volatile modifier reported: ${counter.modifiers}")
        assertTrue(members.none { it.displayName == "compareTo" }, "genuine bridge method must be dropped")
    }

    @Test
    fun memberKeysMatchTheDecompileMetadataKeying() {
        val cls = buildModel()
        val byName = membersOf(cls).associateBy { it.displayName }
        // Field key == JavaCodeGenerator's FieldNodeRef(binaryOwner, name).
        assertEquals(FieldNodeRef("com.example.Widget", "count"), byName.getValue("count").key)
        // Method key == MethodNodeRef(binaryOwner, rawName, argTypes.map { it.toString() }).
        assertEquals(
            MethodNodeRef("com.example.Widget", "main", listOf("java.lang.String[]")),
            byName.getValue("main").key,
        )
        // Constructor keeps its raw <init> name in the key (display shows the class name).
        assertEquals(
            MethodNodeRef("com.example.Widget", "<init>", listOf("java.lang.String")),
            byName.getValue("Widget").key,
        )
        // Nested class key == ClassNodeRef(binaryFullName).
        assertEquals(ClassNodeRef("com.example.Widget\$Handler"), byName.getValue("Handler").key)
    }
}
