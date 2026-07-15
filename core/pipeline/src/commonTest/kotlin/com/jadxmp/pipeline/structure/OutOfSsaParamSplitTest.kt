package com.jadxmp.pipeline.structure

import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.PhiInstruction
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.SsaValue
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.PipelineAttrs
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import com.jadxmp.pipeline.types.ClassHierarchy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Out-of-SSA must not coalesce a **type-fixed parameter** with φ-component members whose type its
 * signature type cannot hold. A merged local containing a parameter is rendered under the parameter's
 * fixed name and type (it cannot be widened), so folding an incompatible value into it emits an
 * uncompilable cross-type store (`String p0 = Integer.valueOf(...)`). Such a parameter is split out —
 * it keeps its own local; the remaining members coalesce into a join-typed local pre-assigned
 * `merged = p0`, reproducing the φ's parameter edge without any critical-edge copy. (The end-to-end
 * oracle is types/TestTypeResolver10.smali.)
 */
class OutOfSsaParamSplitTest {

    private val STRING = IrType.objectType("java.lang.String")
    private val INTEGER = IrType.objectType("java.lang.Integer")

    /** The block holding the `merged = param` pre-assign MOVE (a 1-arg MOVE reading [param]), or null. */
    private fun preAssignBlock(method: IrMethod, param: SsaValue): com.jadxmp.ir.node.BasicBlock? {
        for (b in method.blocks) {
            for (insn in b.instructions) {
                if (insn.opcode != IrOpcode.MOVE || insn.argCount != 1) continue
                val src = insn.getArg(0) as? RegisterOperand ?: continue
                if (src.ssaValue === param) return b
            }
        }
        return null
    }

    private fun readsParam(method: IrMethod, param: SsaValue): Boolean = preAssignBlock(method, param) != null

    @Test
    fun paramCoalescedWithIncompatibleTypeIsSplitOutNotMiscoalesced() {
        //   static Object m(String p0):   (p0 in reg 1)
        //   0: if (p0 == null) goto B(4) else fall→A(1)
        //   A: reg1 = Integer.valueOf(p0)          (Integer)
        //   B: (p0 unchanged)                      (String)
        //   MERGE: return reg1                      φ(String p0, Integer) ⇒ Object
        val integer = FakeMethodRef("Lcom/example/Foo;", "i", "Ljava/lang/Integer;", listOf("Ljava/lang/String;"))
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(1), target = 4), // if (p0==null) goto B(4) else A(1)
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(1), indexType = IndexType.METHOD_REF, methodRef = integer),
                Insn(Opcode.MOVE_RESULT, 2, intArrayOf(1)), // reg1 = Integer.valueOf(p0)
                Insn(Opcode.GOTO, 3, target = 5), // A → MERGE(5)
                Insn(Opcode.GOTO, 4, target = 5), // B (p0 unchanged) → MERGE(5)
                Insn(Opcode.RETURN, 5, intArrayOf(1)), // MERGE: return φ(reg1)
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.OBJECT, argTypes = listOf(STRING))
        TestPipeline.full(method)
        OutOfSsa(method, hierarchy = ClassHierarchy(method.declaringClass.root)).run()

        assertTrue(
            method.blocks.all { b -> b.instructions.none { it is PhiInstruction } },
            "φ must be removed",
        )

        val param = method[PipelineAttrs.PARAMETERS]!!.first { it.regNum == 1 }
        // The parameter is EXCLUDED from the merged local (it keeps no shared LocalVar here — the codegen
        // bridge downstream gives it its own String parameter local). A plain coalesce would instead have
        // set param.localVar to the merged local; a non-null here would mean the miscompile was NOT split.
        assertTrue(param.localVar == null, "the type-fixed parameter is split out of the merged local")

        // The Integer value coalesces into a join-typed local (a common supertype, here Object) that does
        // NOT contain the parameter — so codegen never emits `String p0 = Integer.valueOf(...)`.
        val integerValue = method.ssaValues.first { it.type == INTEGER }
        val mergedLocal = integerValue.localVar
        assertNotNull(mergedLocal, "the split-out members share a local")
        assertFalse(mergedLocal.ssaValues.contains(param), "the parameter is not a member of the merged local")
        assertTrue(mergedLocal.type == IrType.OBJECT, "the merged local is typed to the join (Object), not String")

        // The parameter's φ-edge contribution is reproduced as a dominating `merged = p0` pre-assignment.
        assertTrue(readsParam(method, param), "a `merged = p0` pre-assignment reproduces the parameter edge")
    }

    @Test
    fun paramMergedWithUnprovableLibraryTypeIsNotSplit() {
        //   static Object m(Base p0):  reg1 = p0. On one branch reg1 = someSub() (com.example.Sub); merge.
        // Base and Sub are both UNLOADED and non-final, so `Sub <: Base` is UNPROVABLE — Sub may genuinely
        // BE a Base (the rx.c.c/rx.i corpus shape). Splitting on a merely-unprovable relation would widen
        // the local to Object and diverge from the oracle, so the parameter must stay COALESCED (not split).
        val sub = FakeMethodRef("Lcom/example/Foo;", "s", "Lcom/example/Sub;", emptyList())
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(1), target = 4),
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = sub),
                Insn(Opcode.MOVE_RESULT, 2, intArrayOf(1)), // reg1 = Sub
                Insn(Opcode.GOTO, 3, target = 5),
                Insn(Opcode.GOTO, 4, target = 5), // p0 unchanged
                Insn(Opcode.RETURN, 5, intArrayOf(1)),
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            returnType = IrType.OBJECT,
            argTypes = listOf(IrType.objectType("com.example.Base")),
        )
        TestPipeline.full(method)
        OutOfSsa(method, hierarchy = ClassHierarchy(method.declaringClass.root)).run()

        val param = method[PipelineAttrs.PARAMETERS]!!.first { it.regNum == 1 }
        val paramLocal = param.localVar
        assertNotNull(paramLocal, "an unprovable type relation must NOT split the param (status-quo coalesce)")
        assertTrue(paramLocal.ssaValues.size > 1, "the parameter stays coalesced with the merged members")
        assertFalse(readsParam(method, param), "no `merged = p0` pre-assign is materialized when not split")
    }

    @Test
    fun loopCarriedIncompatibleComponentHoistsPreAssignOutOfTheLoop() {
        //   static Object m(String p0):  reg1 = p0, reassigned to Integer each loop iteration, read after.
        //   PRE:    reg0 = 0
        //   HEADER: if (reg0 == 0) goto EXIT else BODY    φ(reg1) = φ(p0 from PRE, Integer from back-edge)
        //   BODY:   reg1 = Integer.valueOf(); goto HEADER
        //   EXIT:   return reg1
        // String↔Integer is a PROVABLE conflict ⇒ the param is split. The `merged = p0` pre-assign MUST
        // land in the PRE-header (dominating, outside the loop), never the header — otherwise it resets the
        // accumulator every iteration (a silent miscompile).
        val integer = FakeMethodRef("Lcom/example/Foo;", "i", "Ljava/lang/Integer;", emptyList())
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0), // PRE
                Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 5), // HEADER: taken→EXIT(5), fall→BODY(2)
                Insn(Opcode.INVOKE_STATIC, 2, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = integer),
                Insn(Opcode.MOVE_RESULT, 3, intArrayOf(1)), // reg1 = Integer
                Insn(Opcode.GOTO, 4, target = 1), // back-edge → HEADER
                Insn(Opcode.RETURN, 5, intArrayOf(1)), // EXIT: return reg1
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.OBJECT, argTypes = listOf(STRING))
        TestPipeline.full(method)
        OutOfSsa(method, hierarchy = ClassHierarchy(method.declaringClass.root)).run()

        val param = method[PipelineAttrs.PARAMETERS]!!.first { it.regNum == 1 }
        val moveBlock = preAssignBlock(method, param)
        assertNotNull(moveBlock, "the `merged = p0` pre-assign is materialized (the split fired)")
        // The loop header is the back-edge target (a block that dominates one of its predecessors).
        val header = method.blocks.first { h -> h.predecessors.any { p -> h.id in p.dominators } }
        assertFalse(moveBlock === header, "the pre-assign must NOT sit in the loop header (would reset each iteration)")
        assertTrue(moveBlock.id in header.dominators, "the pre-assign block must dominate the loop header (a pre-header)")
    }
}
