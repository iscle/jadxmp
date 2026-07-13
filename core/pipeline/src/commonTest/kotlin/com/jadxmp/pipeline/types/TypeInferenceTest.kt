package com.jadxmp.pipeline.types

import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode
import com.jadxmp.ir.insn.IrOpcode
import com.jadxmp.ir.insn.LiteralOperand
import com.jadxmp.ir.insn.RegisterOperand
import com.jadxmp.ir.node.IrClass
import com.jadxmp.ir.node.IrMethod
import com.jadxmp.ir.node.IrRoot
import com.jadxmp.ir.type.IrType
import com.jadxmp.pipeline.support.FakeCodeReader
import com.jadxmp.pipeline.support.FakeFieldRef
import com.jadxmp.pipeline.support.FakeMethodRef
import com.jadxmp.pipeline.support.Insn
import com.jadxmp.pipeline.support.TestPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypeInferenceTest {

    private fun defValue(method: com.jadxmp.ir.node.IrMethod, opcode: IrOpcode) =
        method.ssaValues.first { it.assign.parent?.opcode == opcode }

    private fun firstInsn(method: IrMethod, opcode: IrOpcode) =
        method.blocks.flatMap { it.instructions }.first { it.opcode == opcode }

    private val LIST = IrType.objectType("java.util.List")

    @Test
    fun constUsedInIntArithmeticBecomesInt() {
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 1),
                Insn(Opcode.ADD_INT, 1, intArrayOf(1, 0, 0)), // v1 = v0 + v0
                Insn(Opcode.RETURN, 2, intArrayOf(1)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.full(method)
        assertEquals(IrType.INT, defValue(method, IrOpcode.CONST).type)
    }

    @Test
    fun invokeResultTakesReturnType() {
        val ref = FakeMethodRef("Lcom/example/Foo;", "name", "Ljava/lang/String;", emptyList())
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = ref),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)),
                Insn(Opcode.RETURN, 2, intArrayOf(0)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.STRING)
        TestPipeline.full(method)
        assertEquals(IrType.STRING, defValue(method, IrOpcode.INVOKE).type)
    }

    @Test
    fun fieldTypeFlowsThroughMove() {
        val field = FakeFieldRef("Lcom/example/Foo;", "text", "Ljava/lang/String;")
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.SGET, 0, intArrayOf(0), indexType = IndexType.FIELD_REF, fieldRef = field),
                Insn(Opcode.MOVE_OBJECT, 1, intArrayOf(1, 0)),
                Insn(Opcode.RETURN, 2, intArrayOf(1)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.STRING)
        TestPipeline.full(method)
        assertEquals(IrType.STRING, defValue(method, IrOpcode.STATIC_GET).type)
        assertEquals(IrType.STRING, defValue(method, IrOpcode.MOVE).type)
    }

    @Test
    fun arrayGetResolvesElementType() {
        val reader = FakeCodeReader(
            4,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(1), literal = 1),
                Insn(Opcode.NEW_ARRAY, 1, intArrayOf(0, 1), indexType = IndexType.TYPE_REF, typeValue = "[Ljava/lang/String;"),
                Insn(Opcode.CONST, 2, intArrayOf(2), literal = 0),
                Insn(Opcode.AGET_OBJECT, 3, intArrayOf(3, 0, 2)),
                Insn(Opcode.RETURN, 4, intArrayOf(3)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.STRING)
        TestPipeline.full(method)
        assertEquals(IrType.STRING, defValue(method, IrOpcode.ARRAY_GET).type)
    }

    @Test
    fun xorResultRefinesToBooleanFromReturn() {
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 1),
                Insn(Opcode.CONST, 1, intArrayOf(1), literal = 0),
                Insn(Opcode.XOR_INT, 2, intArrayOf(2, 0, 1)),
                Insn(Opcode.RETURN, 3, intArrayOf(2)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.BOOLEAN)
        TestPipeline.full(method)
        assertEquals(IrType.BOOLEAN, defValue(method, IrOpcode.ARITH).type)
    }

    @Test
    fun phiOfTwoSubclassesJoinsToCommonSuperType() {
        val root = IrRoot()
        val animal = IrClass(root, "com.example.Animal", 0, superType = IrType.OBJECT)
        val dog = IrClass(root, "com.example.Dog", 0, superType = IrType.objectType("com.example.Animal"))
        val cat = IrClass(root, "com.example.Cat", 0, superType = IrType.objectType("com.example.Animal"))
        root.addClass(animal); root.addClass(dog); root.addClass(cat)

        val makeDog = FakeMethodRef("Lcom/example/Foo;", "dog", "Lcom/example/Dog;", emptyList())
        val makeCat = FakeMethodRef("Lcom/example/Foo;", "cat", "Lcom/example/Cat;", emptyList())
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(1), target = 4),
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = makeDog),
                Insn(Opcode.MOVE_RESULT, 2, intArrayOf(0)),
                Insn(Opcode.GOTO, 3, target = 6),
                Insn(Opcode.INVOKE_STATIC, 4, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = makeCat),
                Insn(Opcode.MOVE_RESULT, 5, intArrayOf(0)),
                Insn(Opcode.RETURN, 6, intArrayOf(0)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.objectType("com.example.Animal"), root = root)
        // register 1 is read but never defined; it is a dummy selector for the branch.
        TestPipeline.full(method)
        val phiVal = method.ssaValues.first { it.assign.parent is com.jadxmp.ir.insn.PhiInstruction }
        assertEquals(IrType.objectType("com.example.Animal"), phiVal.type)
    }

    @Test
    fun phiOverUnrelatedLibraryTypesJoinsToObject() {
        val str = FakeMethodRef("Lcom/example/Foo;", "s", "Ljava/lang/String;", emptyList())
        val integer = FakeMethodRef("Lcom/example/Foo;", "i", "Ljava/lang/Integer;", emptyList())
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(1), target = 4),
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = str),
                Insn(Opcode.MOVE_RESULT, 2, intArrayOf(0)),
                Insn(Opcode.GOTO, 3, target = 6),
                Insn(Opcode.INVOKE_STATIC, 4, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = integer),
                Insn(Opcode.MOVE_RESULT, 5, intArrayOf(0)),
                Insn(Opcode.RETURN, 6, intArrayOf(0)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.OBJECT)
        TestPipeline.full(method)
        val phiVal = method.ssaValues.first { it.assign.parent is com.jadxmp.ir.insn.PhiInstruction }
        // String and Integer are unrelated and unloaded => their only known common supertype is Object.
        assertEquals(IrType.OBJECT, phiVal.type)
    }

    @Test
    fun conflictingUseBoundsKeepFirstConsistentTypeNoBogusWidening() {
        val useInt = FakeMethodRef("Lcom/example/Foo;", "i", "V", listOf("I"))
        val useObj = FakeMethodRef("Lcom/example/Foo;", "o", "V", listOf("Ljava/lang/Object;"))
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 5),
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = useInt),
                Insn(Opcode.INVOKE_STATIC, 2, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = useObj),
                Insn(Opcode.RETURN_VOID, 3),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.full(method)
        // int and Object cannot both hold the value; inference keeps the first consistent bound (int)
        // and does NOT silently widen across the conflict.
        assertEquals(IrType.INT, defValue(method, IrOpcode.CONST).type)
    }

    // ---- const-0 / literal-operand typing (oracle types/* regression cluster) ----

    @Test
    fun constZeroMergedIntoObjectPhiRendersAsNull() {
        // v = (branch) ? 0 : list() ; return v  (List). The `const 0` must become a reference so codegen
        // emits `null`, not `0` (TestTypeResolver16 / TestConstInline `list = null`).
        val list = FakeMethodRef("Lcom/example/Foo;", "list", "Ljava/util/List;", emptyList())
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(1), target = 3),
                Insn(Opcode.CONST, 1, intArrayOf(0), literal = 0),
                Insn(Opcode.GOTO, 2, target = 5),
                Insn(Opcode.INVOKE_STATIC, 3, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = list),
                Insn(Opcode.MOVE_RESULT, 4, intArrayOf(0)),
                Insn(Opcode.RETURN, 5, intArrayOf(0)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = LIST)
        TestPipeline.full(method)
        val constValue = defValue(method, IrOpcode.CONST)
        assertEquals(LIST, constValue.type, "const-0 flowing into a List φ must be typed List")
        // The literal operand is retyped too, so codegen renders `null` (reference-like zero).
        val lit = firstInsn(method, IrOpcode.CONST).getArg(0) as LiteralOperand
        assertTrue(isReferenceLike(lit.type), "const-0 literal must carry a reference type to render as null, was ${lit.type}")
    }

    @Test
    fun ifZeroCompareAgainstObjectTypesLiteralAsReference() {
        // `if (list == null)` — the zero literal of an if-eqz on an object must be typed as that object,
        // so codegen emits `== null`, not the un-compilable `== 0` (TestTypeResolver16 `set != null`).
        val field = FakeFieldRef("Lcom/example/Foo;", "f", "Ljava/util/List;")
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.SGET, 0, intArrayOf(0), indexType = IndexType.FIELD_REF, fieldRef = field),
                Insn(Opcode.IF_EQZ, 1, intArrayOf(0), target = 3),
                Insn(Opcode.RETURN_VOID, 2),
                Insn(Opcode.RETURN_VOID, 3),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.full(method)
        val lit = firstInsn(method, IrOpcode.IF).getArg(1) as LiteralOperand
        assertEquals(LIST, lit.type)
    }

    @Test
    fun ifZeroCompareAgainstBooleanTypesLiteralAsBoolean() {
        // `if (z == false)` — an if-eqz on a boolean param types the zero literal boolean (compilable),
        // never `z == 0` (TestTypeResolver15 `z == 0`).
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.IF_EQZ, 0, intArrayOf(0), target = 2),
                Insn(Opcode.RETURN_VOID, 1),
                Insn(Opcode.RETURN_VOID, 2),
            ),
        )
        val method = TestPipeline.buildMethod(reader, argTypes = listOf(IrType.BOOLEAN))
        TestPipeline.full(method)
        val lit = firstInsn(method, IrOpcode.IF).getArg(1) as LiteralOperand
        assertEquals(IrType.BOOLEAN, lit.type)
    }

    @Test
    fun constZeroUsedOnlyAsIntStaysIntLiteral() {
        // Soundness guard: a genuinely-int const must NOT be turned into a reference/null.
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
                Insn(Opcode.ADD_INT, 1, intArrayOf(1, 0, 0)),
                Insn(Opcode.RETURN, 2, intArrayOf(1)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.full(method)
        val lit = firstInsn(method, IrOpcode.CONST).getArg(0) as LiteralOperand
        assertEquals(IrType.INT, lit.type)
    }

    @Test
    fun constZeroMovedIntoObjectBecomesReference() {
        // v1 = move v0(const 0); v1 used as String arg. The move back-constraint pulls the const to a
        // reference (so both render `null`) — mirrors the null-const-through-move case.
        val useStr = FakeMethodRef("Lcom/example/Foo;", "s", "V", listOf("Ljava/lang/String;"))
        val reader = FakeCodeReader(
            2,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 0),
                Insn(Opcode.MOVE_OBJECT, 1, intArrayOf(1, 0)),
                Insn(Opcode.INVOKE_STATIC, 2, intArrayOf(1), indexType = IndexType.METHOD_REF, methodRef = useStr),
                Insn(Opcode.RETURN_VOID, 3),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.full(method)
        assertEquals(IrType.STRING, defValue(method, IrOpcode.MOVE).type)
        assertTrue(isReferenceLike(defValue(method, IrOpcode.CONST).type))
    }

    private fun isReferenceLike(t: IrType): Boolean =
        t is IrType.Object || t is IrType.ArrayType || t is IrType.TypeVariable || t is IrType.Wildcard

    @Test
    fun integralCharVsByteConflictKeepsAnIntegralNotIntWidening() {
        val useChar = FakeMethodRef("Lcom/example/Foo;", "c", "V", listOf("C"))
        val useByte = FakeMethodRef("Lcom/example/Foo;", "b", "V", listOf("B"))
        val reader = FakeCodeReader(
            1,
            listOf(
                Insn(Opcode.CONST, 0, intArrayOf(0), literal = 65),
                Insn(Opcode.INVOKE_STATIC, 1, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = useChar),
                Insn(Opcode.INVOKE_STATIC, 2, intArrayOf(0), indexType = IndexType.METHOD_REF, methodRef = useByte),
                Insn(Opcode.RETURN_VOID, 3),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.full(method)
        // char ∩ byte is empty (disjoint primitives): inference keeps the first bound (char), never
        // fabricating int by widening past the conflict.
        val t = defValue(method, IrOpcode.CONST).type
        assertEquals(IrType.CHAR, t)
    }

    // ---- bitwise int/boolean disambiguation (jadx: FixTypesVisitor boolean narrowing) --------------

    private val makeBool = FakeMethodRef("Lcom/example/Foo;", "b", "Z", emptyList())
    private val makeInt = FakeMethodRef("Lcom/example/Foo;", "i", "I", emptyList())

    private fun xorResult(method: IrMethod) =
        method.ssaValues.first { v ->
            val p = v.assign.parent
            p is com.jadxmp.ir.insn.ArithInstruction && p.op == com.jadxmp.ir.insn.ArithOp.XOR
        }

    @Test
    fun bitwiseWithBooleanOperandInfersBooleanUnderBooleanUse() {
        // z = makeBool(); v2 = z ^ 1; if (v2 == 0) ...   → `xor bool, 1` is `!bool`, and its only use is a
        // boolean test, so the result must infer BOOLEAN (arms would render false/true), not int.
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = makeBool),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)), // v0 = makeBool() : boolean
                Insn(Opcode.CONST, 2, intArrayOf(1), literal = 1), // v1 = 1
                Insn(Opcode.XOR_INT, 3, intArrayOf(2, 0, 1)), // v2 = v0 ^ v1
                Insn(Opcode.IF_EQZ, 4, intArrayOf(2), target = 6), // boolean test — no int use
                Insn(Opcode.RETURN_VOID, 5),
                Insn(Opcode.RETURN_VOID, 6),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.full(method)
        assertEquals(IrType.BOOLEAN, xorResult(method).type, "xor of a boolean under a boolean-only use is boolean")
    }

    @Test
    fun bitwiseWithoutBooleanOperandStaysInt() {
        // v2 = i0 ^ i1 where both operands are genuine ints → no boolean evidence, must stay int.
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = makeInt),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)), // v0 : int
                Insn(Opcode.INVOKE_STATIC, 2, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = makeInt),
                Insn(Opcode.MOVE_RESULT, 3, intArrayOf(1)), // v1 : int
                Insn(Opcode.XOR_INT, 4, intArrayOf(2, 0, 1)), // v2 = v0 ^ v1
                Insn(Opcode.IF_EQZ, 5, intArrayOf(2), target = 7),
                Insn(Opcode.RETURN_VOID, 6),
                Insn(Opcode.RETURN_VOID, 7),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.full(method)
        assertEquals(IrType.INT, xorResult(method).type, "no boolean operand ⇒ no boolean narrowing")
    }

    @Test
    fun booleanBitwiseUsedInArithmeticStaysInt() {
        // v2 = z ^ 1 (boolean evidence) BUT v4 = v2 + 5 uses it arithmetically. A real int use wins — the
        // value is genuinely int; narrowing to boolean would emit `false + 5` (rule 4: keep int).
        val reader = FakeCodeReader(
            5,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = makeBool),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)), // v0 : boolean
                Insn(Opcode.CONST, 2, intArrayOf(1), literal = 1), // v1 = 1
                Insn(Opcode.XOR_INT, 3, intArrayOf(2, 0, 1)), // v2 = v0 ^ v1  (boolean operand)
                Insn(Opcode.CONST, 4, intArrayOf(3), literal = 5), // v3 = 5
                Insn(Opcode.ADD_INT, 5, intArrayOf(4, 2, 3)), // v4 = v2 + v3  (int arithmetic use of v2)
                Insn(Opcode.RETURN, 6, intArrayOf(4)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.full(method)
        assertEquals(IrType.INT, xorResult(method).type, "an int arithmetic use forbids the boolean narrowing")
    }

    @Test
    fun booleanBitwiseUnderOrderingCompareStaysInt() {
        // v2 = z ^ 1 (boolean operand) but the use is `if (v2 < 0)` — an ORDERING compare needs int.
        // `z < 0` / `z < false` does not compile, so v2 must stay int (must-fix 1).
        val reader = FakeCodeReader(
            3,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = makeBool),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)), // v0 : boolean
                Insn(Opcode.CONST, 2, intArrayOf(1), literal = 1), // v1 = 1
                Insn(Opcode.XOR_INT, 3, intArrayOf(2, 0, 1)), // v2 = v0 ^ v1
                Insn(Opcode.IF_LTZ, 4, intArrayOf(2), target = 6), // ordering compare `v2 < 0`
                Insn(Opcode.RETURN_VOID, 5),
                Insn(Opcode.RETURN_VOID, 6),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.full(method)
        assertEquals(IrType.INT, xorResult(method).type, "an ordering compare forbids the boolean narrowing")
    }

    @Test
    fun booleanBitwiseEqualityAgainstIntStaysInt() {
        // v2 = z ^ 1 (boolean operand) but the use is `if (v2 == makeInt())` — the sibling is a genuine
        // int, so `if (z == i)` would not compile; v2 must stay int (must-fix 2).
        val reader = FakeCodeReader(
            4,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = makeBool),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)), // v0 : boolean
                Insn(Opcode.CONST, 2, intArrayOf(1), literal = 1), // v1 = 1
                Insn(Opcode.XOR_INT, 3, intArrayOf(2, 0, 1)), // v2 = v0 ^ v1
                Insn(Opcode.INVOKE_STATIC, 4, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = makeInt),
                Insn(Opcode.MOVE_RESULT, 5, intArrayOf(3)), // v3 = makeInt() : int
                Insn(Opcode.IF_EQ, 6, intArrayOf(2, 3), target = 8), // two-register equality `v2 == v3`
                Insn(Opcode.RETURN_VOID, 7),
                Insn(Opcode.RETURN_VOID, 8),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.full(method)
        assertEquals(IrType.INT, xorResult(method).type, "equality against a genuine int forbids narrowing")
    }

    @Test
    fun phiMergingBooleanBitwiseWithGenuineIntConstStaysInt() {
        // if (p0) v2 = z ^ 1;  else v2 = 2;   if (v2 == 0) ...
        // The φ merges a boolean-bitwise arm with a genuine `const 2`. Narrowing would render `2` as
        // `true` (JavaLiterals: non-zero → "true"), so BOTH the xor and the const 2 must stay int
        // (must-fix 3). Equality-to-zero on the φ result alone is not enough — the int sibling forbids it.
        val reader = FakeCodeReader(
            3, // v0, v1, v2 — no params; branch on a fresh boolean
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = makeBool),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)), // v0 = makeBool()
                Insn(Opcode.IF_EQZ, 2, intArrayOf(0), target = 7), // if (!v0) goto else
                // then: v2 = z ^ 1  (reuse v0 as the boolean operand)
                Insn(Opcode.CONST, 3, intArrayOf(1), literal = 1), // v1 = 1
                Insn(Opcode.XOR_INT, 4, intArrayOf(2, 0, 1)), // v2 = v0 ^ v1
                Insn(Opcode.GOTO, 5, target = 8),
                Insn(Opcode.NOP, 6),
                // else (offset 7): v2 = 2  (a GENUINE int const)
                Insn(Opcode.CONST, 7, intArrayOf(2), literal = 2), // v2 = 2
                // merge (offset 8): if (v2 == 0) ...
                Insn(Opcode.IF_EQZ, 8, intArrayOf(2), target = 10),
                Insn(Opcode.RETURN_VOID, 9),
                Insn(Opcode.RETURN_VOID, 10),
            ),
        )
        val method = TestPipeline.buildMethod(reader)
        TestPipeline.full(method)
        assertEquals(IrType.INT, xorResult(method).type, "a genuine-int φ sibling forbids the boolean narrowing")
        // And the genuine int const 2 is not dragged to boolean either.
        val const2 = method.ssaValues.first { v ->
            val p = v.assign.parent
            p?.opcode == IrOpcode.CONST && (p.args.firstOrNull() as? LiteralOperand)?.value == 2L
        }
        assertEquals(IrType.INT, const2.type, "const 2 stays int (must render as 2, not true)")
    }

    @Test
    fun phiSiblingZeroConstPinnedIntByArithmeticStaysInt() {
        // v1 = 0 is used in `v2 = v1 + v1` (and v2 is RETURNED, so the add survives DCE and pins v1 to
        // int) AND, via a move, as a φ arm merging with `v0 ^ 1`; the φ result is tested `== 0`. Even
        // though v1 is a `0` const, its arithmetic use has resolved it to a concrete int, so narrowing the
        // xor would drag v1 to boolean → `false + false`. The xor must stay int (rule 4 for a shared,
        // int-pinned polymorphic zero).
        val reader = FakeCodeReader(
            5,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = makeBool),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)), // v0 : boolean
                Insn(Opcode.CONST, 2, intArrayOf(1), literal = 0), // v1 = 0 (shared)
                Insn(Opcode.ADD_INT, 3, intArrayOf(2, 1, 1)), // v2 = v1 + v1  → pins v1 to int
                Insn(Opcode.IF_EQZ, 4, intArrayOf(0), target = 8), // if (!v0) goto else
                // then: v3 = v0 ^ 1
                Insn(Opcode.CONST, 5, intArrayOf(4), literal = 1), // v4 = 1
                Insn(Opcode.XOR_INT, 6, intArrayOf(3, 0, 4)), // v3 = v0 ^ v4
                Insn(Opcode.GOTO, 7, target = 9),
                // else (offset 8): v3 = v1  (the int-pinned zero)
                Insn(Opcode.MOVE, 8, intArrayOf(3, 1)),
                // merge (offset 9): if (v3 == 0) then return v2 else return v2  (v2 keeps the add live)
                Insn(Opcode.IF_EQZ, 9, intArrayOf(3), target = 11),
                Insn(Opcode.RETURN, 10, intArrayOf(2)),
                Insn(Opcode.RETURN, 11, intArrayOf(2)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.INT)
        TestPipeline.full(method)
        assertEquals(IrType.INT, xorResult(method).type, "a φ sibling pinned int by arithmetic forbids narrowing")
    }

    @Test
    fun booleanBitwiseStoredIntoIntArrayStaysInt() {
        // v2 = makeBool() ^ 1; arr = new int[1]; arr[0] = v2; return arr
        // A generic `aput` value decodes as NARROW_NUMBERS (which CONTAINS boolean), but generic aput
        // serves int[]/float[] only — booleans use `aput-boolean`. So the store requires int; narrowing v2
        // to boolean would emit `arr[0] = !z` into an int[] (won't compile). The fallback must block on a
        // merely-boolean-admitting permissive bound, so v2 stays int.
        val reader = FakeCodeReader(
            6,
            listOf(
                Insn(Opcode.INVOKE_STATIC, 0, intArrayOf(), indexType = IndexType.METHOD_REF, methodRef = makeBool),
                Insn(Opcode.MOVE_RESULT, 1, intArrayOf(0)), // v0 : boolean
                Insn(Opcode.CONST, 2, intArrayOf(1), literal = 1), // v1 = 1
                Insn(Opcode.XOR_INT, 3, intArrayOf(2, 0, 1)), // v2 = v0 ^ v1
                Insn(Opcode.CONST, 4, intArrayOf(3), literal = 1), // v3 = 1 (length)
                // v4 = new int[v3]
                Insn(Opcode.NEW_ARRAY, 5, intArrayOf(4, 3), indexType = IndexType.TYPE_REF, typeValue = "[I"),
                Insn(Opcode.CONST, 6, intArrayOf(5), literal = 0), // v5 = 0 (index)
                Insn(Opcode.APUT, 7, intArrayOf(2, 4, 5)), // v4[v5] = v2  (generic aput into int[])
                Insn(Opcode.RETURN, 8, intArrayOf(4)),
            ),
        )
        val method = TestPipeline.buildMethod(reader, returnType = IrType.array(IrType.INT))
        TestPipeline.full(method)
        assertEquals(IrType.INT, xorResult(method).type, "a generic aput into int[] forbids the boolean narrowing")
    }
}
