package com.jadxmp.ir.node

import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NodeModelTest {

    private fun buildClass(): IrClass {
        val root = IrRoot()
        val cls = IrClass(root, "com.example.Foo", accessFlags = 0)
        root.addClass(cls)
        return cls
    }

    @Test
    fun rootIndexesClassesByName() {
        val root = IrRoot()
        val cls = IrClass(root, "com.example.Foo", 0)
        root.addClass(cls)
        assertSame(cls, root.findClass("com.example.Foo"))
        assertNull(root.findClass("com.example.Missing"))
    }

    @Test
    fun classShortName() {
        val root = IrRoot()
        assertEquals("Foo", IrClass(root, "com.example.Foo", 0).shortName)

        // A '$'-containing name with NO outer set is a class that couldn't be nested: it stays top-level
        // and keeps the full simple name ('$' included) so `class Foo$Inner` lands in `Foo$Inner.java`.
        assertEquals("Foo\$Inner", IrClass(root, "com.example.Foo\$Inner", 0).shortName)

        // Once actually nested under an outer, the emitted name is the segment after the LAST '$'.
        val outer = IrClass(root, "com.example.Foo", 0)
        val inner = IrClass(root, "com.example.Foo\$Inner", 0).apply { outerClass = outer }
        val deep = IrClass(root, "com.example.Foo\$Inner\$Deep", 0).apply { outerClass = inner }
        assertEquals("Inner", inner.shortName)
        assertEquals("Deep", deep.shortName)
    }

    @Test
    fun innerClassNestingRoundTrips() {
        val root = IrRoot()
        val outer = IrClass(root, "com.example.Outer", 0)
        val inner = IrClass(root, "com.example.Outer\$Inner", 0)
        val deep = IrClass(root, "com.example.Outer\$Inner\$Deep", 0)
        root.addClass(outer)
        root.addClass(inner)
        root.addClass(deep)

        // Wire the two-level nesting: Outer { Inner { Deep } }.
        outer.innerClasses.add(inner)
        inner.outerClass = outer
        inner.innerClasses.add(deep)
        deep.outerClass = inner

        // Forward links (outer owns its inners) and reverse links (inner points back) agree.
        assertSame(inner, outer.innerClasses.single())
        assertSame(outer, inner.outerClass)
        assertSame(deep, inner.innerClasses.single())
        assertSame(inner, deep.outerClass)

        // A top-level class has no outer; every class stays findable by its full binary name.
        assertNull(outer.outerClass)
        assertTrue(outer.innerClasses.isNotEmpty())
        assertSame(deep, root.findClass("com.example.Outer\$Inner\$Deep"))

        // The emitted simple name is the last '$' segment even for a deeply nested class.
        assertEquals("Outer", outer.shortName)
        assertEquals("Inner", inner.shortName)
        assertEquals("Deep", deep.shortName)
    }

    @Test
    fun fieldConstValueRoundTrips() {
        val cls = buildClass()

        // no constant by default
        val plain = IrField(cls, "x", IrType.INT, 0)
        assertNull(plain.constValue)

        // primitive constant: bit pattern + type, mirroring LiteralOperand
        val maxInt = IrField(cls, "MAX", IrType.INT, 0)
        maxInt.constValue = IrFieldConst.Primitive(2147483647L, IrType.INT)
        val prim = maxInt.constValue
        assertTrue(prim is IrFieldConst.Primitive)
        assertEquals(2147483647L, prim.bits)
        assertEquals(IrType.INT, prim.type)

        // String constant
        val greeting = IrField(cls, "GREETING", IrType.STRING, 0)
        greeting.constValue = IrFieldConst.Str("hello")
        val str = greeting.constValue
        assertTrue(str is IrFieldConst.Str)
        assertEquals("hello", str.value)
    }

    @Test
    fun methodStaticFlag() {
        val cls = buildClass()
        val static = IrMethod(cls, "m", IrType.VOID, emptyList(), IrMethod.ACC_STATIC)
        val instance = IrMethod(cls, "n", IrType.VOID, emptyList(), 0)
        assertTrue(static.isStatic)
        assertFalse(instance.isStatic)
    }

    @Test
    fun ssaValueSingleDefManyUses() {
        val def = RegisterOperand(1, IrType.INT)
        val ssa = SsaValue(regNum = 1, version = 0, assign = def)
        assertSame(ssa, def.ssaValue)

        val u1 = RegisterOperand(1, IrType.INT)
        val u2 = RegisterOperand(1, IrType.INT)
        ssa.addUse(u1)
        ssa.addUse(u2)
        assertEquals(2, ssa.useCount)
        assertSame(ssa, u1.ssaValue)

        ssa.removeUse(u1)
        assertEquals(1, ssa.useCount)
    }

    @Test
    fun ssaValueTypeCellNarrows() {
        val ssa = SsaValue(0, 0, RegisterOperand(0, IrType.UNKNOWN))
        assertEquals(IrType.UNKNOWN, ssa.type)
        assertEquals(NarrowResult.NARROWED, ssa.typeCell.narrow(IrType.NARROW_INTEGRAL))
        assertEquals(IrType.NARROW_INTEGRAL, ssa.type)
        assertEquals(NarrowResult.NARROWED, ssa.typeCell.narrow(IrType.INT))
        assertEquals(IrType.INT, ssa.type)
        // narrowing to something already implied is a no-op
        assertEquals(NarrowResult.UNCHANGED, ssa.typeCell.narrow(IrType.INT))
    }

    @Test
    fun typeCellRespectsImmutability() {
        val cell = TypeCell()
        cell.fix(IrType.STRING)
        assertTrue(cell.immutable)
        assertEquals(NarrowResult.LOCKED, cell.narrow(IrType.OBJECT))
        assertEquals(IrType.STRING, cell.type)
    }

    @Test
    fun typeCellConflictIsSignalledDistinctly() {
        val cell = TypeCell(IrType.WIDE)
        assertEquals(NarrowResult.CONFLICT, cell.narrow(IrType.NARROW_INTEGRAL)) // disjoint ⇒ no meet
        assertEquals(IrType.WIDE, cell.type)
    }

    @Test
    fun localVarCollapsesSsaValues() {
        val v0 = SsaValue(0, 0, RegisterOperand(0, IrType.INT))
        val v1 = SsaValue(0, 1, RegisterOperand(0, IrType.INT))
        val local = LocalVar().apply { name = "i" }
        local.addSsaValue(v0)
        local.addSsaValue(v1)
        assertEquals(2, local.ssaValues.size)
        assertSame(local, v0.localVar)
        assertSame(local, v1.localVar)
    }

    @Test
    fun basicBlockEdgesAndDominators() {
        val entry = BasicBlock(0)
        val body = BasicBlock(1)
        entry.successors.add(body)
        body.predecessors.add(entry)
        body.immediateDominator = entry
        entry.dominatedBlocks.add(body)
        assertSame(entry, body.immediateDominator)
        assertSame(body, entry.successors[0])
        assertSame(entry, body.predecessors[0])
    }
}
