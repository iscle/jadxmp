package com.jadxmp.ir.insn

import com.jadxmp.ir.attr.AttrKey
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.type.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SemanticInstructionsTest {

    private fun reg(n: Int, t: IrType = IrType.INT) = RegisterOperand(n, t)
    private val fooType = IrType.objectType("com.example.Foo")

    // ---- refs ----

    @Test
    fun methodRefFieldsAndPredicates() {
        val m = MethodRef(fooType, "bar", IrType.INT, listOf(IrType.STRING, IrType.BOOLEAN))
        assertEquals(fooType, m.declaringType)
        assertEquals("bar", m.name)
        assertEquals(IrType.INT, m.returnType)
        assertEquals(listOf(IrType.STRING, IrType.BOOLEAN), m.paramTypes)
        assertTrue(!m.isConstructor && !m.isStaticInit)

        val ctor = MethodRef(fooType, "<init>", IrType.VOID, emptyList())
        assertTrue(ctor.isConstructor)
        val clinit = MethodRef(fooType, "<clinit>", IrType.VOID, emptyList())
        assertTrue(clinit.isStaticInit)
    }

    @Test
    fun refsAreValueTypes() {
        assertEquals(
            MethodRef(fooType, "bar", IrType.INT, listOf(IrType.STRING)),
            MethodRef(fooType, "bar", IrType.INT, listOf(IrType.STRING)),
        )
        assertEquals(FieldRef(fooType, "count", IrType.INT), FieldRef(fooType, "count", IrType.INT))
    }

    @Test
    fun invokeKindFlags() {
        assertTrue(InvokeKind.STATIC.isStatic)
        assertTrue(InvokeKind.VIRTUAL.hasInstance)
        assertTrue(!InvokeKind.STATIC.hasInstance)
        assertTrue(!InvokeKind.CUSTOM.hasInstance)
        assertEquals(7, InvokeKind.entries.size)
    }

    // ---- invoke ----

    @Test
    fun invokeInstructionCarriesRefAndReceiver() {
        val recv = reg(0, fooType)
        val arg = reg(1)
        val m = MethodRef(fooType, "bar", IrType.INT, listOf(IrType.INT))
        val insn = InvokeInstruction(m, InvokeKind.VIRTUAL, result = reg(2), args = listOf(recv, arg))
        assertEquals(IrOpcode.INVOKE, insn.opcode)
        assertSame(m, insn.methodRef)
        assertEquals(InvokeKind.VIRTUAL, insn.invokeKind)
        assertTrue(insn.hasInstance)
        assertSame(recv, insn.instanceArg)
        assertSame(insn, recv.parent) // real Instruction: operand back-links maintained
    }

    @Test
    fun staticInvokeHasNoReceiver() {
        val insn = InvokeInstruction(
            MethodRef(fooType, "of", fooType, emptyList()),
            InvokeKind.STATIC,
            result = reg(0, fooType),
        )
        assertTrue(insn.isStatic)
        assertNull(insn.instanceArg)
    }

    @Test
    fun invokeCanBeConstructorOpcode() {
        val insn = InvokeInstruction(
            MethodRef(fooType, "<init>", IrType.VOID, emptyList()),
            InvokeKind.DIRECT,
            opcode = IrOpcode.CONSTRUCTOR,
        )
        assertEquals(IrOpcode.CONSTRUCTOR, insn.opcode)
        assertTrue(insn.methodRef.isConstructor)
    }

    @Test
    fun invokeRejectsWrongOpcode() {
        assertFailsWith<IllegalArgumentException> {
            InvokeInstruction(
                MethodRef(fooType, "x", IrType.VOID, emptyList()),
                InvokeKind.STATIC,
                opcode = IrOpcode.MOVE,
            )
        }
    }

    // ---- field ----

    @Test
    fun fieldInstructionDerivesOpcode() {
        val f = FieldRef(fooType, "count", IrType.INT)
        assertEquals(IrOpcode.INSTANCE_GET, FieldInstruction(f, isStatic = false, isPut = false).opcode)
        assertEquals(IrOpcode.INSTANCE_PUT, FieldInstruction(f, isStatic = false, isPut = true).opcode)
        assertEquals(IrOpcode.STATIC_GET, FieldInstruction(f, isStatic = true, isPut = false).opcode)
        assertEquals(IrOpcode.STATIC_PUT, FieldInstruction(f, isStatic = true, isPut = true).opcode)

        val insn = FieldInstruction(f, isStatic = false, isPut = false, result = reg(0))
        assertSame(f, insn.fieldRef)
    }

    // ---- const-string ----

    @Test
    fun constStringCarriesValue() {
        val insn = ConstStringInstruction("hello", result = reg(0, IrType.STRING))
        assertEquals(IrOpcode.CONST_STRING, insn.opcode)
        assertEquals("hello", insn.value)
    }

    // ---- type ref ----

    @Test
    fun typeInstructionCarriesReferencedType() {
        val insn = TypeInstruction(IrOpcode.CHECK_CAST, fooType, result = reg(0, fooType), args = listOf(reg(1)))
        assertEquals(IrOpcode.CHECK_CAST, insn.opcode)
        assertEquals(fooType, insn.referencedType)

        val newInstance = TypeInstruction(IrOpcode.NEW_INSTANCE, fooType, result = reg(0, fooType))
        assertEquals(IrOpcode.NEW_INSTANCE, newInstance.opcode)
    }

    @Test
    fun newArrayReferencedTypeIsWholeArrayType() {
        // referencedType is the ARRAY type; codegen derives the element via arrayElement.
        val arrType = IrType.array(IrType.INT)
        val insn = TypeInstruction(IrOpcode.NEW_ARRAY, arrType, result = reg(0, arrType), args = listOf(reg(1)))
        assertEquals(arrType, insn.referencedType)
        assertEquals(IrType.INT, insn.referencedType.arrayElement)
    }

    @Test
    fun typeInstructionRejectsWrongOpcode() {
        assertFailsWith<IllegalArgumentException> {
            TypeInstruction(IrOpcode.MOVE, fooType, result = reg(0))
        }
    }

    // ---- switch ----

    @Test
    fun switchInstructionTablesAreMutable() {
        val selector = reg(0)
        val insn = SwitchInstruction(
            keys = intArrayOf(1, 2),
            caseTargets = intArrayOf(0x10, 0x20),
            defaultTarget = 0x30,
            selector = selector,
        )
        assertEquals(IrOpcode.SWITCH, insn.opcode)
        assertSame(selector, insn.selector)
        assertTrue(insn.keys.contentEquals(intArrayOf(1, 2)))
        // second-pass fill
        insn.keys = intArrayOf(1, 2, 3)
        insn.caseTargets = intArrayOf(0x10, 0x20, 0x28)
        insn.defaultTarget = 0x40
        assertEquals(3, insn.keys.size)
        assertEquals(0x40, insn.defaultTarget)
    }

    // ---- phi ----

    @Test
    fun phiInstructionTracksIncomingEdges() {
        val b1 = BasicBlock(1)
        val b2 = BasicBlock(2)
        val phi = PhiInstruction(result = reg(0))
        val v1 = phi.addIncoming(regNum = 0, type = IrType.INT, from = b1)
        val v2 = phi.addIncoming(regNum = 0, type = IrType.INT, from = b2)

        assertEquals(IrOpcode.PHI, phi.opcode)
        assertEquals(2, phi.argCount) // incoming values are real operands
        assertSame(phi, v1.parent)
        assertSame(b1, phi.blockFor(0))
        assertEquals(listOf(PhiIncoming(v1, b1), PhiIncoming(v2, b2)), phi.incoming)
    }

    @Test
    fun phiEdgesStayAlignedUnderBaseMutation() {
        // Adversarial: removing an edge via the base removeArg API must NOT desync value↔block.
        val b1 = BasicBlock(1)
        val b2 = BasicBlock(2)
        val b3 = BasicBlock(3)
        val phi = PhiInstruction(result = reg(0))
        phi.addIncoming(regNum = 0, type = IrType.INT, from = b1)
        val v2 = phi.addIncoming(regNum = 1, type = IrType.INT, from = b2)
        val v3 = phi.addIncoming(regNum = 2, type = IrType.INT, from = b3)

        // remove the FIRST edge through the inherited base API (as SSA prune passes do)
        phi.removeArg(0)
        assertEquals(2, phi.argCount)
        assertSame(b2, phi.blockFor(0)) // block travels with its value — no drift
        assertSame(v2, phi.getArg(0))
        assertEquals(listOf(PhiIncoming(v2, b2), PhiIncoming(v3, b3)), phi.incoming)

        // remove by predecessor block
        assertTrue(phi.removeIncoming(b2))
        assertEquals(listOf(PhiIncoming(v3, b3)), phi.incoming)
        assertSame(b3, phi.blockFor(0))
    }

    @Test
    fun phiRejectsPlainOperandAtEveryWritePoint() {
        // A uniform SSA-renaming / copy-propagation pass rewrites operands via the base API; a plain
        // RegisterOperand (no predecessor block) must be rejected AT THE WRITE, not crash later.
        val b1 = BasicBlock(1)
        val phi = PhiInstruction(result = reg(0))
        val edge = phi.addIncoming(regNum = 0, type = IrType.INT, from = b1)
        val plain = reg(9)
        assertFailsWith<IllegalArgumentException> { phi.addArg(plain) }
        assertFailsWith<IllegalArgumentException> { phi.setArg(0, plain) }
        assertFailsWith<IllegalArgumentException> { phi.replaceArg(edge, plain) }
        // the φ is untouched and still consistent
        assertEquals(1, phi.argCount)
        assertSame(edge, phi.getArg(0))
        assertSame(b1, phi.blockFor(0))
    }

    @Test
    fun phiOperandIsARegisterOperandForSsaUseLists() {
        val op = PhiOperand(regNum = 4, type = IrType.INT, from = BasicBlock(1))
        val asRegister: RegisterOperand = op // compile-time proof it participates in SSA machinery
        assertEquals(4, asRegister.regNum)
    }

    // ---- fill-array ----

    @Test
    fun fillArrayInstructionCarriesElementsWidthAndArray() {
        val array = reg(1, IrType.array(IrType.LONG))
        val insn = FillArrayInstruction(elementWidth = 8, elements = longArrayOf(1, 2, -3), array = array)
        assertEquals(IrOpcode.FILL_ARRAY, insn.opcode)
        assertEquals(8, insn.elementWidth)
        assertEquals(3, insn.size)
        assertTrue(insn.elements.contentEquals(longArrayOf(1, 2, -3)))
        assertSame(array, insn.array) // the array is operand 0
        assertSame(insn, array.parent) // real Instruction: operand back-link maintained
        assertNull(insn.result)
    }

    @Test
    fun fillArrayRejectsBadElementWidth() {
        val array = reg(1, IrType.array(IrType.INT))
        assertFailsWith<IllegalArgumentException> {
            FillArrayInstruction(elementWidth = 3, elements = longArrayOf(1), array = array)
        }
    }

    // ---- integration with the node model ----

    @Test
    fun semanticInstructionsAreRealInstructions() {
        val block = BasicBlock(0)
        val invoke = InvokeInstruction(
            MethodRef(fooType, "run", IrType.VOID, emptyList()),
            InvokeKind.VIRTUAL,
            args = listOf(reg(0, fooType)),
        )
        block.instructions.add(invoke)
        assertSame(invoke, block.instructions[0])

        // usable as a nested sub-expression operand
        val wrap = InstructionOperand(invoke)
        assertTrue(wrap.isNested)
        assertSame(invoke, wrap.instruction)

        // carries attributes like any AttrNode
        val key = AttrKey<String>("note")
        invoke[key] = "x"
        assertEquals("x", invoke[key])
    }
}
