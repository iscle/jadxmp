package com.jadxmp.input.dex

import com.jadxmp.input.AccessFlags
import com.jadxmp.input.AnnotationData
import com.jadxmp.input.CatchHandler
import com.jadxmp.input.ClassData
import com.jadxmp.input.CodeReader
import com.jadxmp.input.DebugInfo
import com.jadxmp.input.EncodedValue
import com.jadxmp.input.FieldData
import com.jadxmp.input.FieldRef
import com.jadxmp.input.IndexType
import com.jadxmp.input.Instruction
import com.jadxmp.input.InstructionPayload
import com.jadxmp.input.MethodData
import com.jadxmp.input.MethodProto
import com.jadxmp.input.MethodRef
import com.jadxmp.input.Opcode
import com.jadxmp.input.TryBlock
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit-tests the smali serializer through the `core:input` SPI with hand-built fake instructions, so
 * label/operand formatting is exercised deterministically without crafting DEX bytes. This is the
 * testable seam the task requires: model → smali string, independent of Compose and the parser.
 */
class SmaliPrinterTest {

    @Test
    fun rendersClassHeaderFieldsAndMethodSignature() {
        val cls = FakeClass(
            type = "Lp/Demo;",
            accessFlags = AccessFlags.PUBLIC or AccessFlags.FINAL,
            superType = "Ljava/lang/Object;",
            interfaces = listOf("Lp/Iface;"),
            sourceFile = "Demo.java",
            fields = listOf(
                FakeField("Lp/Demo;", "COUNT", "I", AccessFlags.PUBLIC or AccessFlags.STATIC or AccessFlags.FINAL, null),
            ),
            methods = listOf(branchMethod()),
        )
        val smali = SmaliPrinter.render(cls)

        assertContains(smali, ".class public final Lp/Demo;")
        assertContains(smali, ".super Ljava/lang/Object;")
        assertContains(smali, ".implements Lp/Iface;")
        assertContains(smali, ".source \"Demo.java\"")
        assertContains(smali, ".field public static final COUNT:I")
        assertContains(smali, ".method public static test(I)V")
        assertContains(smali, ".end method")
    }

    @Test
    fun rendersRegistersInstructionsFieldRefBranchAndInvoke() {
        val cls = FakeClass(methods = listOf(branchMethod()))
        val smali = SmaliPrinter.render(cls)

        assertContains(smali, "    .registers 2")
        assertContains(smali, "    const/4 v0, 0x1")
        // Branch target renders as a label, and that label is defined before the target instruction.
        assertContains(smali, "    if-eqz v0, :cond_4")
        assertContains(smali, "    :cond_4")
        // Field reference in Lpkg/Class;->name:Type form.
        assertContains(smali, "    sget-object v1, Lp/C;->f:Ljava/lang/String;")
        // Invoke uses brace-wrapped registers and a Lpkg/Class;->m(args)ret method reference.
        assertContains(smali, "    invoke-static {v0}, Lp/C;->m(I)V")
        assertContains(smali, "    return-void")

        // The :cond_4 label must appear textually before the invoke it guards.
        assertTrue(smali.indexOf(":cond_4") < smali.indexOf("invoke-static"))
    }

    @Test
    fun negativeAndWideLiteralsUseSignedHex() {
        val m = FakeMethod(
            ref = FakeMethodRef("Lp/C;", "lits", "V", emptyList()),
            accessFlags = AccessFlags.STATIC,
            code = FakeCode(
                registerCount = 3,
                instructions = listOf(
                    FakeInsn(0, "const/4", Opcode.CONST, registers = intArrayOf(0), literal = -1),
                    FakeInsn(1, "const-wide", Opcode.CONST_WIDE, registers = intArrayOf(1), literal = 5),
                    FakeInsn(6, "return-void", Opcode.RETURN_VOID),
                ),
            ),
        )
        val smali = SmaliPrinter.render(FakeClass(methods = listOf(m)))
        assertContains(smali, "const/4 v0, -0x1")
        assertContains(smali, "const-wide v1, 0x5L")
    }

    @Test
    fun rendersTryCatchDirectives() {
        val handler = FakeCatch(
            types = listOf("Ljava/lang/Exception;"),
            handlers = listOf(6),
            catchAllHandler = -1,
        )
        val m = FakeMethod(
            ref = FakeMethodRef("Lp/C;", "guarded", "V", emptyList()),
            accessFlags = AccessFlags.STATIC,
            code = FakeCode(
                registerCount = 1,
                instructions = listOf(
                    FakeInsn(0, "invoke-static", Opcode.INVOKE_STATIC, registers = intArrayOf(0),
                        indexType = IndexType.METHOD_REF, methodRef = FakeMethodRef("Lp/C;", "m", "V", emptyList())),
                    FakeInsn(3, "return-void", Opcode.RETURN_VOID),
                    FakeInsn(6, "move-exception", Opcode.MOVE_EXCEPTION, registers = intArrayOf(0)),
                    FakeInsn(7, "return-void", Opcode.RETURN_VOID),
                ),
                tries = listOf(FakeTry(startOffset = 0, endOffset = 2, catchHandler = handler)),
            ),
        )
        val smali = SmaliPrinter.render(FakeClass(methods = listOf(m)))
        assertContains(smali, ":try_start_0")
        assertContains(smali, ":try_end_3")
        assertContains(smali, ".catch Ljava/lang/Exception; {:try_start_0 .. :try_end_3} :catch_6")
        assertContains(smali, ":catch_6")
    }

    @Test
    fun rendersPackedSwitchPayloadWithCaseLabels() {
        // packed-switch @0 -> payload @8; payload case targets are relative to the switch (0), so absolute
        // case offsets are 4 and 6, which must appear as :pswitch_4 / :pswitch_6 labels.
        val payload = com.jadxmp.input.SwitchPayload(keys = intArrayOf(0, 1), targets = intArrayOf(4, 6))
        val m = FakeMethod(
            ref = FakeMethodRef("Lp/C;", "sw", "V", listOf("I")),
            accessFlags = AccessFlags.STATIC,
            code = FakeCode(
                registerCount = 1,
                instructions = listOf(
                    FakeInsn(0, "packed-switch", Opcode.PACKED_SWITCH, registers = intArrayOf(0), target = 8),
                    FakeInsn(4, "const/4", Opcode.CONST, registers = intArrayOf(0), literal = 0),
                    FakeInsn(6, "return-void", Opcode.RETURN_VOID),
                    FakeInsn(8, "packed-switch-payload", Opcode.PACKED_SWITCH_PAYLOAD, payload = payload),
                ),
            ),
        )
        val smali = SmaliPrinter.render(FakeClass(methods = listOf(m)))
        assertContains(smali, "packed-switch v0, :pswitch_data_8")
        assertContains(smali, "    :pswitch_data_8")
        assertContains(smali, "    .packed-switch 0x0")
        assertContains(smali, "        :pswitch_4")
        assertContains(smali, "        :pswitch_6")
        assertContains(smali, "    .end packed-switch")
        // The case-target labels are defined at their instruction offsets.
        assertContains(smali, "    :pswitch_4")
        assertContains(smali, "    :pswitch_6")
    }

    @Test
    fun unmappedOpcodeIsMarkedPlaceholderNotWrongMnemonic() {
        val m = FakeMethod(
            ref = FakeMethodRef("Lp/C;", "weird", "V", emptyList()),
            accessFlags = AccessFlags.STATIC,
            code = FakeCode(
                registerCount = 1,
                instructions = listOf(
                    FakeInsn(0, "unknown-0x3e", Opcode.UNKNOWN, rawOpcodeUnit = 0x3e),
                    FakeInsn(1, "return-void", Opcode.RETURN_VOID),
                ),
            ),
        )
        val smali = SmaliPrinter.render(FakeClass(methods = listOf(m)))
        assertContains(smali, "unmapped opcode")
        // The placeholder must be a FULL comment line — no leading bare `unknown-…` token that would be an
        // invalid instruction. The whole trimmed line starts with '#'.
        val line = smali.lines().single { "unmapped opcode" in it }
        assertTrue(line.trim().startsWith("#"), "unmapped line must be a pure comment, was: '$line'")
        assertTrue(!line.trim().startsWith("unknown"), "no bare opcode token before the comment: '$line'")
    }

    @Test
    fun methodThatFailsToDecodeDegradesToOneValidErrorStubAndKeepsSiblings() {
        // Middle method throws mid-body (reachable on truncated/hostile dex). It must NOT leave a
        // half-written `.method` block: exactly one `.method`/`.end method` pair with an error comment,
        // and the sibling methods stay intact and well-formed.
        val boom = FakeMethod(
            ref = FakeMethodRef("Lp/C;", "boomy", "V", emptyList()),
            accessFlags = AccessFlags.STATIC,
            code = FakeCode(
                registerCount = 2,
                instructions = listOf(
                    FakeInsn(0, "const/4", Opcode.CONST, registers = intArrayOf(0), literal = 1, decodeThrows = true),
                    FakeInsn(1, "return-void", Opcode.RETURN_VOID),
                ),
            ),
        )
        val registersBoom = FakeMethod(
            ref = FakeMethodRef("Lp/C;", "regBoom", "V", emptyList()),
            accessFlags = AccessFlags.STATIC,
            code = FakeCode(registerCount = 1, instructions = emptyList(), registerCountThrows = true),
        )
        val ok1 = branchMethod()
        val ok2 = FakeMethod(
            ref = FakeMethodRef("Lp/C;", "fine", "V", emptyList()),
            accessFlags = AccessFlags.STATIC,
            code = FakeCode(registerCount = 1, instructions = listOf(FakeInsn(0, "return-void", Opcode.RETURN_VOID))),
        )
        val smali = SmaliPrinter.render(FakeClass(methods = listOf(ok1, boom, registersBoom, ok2)))

        // Balanced structure: one `.end method` per `.method`, no doubled `.method` from a leaked stub.
        val methodCount = countOccurrences(smali, ".method ")
        val endCount = countOccurrences(smali, ".end method")
        assertEquals(4, methodCount, "expected 4 .method headers, smali:\n$smali")
        assertEquals(methodCount, endCount, "every .method must be closed exactly once, smali:\n$smali")
        // The two failing methods each degrade to an error comment.
        assertContains(smali, "# <error rendering method:")
        // No half-written block: the error stub for `boomy` never emitted a `.registers` before failing.
        assertTrue(
            !smali.contains(".registers 2\n.method"),
            "a failed body must not leave a dangling unclosed block, smali:\n$smali",
        )
        // Siblings survived intact.
        assertContains(smali, ".method public static test(I)V")
        assertContains(smali, "    return-void")
    }

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var idx = text.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = text.indexOf(needle, idx + needle.length)
        }
        return count
    }

    @Test
    fun abstractMethodHasNoRegistersOrBody() {
        val m = FakeMethod(
            ref = FakeMethodRef("Lp/C;", "abst", "V", emptyList()),
            accessFlags = AccessFlags.PUBLIC or AccessFlags.ABSTRACT,
            code = null,
        )
        val smali = SmaliPrinter.render(FakeClass(methods = listOf(m)))
        assertContains(smali, ".method public abstract abst()V")
        assertTrue(!smali.contains(".registers"), smali)
    }

    // ---- fixtures -------------------------------------------------------------------------------

    /** const/4 v0,1 ; if-eqz v0 -> off 4 ; sget-object v1 field ; :cond_4 invoke-static {v0} ; return-void */
    private fun branchMethod(): FakeMethod = FakeMethod(
        ref = FakeMethodRef("Lp/Demo;", "test", "V", listOf("I")),
        accessFlags = AccessFlags.PUBLIC or AccessFlags.STATIC,
        code = FakeCode(
            registerCount = 2,
            instructions = listOf(
                FakeInsn(0, "const/4", Opcode.CONST, registers = intArrayOf(0), literal = 1),
                FakeInsn(1, "if-eqz", Opcode.IF_EQZ, registers = intArrayOf(0), target = 4),
                FakeInsn(
                    2, "sget-object", Opcode.SGET, registers = intArrayOf(1),
                    indexType = IndexType.FIELD_REF, fieldRef = FakeFieldRef("Lp/C;", "f", "Ljava/lang/String;"),
                ),
                FakeInsn(
                    4, "invoke-static", Opcode.INVOKE_STATIC, registers = intArrayOf(0),
                    indexType = IndexType.METHOD_REF, methodRef = FakeMethodRef("Lp/C;", "m", "V", listOf("I")),
                ),
                FakeInsn(6, "return-void", Opcode.RETURN_VOID),
            ),
        ),
    )
}

// ---- fake SPI implementations -------------------------------------------------------------------

internal class FakeFieldRef(
    override val declaringClassType: String,
    override val name: String,
    override val type: String,
) : FieldRef

internal class FakeMethodRef(
    override val declaringClassType: String,
    override val name: String,
    override val returnType: String,
    override val parameterTypes: List<String>,
) : MethodRef

internal class FakeCatch(
    override val types: List<String>,
    override val handlers: List<Int>,
    override val catchAllHandler: Int,
) : CatchHandler

internal class FakeTry(
    override val startOffset: Int,
    override val endOffset: Int,
    override val catchHandler: CatchHandler,
) : TryBlock

internal class FakeInsn(
    override val offset: Int,
    override val mnemonic: String,
    override val opcode: Opcode,
    private val registers: IntArray = IntArray(0),
    override val literal: Long = 0,
    override val target: Int = 0,
    override val index: Int = 0,
    override val indexType: IndexType = IndexType.NONE,
    override val rawOpcodeUnit: Int = 0,
    override val payload: InstructionPayload? = null,
    private val fieldRef: FieldRef? = null,
    private val methodRef: MethodRef? = null,
    private val stringVal: String? = null,
    private val typeVal: String? = null,
    private val decodeThrows: Boolean = false,
) : Instruction {
    override fun decode() {
        if (decodeThrows) throw IllegalStateException("boom decoding insn @$offset")
    }
    override val fileOffset: Int get() = offset
    override val registerCount: Int get() = registers.size
    override fun register(argNum: Int): Int = registers[argNum]
    override val resultRegister: Int get() = -1
    override fun indexAsString(): String = stringVal ?: error("no string")
    override fun indexAsType(): String = typeVal ?: error("no type")
    override fun indexAsField(): FieldRef = fieldRef ?: error("no field")
    override fun indexAsMethod(): MethodRef = methodRef ?: error("no method")
    override fun indexAsProto(protoIndex: Int): MethodProto = error("no proto")
    override fun indexAsCallSite() = error("no call site")
    override fun indexAsMethodHandle() = error("no method handle")
}

internal class FakeCode(
    registerCount: Int,
    private val instructions: List<Instruction>,
    override val tries: List<TryBlock> = emptyList(),
    override val debugInfo: DebugInfo? = null,
    private val registerCountThrows: Boolean = false,
) : CodeReader {
    private val registers = registerCount
    override val registerCount: Int get() = if (registerCountThrows) throw IllegalStateException("boom registers") else registers
    override val unitsCount: Int get() = instructions.lastOrNull()?.let { it.offset + 1 } ?: 0
    override val codeOffset: Int get() = 0
    override fun visitInstructions(visitor: (Instruction) -> Unit) {
        for (insn in instructions) visitor(insn)
    }
}

internal class FakeField(
    override val declaringClassType: String,
    override val name: String,
    override val type: String,
    override val accessFlags: Int,
    override val constValue: EncodedValue?,
) : FieldData {
    override val annotations: List<AnnotationData> = emptyList()
}

internal class FakeMethod(
    override val ref: MethodRef,
    override val accessFlags: Int,
    private val code: CodeReader?,
) : MethodData {
    override val annotations: List<AnnotationData> = emptyList()
    override val parameterAnnotations: List<List<AnnotationData>> = emptyList()
    override val codeReader: CodeReader? get() = code
}

internal class FakeClass(
    override val type: String = "Lp/Demo;",
    override val accessFlags: Int = AccessFlags.PUBLIC,
    override val superType: String? = "Ljava/lang/Object;",
    override val interfaces: List<String> = emptyList(),
    override val sourceFile: String? = null,
    override val fields: List<FieldData> = emptyList(),
    override val methods: List<MethodData> = emptyList(),
) : ClassData {
    override val annotations: List<AnnotationData> = emptyList()
    override val inputFileName: String = "test.dex"
    override fun disassemble(): String = SmaliPrinter.render(this)
}
