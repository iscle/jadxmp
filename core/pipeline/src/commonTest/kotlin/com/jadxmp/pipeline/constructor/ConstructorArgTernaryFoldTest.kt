package com.jadxmp.pipeline.constructor

import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.InstructionOperand
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.node.BasicBlock
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.region.IfRegion
import com.jadxmp.ir.region.Region
import com.jadxmp.ir.region.SequenceRegion
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.constructor.ConstructorReconstruction
import com.jadxmp.pipeline.structure.ExpressionShaping
import com.jadxmp.pipeline.structure.OutOfSsa
import com.jadxmp.pipeline.structure.RegionMaker
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The default-arguments synthetic-constructor fold: guarded argument mutations before a `this()`/
 * `super()` delegation become ternary arguments (jadx's `this(a, cond ? x : y, …)`), never illegal
 * statements before the delegation. A constructor WITHOUT that shape must be left untouched.
 */
class ConstructorArgTernaryFoldTest {

    private fun runPipeline(method: IrMethod) {
        TestPipeline.full(method)
        ConstructorReconstruction(method).run()
        OutOfSsa(method).run()
        ExpressionShaping(method).run()
        RegionMaker(method).run()
        ConstructorArgTernaryFold(method).run()
    }

    private fun delegation(method: IrMethod): InvokeInstruction =
        method.blocks.flatMap { it.instructions }
            .filterIsInstance<InvokeInstruction>()
            .single { it.methodRef.isConstructor }

    private fun ternaryArg(invoke: InvokeInstruction, index: Int): Boolean {
        val op = invoke.getArg(index)
        return op is InstructionOperand && op.instruction.opcode == IrOpcode.TERNARY
    }

    private fun regionChildren(method: IrMethod): List<Any> =
        (method.region as? SequenceRegion)?.children ?: emptyList()

    /** Emitting = would render a statement (ignores φ/goto and consumed branch `if`s). */
    private fun emittingBefore(method: IrMethod, invoke: InvokeInstruction): Int {
        var count = 0
        for (child in regionChildren(method)) {
            if (child is BasicBlock) {
                if (child.instructions.contains(invoke)) break
                count += child.instructions.count { insn ->
                    insn.opcode !in setOf(IrOpcode.GOTO, IrOpcode.NOP, IrOpcode.PHI, IrOpcode.IF, IrOpcode.RETURN) &&
                        insn !is InvokeInstruction
                }
            }
        }
        return count
    }

    @Test
    fun guardedDefaultArgsBecomeTernaryDelegationArgs() {
        val ctorRef = FakeMethodRef(
            "Lconditions/Foo;", "<init>", "V",
            listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;", "Z"),
        )
        // v0 local; v1=this; v2=str; v3=str2; v4=str3; v5=z; v6=i; v7=i2
        val reader = FakeCodeReader(
            8,
            listOf(
                Insn(Opcode.AND_INT_LIT, 0, intArrayOf(7, 6), literal = 2),
                Insn(Opcode.CONST_STRING, 1, intArrayOf(0), indexType = IndexType.STRING_REF, stringValue = ""),
                Insn(Opcode.IF_EQZ, 2, intArrayOf(7), target = 4),
                Insn(Opcode.MOVE_OBJECT, 3, intArrayOf(3, 0)),
                Insn(Opcode.AND_INT_LIT, 4, intArrayOf(7, 6), literal = 4),
                Insn(Opcode.IF_EQZ, 5, intArrayOf(7), target = 7),
                Insn(Opcode.MOVE_OBJECT, 6, intArrayOf(4, 0)),
                Insn(Opcode.AND_INT_LIT, 7, intArrayOf(6, 6), literal = 8),
                Insn(Opcode.IF_EQZ, 8, intArrayOf(6), target = 10),
                Insn(Opcode.CONST, 9, intArrayOf(5), literal = 0),
                Insn(
                    Opcode.INVOKE_DIRECT, 10, intArrayOf(1, 2, 3, 4, 5),
                    indexType = IndexType.METHOD_REF, methodRef = ctorRef,
                ),
                Insn(Opcode.RETURN_VOID, 11),
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            className = "conditions.Foo",
            methodName = "<init>",
            returnType = IrType.VOID,
            argTypes = listOf(IrType.STRING, IrType.STRING, IrType.STRING, IrType.BOOLEAN, IrType.INT, IrType.INT),
            isStatic = false,
        )
        runPipeline(method)

        val deleg = delegation(method)
        assertEquals(5, deleg.argCount, "receiver + 4 delegation args")
        // args: [this, str, ternary, ternary, ternary]
        assertTrue(ternaryArg(deleg, 2), "str2 arg folded to ternary")
        assertTrue(ternaryArg(deleg, 3), "str3 arg folded to ternary")
        assertTrue(ternaryArg(deleg, 4), "z arg folded to ternary")

        assertTrue(regionChildren(method).none { it is IfRegion }, "guard if-regions removed")
        assertEquals(0, emittingBefore(method, deleg), "no statement remains before the delegation")
    }

    @Test
    fun crossGuardDependencyIsNotFolded() {
        // Guard A assigns delegation-arg `r`; guard B's CONDITION reads `r`. Folding both would make B's
        // ternary read the stale pre-guard `r` (guard A's assignment is removed) — a reordering miscompile.
        // The pass must leave the whole method untouched.
        val ctorRef = FakeMethodRef("Lconditions/Foo;", "<init>", "V", listOf("I", "Ljava/lang/String;"))
        // v0 local; v1=this; v2=p; v3=r; v4=s
        val reader = FakeCodeReader(
            5,
            listOf(
                Insn(Opcode.CONST_STRING, 0, intArrayOf(0), indexType = IndexType.STRING_REF, stringValue = ""),
                Insn(Opcode.IF_EQZ, 1, intArrayOf(2), target = 3), // if (p != 0) r = 0
                Insn(Opcode.CONST, 2, intArrayOf(3), literal = 0),
                Insn(Opcode.IF_EQZ, 3, intArrayOf(3), target = 5), // if (r != 0) s = ""
                Insn(Opcode.MOVE_OBJECT, 4, intArrayOf(4, 0)),
                Insn(
                    Opcode.INVOKE_DIRECT, 5, intArrayOf(1, 3, 4),
                    indexType = IndexType.METHOD_REF, methodRef = ctorRef,
                ),
                Insn(Opcode.RETURN_VOID, 6),
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            className = "conditions.Foo",
            methodName = "<init>",
            returnType = IrType.VOID,
            argTypes = listOf(IrType.INT, IrType.INT, IrType.STRING),
            isStatic = false,
        )
        runPipeline(method)

        val deleg = delegation(method)
        assertTrue((0 until deleg.argCount).none { ternaryArg(deleg, it) }, "cross-guard shape must not fold")
        assertTrue(regionChildren(method).any { it is IfRegion }, "guard if-regions remain (untouched)")
    }

    @Test
    fun duplicatedArgRegisterIsNotFolded() {
        // A guarded register `r` feeds TWO delegation arg slots (`this(r, r)`). Folding only the first slot
        // would leave the second reading the unguarded original — a silent miscompile. Bail.
        val ctorRef = FakeMethodRef("Lconditions/Foo;", "<init>", "V", listOf("I", "I"))
        // v0=this; v1=p; v2=r
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(1), target = 2), // if (p != 0) r = 0
                Insn(Opcode.CONST, 1, intArrayOf(2), literal = 0),
                Insn(
                    Opcode.INVOKE_DIRECT, 2, intArrayOf(0, 2, 2),
                    indexType = IndexType.METHOD_REF, methodRef = ctorRef,
                ),
                Insn(Opcode.RETURN_VOID, 3),
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            className = "conditions.Foo",
            methodName = "<init>",
            returnType = IrType.VOID,
            argTypes = listOf(IrType.INT, IrType.INT),
            isStatic = false,
        )
        runPipeline(method)

        val deleg = delegation(method)
        assertTrue((0 until deleg.argCount).none { ternaryArg(deleg, it) }, "this(x, x) shape must not fold")
    }

    @Test
    fun plainSuperDelegationIsUntouched() {
        // A constructor with a plain `super()` and no pre-delegation guards must be left alone.
        val ctorRef = FakeMethodRef("Ljava/lang/Object;", "<init>", "V", emptyList())
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_DIRECT, 0, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = ctorRef),
                Insn(Opcode.RETURN_VOID, 1),
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            className = "conditions.Bar",
            methodName = "<init>",
            returnType = IrType.VOID,
            isStatic = false,
        )
        runPipeline(method)

        val deleg = delegation(method)
        assertEquals(1, deleg.argCount, "just the receiver")
        assertTrue((0 until deleg.argCount).none { ternaryArg(deleg, it) }, "no ternary synthesized")
    }
}
