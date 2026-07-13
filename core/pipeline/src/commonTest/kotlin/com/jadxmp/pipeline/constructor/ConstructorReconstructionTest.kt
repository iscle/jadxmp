package com.jadxmp.pipeline.constructor

import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.attr.AttrFlag
import com.jadxmp.ir.insn.InvokeInstruction
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.PhiInstruction
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.structure.ExpressionShaping
import com.jadxmp.pipeline.structure.OutOfSsa
import com.jadxmp.pipeline.structure.RegionMaker
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConstructorReconstructionTest {

    private val FOO = "Lcom/example/Foo;"
    private fun ctorRef(vararg params: String) = FakeMethodRef(FOO, "<init>", "V", params.toList())

    private fun allInsns(method: IrMethod) = method.blocks.flatMap { it.instructions }
    private fun run(method: IrMethod) {
        TestPipeline.full(method)
        ConstructorReconstruction(method).run()
    }

    @Test
    fun mergesNewInstanceAndInitIntoConstructor() {
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.NEW_INSTANCE, 0, intArrayOf(0), indexType = IndexType.TYPE_REF, typeValue = FOO),
                Insn(Opcode.CONST_STRING, 1, intArrayOf(1), indexType = IndexType.STRING_REF, stringValue = "x"),
                Insn(Opcode.INVOKE_DIRECT, 2, intArrayOf(0, 1), indexType = IndexType.METHOD_REF, methodRef = ctorRef("Ljava/lang/String;")),
                Insn(Opcode.RETURN, 3, intArrayOf(0)), // return the constructed object
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.objectType("com.example.Foo"))
        run(method)

        // The orphan new-instance is gone; one CONSTRUCTOR remains.
        assertTrue(allInsns(method).none { it.opcode == IrOpcode.NEW_INSTANCE }, "new-instance must be fused away")
        val ctor = allInsns(method).single { it.opcode == IrOpcode.CONSTRUCTOR } as InvokeInstruction
        assertTrue(ctor.methodRef.isConstructor)
        // Result is the constructed register; args are the actual ctor args (receiver dropped).
        assertEquals(0, ctor.result!!.regNum)
        assertEquals(1, ctor.argCount)
    }

    @Test
    fun constructAndDiscardDropsResult() {
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.NEW_INSTANCE, 0, intArrayOf(0), indexType = IndexType.TYPE_REF, typeValue = FOO),
                Insn(Opcode.INVOKE_DIRECT, 1, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = ctorRef()),
                Insn(Opcode.RETURN_VOID, 2), // object never used
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        run(method)
        val ctor = allInsns(method).single { it.opcode == IrOpcode.CONSTRUCTOR } as InvokeInstruction
        assertNull(ctor.result, "discarded construction should have no result (renders `new T();`)")
        assertEquals(0, ctor.argCount)
    }

    @Test
    fun tracesUninitializedRefThroughMoves() {
        // new-instance v0; move v1<-v0; invoke-direct {v1} <init>; return v1
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.NEW_INSTANCE, 0, intArrayOf(0), indexType = IndexType.TYPE_REF, typeValue = FOO),
                Insn(Opcode.MOVE_OBJECT, 1, intArrayOf(1, 0)),
                Insn(Opcode.INVOKE_DIRECT, 2, intArrayOf(1), indexType = IndexType.METHOD_REF, methodRef = ctorRef()),
                Insn(Opcode.RETURN, 3, intArrayOf(1)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.objectType("com.example.Foo"))
        run(method)
        assertTrue(allInsns(method).none { it.opcode == IrOpcode.NEW_INSTANCE })
        val ctor = allInsns(method).single { it.opcode == IrOpcode.CONSTRUCTOR } as InvokeInstruction
        // The constructor's result is the new-instance register (v0), which the move then copies.
        assertEquals(0, ctor.result!!.regNum)
    }

    @Test
    fun sharedNewInstanceCoveringIfElseMaterializesPerArm() {
        // new-instance v0; if (v1==0) { v0.<init>(); return v0 } else { v0.<init>(); return v0 }.
        // A single allocation shared by two constructors, one per arm. Even though the constructions are
        // identical, we must NOT hoist ONE constructor to the (dominating) allocation site — that would run
        // it on every path. Each arm gets its own `new Foo()`, and its return reads that fresh object.
        val reader = FakeCodeReader(
            2, // v0 = obj, v1 = param
            listOf(
                Insn(Opcode.NEW_INSTANCE, 0, intArrayOf(0), indexType = IndexType.TYPE_REF, typeValue = FOO),
                Insn(Opcode.IF_EQZ, 1, intArrayOf(1), target = 4), // if v1==0 -> else (off4)
                Insn(Opcode.INVOKE_DIRECT, 2, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = ctorRef()),
                Insn(Opcode.RETURN, 3, intArrayOf(0)),
                Insn(Opcode.INVOKE_DIRECT, 4, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = ctorRef()),
                Insn(Opcode.RETURN, 5, intArrayOf(0)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.objectType("com.example.Foo"), argTypes = listOf(IrType.INT))
        run(method)
        assertTrue(allInsns(method).none { it.opcode == IrOpcode.NEW_INSTANCE }, "shared new-instance must be fused away")
        val ctors = allInsns(method).filter { it.opcode == IrOpcode.CONSTRUCTOR } as List<InvokeInstruction>
        assertEquals(2, ctors.size, "each arm materializes its own constructor")
        assertEquals(2, ctors.map { it.result!!.ssaValue }.toSet().size, "each arm's construction is a distinct object")
    }

    @Test
    fun sharedNewInstanceNonCoveringArmsNeverConstructsOnTheSkipPath() {
        // The reviewer's repro: an OUTER guard skips BOTH constructing arms.
        //   v0 = new Foo
        //   if (v1 == 0) return;         // neither <init> runs on this path
        //   if (v2 == 0) goto ELSE
        //         v0.<init>()  (arm A); goto RET
        //   ELSE: v0.<init>()  (arm B)
        //   RET:  return void            (v0 never read)
        // A constructor MUST NOT be hoisted to the allocation site — it would run on the v1==0 path, which
        // originally constructed nothing (a silent miscompile if the ctor throws/has side effects). Each arm
        // gets its own bare `new Foo();`; the skip path constructs nothing.
        val reader = FakeCodeReader(
            3, // v0 = obj, v1 = outer guard, v2 = inner cond
            listOf(
                Insn(Opcode.NEW_INSTANCE, 0, intArrayOf(0), indexType = IndexType.TYPE_REF, typeValue = FOO),
                Insn(Opcode.IF_EQZ, 1, intArrayOf(1), target = 6), // if v1==0 -> RET (off6) — skip both arms
                Insn(Opcode.IF_EQZ, 2, intArrayOf(2), target = 5), // if v2==0 -> ELSE/armB (off5)
                Insn(Opcode.INVOKE_DIRECT, 3, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = ctorRef()), // arm A
                Insn(Opcode.GOTO, 4, target = 6), // arm A -> RET
                Insn(Opcode.INVOKE_DIRECT, 5, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = ctorRef()), // arm B
                Insn(Opcode.RETURN_VOID, 6), // RET (v0 never read)
            ),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.INT, IrType.INT))
        run(method)

        assertTrue(allInsns(method).none { it.opcode == IrOpcode.NEW_INSTANCE }, "new-instance fused away")
        val ctorBlocks = method.blocks.filter { b -> b.instructions.any { it.opcode == IrOpcode.CONSTRUCTOR } }
        assertEquals(2, ctorBlocks.size, "one constructor per constructing arm (none hoisted)")
        // The regression guard: NO constructor may sit in a block that dominates the skip path's return —
        // that would execute the ctor on the v1==0 path that originally constructed nothing.
        val retBlock = method.blocks.single { b -> b.instructions.any { it.opcode == IrOpcode.RETURN } }
        for (cb in ctorBlocks) {
            assertTrue(cb.id !in retBlock.dominators, "a constructor must not run on the arm-skipping path")
        }
    }

    @Test
    fun sharedNewInstanceBranchedConstructionMaterializesPerArm() {
        // new-instance v0; if (v3==0) { v0.<init>("a"); return v0 } else { v0.<init>("b"); return v0 }.
        // The arms construct DIFFERENTLY (distinct args), so one hoisted construction would be wrong — each
        // arm must get its OWN `new Foo(arg)` with a fresh object, and its return must read that fresh object.
        val reader = FakeCodeReader(
            4, // v0 = obj, v1 = string arg, v3 = condition
            listOf(
                Insn(Opcode.NEW_INSTANCE, 0, intArrayOf(0), indexType = IndexType.TYPE_REF, typeValue = FOO),
                Insn(Opcode.CONST, 1, intArrayOf(3), literal = 0),
                Insn(Opcode.IF_EQZ, 2, intArrayOf(3), target = 6), // if v3==0 -> else (off6)
                Insn(Opcode.CONST_STRING, 3, intArrayOf(1), indexType = IndexType.STRING_REF, stringValue = "a"),
                Insn(Opcode.INVOKE_DIRECT, 4, intArrayOf(0, 1), indexType = IndexType.METHOD_REF, methodRef = ctorRef("Ljava/lang/String;")),
                Insn(Opcode.RETURN, 5, intArrayOf(0)),
                Insn(Opcode.CONST_STRING, 6, intArrayOf(1), indexType = IndexType.STRING_REF, stringValue = "b"),
                Insn(Opcode.INVOKE_DIRECT, 7, intArrayOf(0, 1), indexType = IndexType.METHOD_REF, methodRef = ctorRef("Ljava/lang/String;")),
                Insn(Opcode.RETURN, 8, intArrayOf(0)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.objectType("com.example.Foo"))
        run(method)
        assertTrue(allInsns(method).none { it.opcode == IrOpcode.NEW_INSTANCE }, "shared new-instance must be fused away")
        val ctors = allInsns(method).filter { it.opcode == IrOpcode.CONSTRUCTOR } as List<InvokeInstruction>
        assertEquals(2, ctors.size, "branched construction materializes one constructor per arm")
        // Each arm's constructor has its OWN fresh result value (not the shared one) so its return is in scope.
        val resultValues = ctors.map { it.result!!.ssaValue }.toSet()
        assertEquals(2, resultValues.size, "each arm's construction is a distinct fresh object")
    }

    @Test
    fun sharedNewInstancePostMergeUseMergedWithPhi() {
        // The TestConstructorBranched shape: a single allocation constructed per-arm, whose result is used
        // AFTER the arms rejoin.
        //   v0 = new Foo
        //   if (v1 == 0) goto ARM_B
        //   ARM_A: v0.<init>(); goto JOIN
        //   ARM_B: v0.<init>()
        //   JOIN:  return v0            // <-- post-merge use, dominated by neither arm
        // Each arm gets its OWN `new Foo()`; a fresh φ at JOIN merges the two per-arm results and the return
        // reads the φ. Neither arm dominates JOIN, so without the φ the post-merge read is out of scope.
        val reader = FakeCodeReader(
            2, // v0 = obj, v1 = condition
            listOf(
                Insn(Opcode.NEW_INSTANCE, 0, intArrayOf(0), indexType = IndexType.TYPE_REF, typeValue = FOO),
                Insn(Opcode.IF_EQZ, 1, intArrayOf(1), target = 4), // v1==0 -> ARM_B (off4); else ARM_A (off2)
                Insn(Opcode.INVOKE_DIRECT, 2, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = ctorRef()), // ARM_A
                Insn(Opcode.GOTO, 3, target = 5), // ARM_A -> JOIN
                Insn(Opcode.INVOKE_DIRECT, 4, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = ctorRef()), // ARM_B, falls to JOIN
                Insn(Opcode.RETURN, 5, intArrayOf(0)), // JOIN: post-merge use of v0
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            returnType = IrType.objectType("com.example.Foo"),
            argTypes = listOf(IrType.INT),
        )
        run(method)

        // The shared new-instance is gone; each arm has its own constructor with a distinct fresh object.
        assertTrue(allInsns(method).none { it.opcode == IrOpcode.NEW_INSTANCE }, "shared new-instance fused away")
        val ctors = allInsns(method).filter { it.opcode == IrOpcode.CONSTRUCTOR } as List<InvokeInstruction>
        assertEquals(2, ctors.size, "one constructor per arm")
        val freshResults = ctors.map { it.result!!.ssaValue!! }.toSet()
        assertEquals(2, freshResults.size, "each arm's construction is a distinct fresh object")

        // A φ at JOIN merges exactly the two per-arm fresh results, one operand per predecessor edge.
        val phi = allInsns(method).filterIsInstance<PhiInstruction>().single()
        assertEquals(2, phi.incoming.size, "one φ operand per predecessor edge of the join")
        assertEquals(freshResults, phi.incoming.map { it.value.ssaValue }.toSet(), "φ merges the per-arm results")
        // The post-merge use (the return) reads the φ result, typed to the constructed object.
        val phiValue = phi.result!!.ssaValue!!
        assertTrue(phiValue.type.isTypeKnown, "φ result carries the object's inferred type")
        val ret = allInsns(method).single { it.opcode == IrOpcode.RETURN }
        assertEquals(phiValue, (ret.getArg(0) as RegisterOperand).ssaValue, "post-merge use reads the φ")

        // Downstream: out-of-SSA coalesces/removes the φ and structuring succeeds — no bail, no error marker.
        OutOfSsa(method).run()
        ExpressionShaping(method).run()
        RegionMaker(method).run()
        assertTrue(allInsns(method).none { it is PhiInstruction }, "out-of-SSA removes the inserted φ")
        assertNotNull(method.region, "clean diamond structures without bailing")
        assertFalse(method.contains(AttrFlag.HAS_ERROR), "no honest-error marker on a correctly-merged diamond")
    }

    @Test
    fun sharedNewInstancePostMergeUseNonDiamondBailsHonestly() {
        // A non-diamond post-merge use: an OUTER guard reaches the join having constructed NOTHING.
        //   v0 = new Foo
        //   if (v1 == 0) goto RET        // skip both arms — v0 unconstructed on this edge
        //   if (v2 == 0) goto ARM_B
        //   ARM_A: v0.<init>(); goto RET
        //   ARM_B: v0.<init>()
        //   RET:   return v0             // post-merge use reached by an arm-less edge
        // A φ here would read an undefined value on the skip edge. We MUST bail: leave the new-instance
        // unfused so codegen emits its honest `// JADXMP ERROR` marker, never a malformed φ (rule 4).
        val reader = FakeCodeReader(
            3, // v0 = obj, v1 = outer guard, v2 = inner cond
            listOf(
                Insn(Opcode.NEW_INSTANCE, 0, intArrayOf(0), indexType = IndexType.TYPE_REF, typeValue = FOO),
                Insn(Opcode.IF_EQZ, 1, intArrayOf(1), target = 6), // v1==0 -> RET (off6), skipping both arms
                Insn(Opcode.IF_EQZ, 2, intArrayOf(2), target = 5), // v2==0 -> ARM_B (off5)
                Insn(Opcode.INVOKE_DIRECT, 3, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = ctorRef()), // ARM_A
                Insn(Opcode.GOTO, 4, target = 6), // ARM_A -> RET
                Insn(Opcode.INVOKE_DIRECT, 5, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = ctorRef()), // ARM_B, falls to RET
                Insn(Opcode.RETURN, 6, intArrayOf(0)), // RET: post-merge use of a possibly-unconstructed v0
            ),
        )
        val method = TestPipeline.buildMethod(
            reader,
            returnType = IrType.objectType("com.example.Foo"),
            argTypes = listOf(IrType.INT, IrType.INT),
        )
        run(method)

        // Honest bail: nothing was rewritten — no φ, no partial fusion, the new-instance stays for the marker.
        assertTrue(allInsns(method).any { it.opcode == IrOpcode.NEW_INSTANCE }, "bail leaves the new-instance unfused")
        assertTrue(allInsns(method).none { it is PhiInstruction }, "no φ inserted on a non-diamond merge")
        assertTrue(allInsns(method).none { it.opcode == IrOpcode.CONSTRUCTOR }, "no arm was partially materialized")
    }

    @Test
    fun superDelegationIsNotTouched() {
        // invoke-direct {this}, Object.<init>()  — a super() call, no new-instance behind `this`.
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(
                    Opcode.INVOKE_DIRECT, 0, intArrayOf(0), indexType = IndexType.METHOD_REF,
                    methodRef = FakeMethodRef("Ljava/lang/Object;", "<init>", "V", emptyList()),
                ),
                Insn(Opcode.RETURN_VOID, 1),
            ),
        )
        // instance method: register 0 is `this`.
        val method = TestPipeline.buildMethod(reader, isStatic = false)
        run(method)
        // Left as a plain INVOKE (codegen renders `super()`), never turned into a CONSTRUCTOR.
        assertTrue(allInsns(method).none { it.opcode == IrOpcode.CONSTRUCTOR }, "super()/this() delegation must not be fused")
        val invoke = allInsns(method).single { it is InvokeInstruction } as InvokeInstruction
        assertEquals(IrOpcode.INVOKE, invoke.opcode)
    }
}
