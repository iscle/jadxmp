package com.jadxmp.pipeline.support

import com.jadxmp.input.CallSite
import com.jadxmp.input.CatchHandler
import com.jadxmp.input.CodeReader
import com.jadxmp.input.DebugInfo
import com.jadxmp.input.FieldRef
import com.jadxmp.input.IndexType
import com.jadxmp.input.Instruction
import com.jadxmp.input.InstructionPayload
import com.jadxmp.input.MethodHandle
import com.jadxmp.input.MethodProto
import com.jadxmp.input.MethodRef
import com.jadxmp.input.Opcode
import com.jadxmp.input.TryBlock

/**
 * A hand-built [CodeReader] over a list of [Insn] specs, for `commonTest`. Lets a test express a
 * method body directly as normalized opcodes + operands without a real DEX, exercising [MethodDecoder]
 * through the exact same SPI a parser presents (including the reused-cursor streaming contract).
 */

/** Field reference test double. */
data class FakeFieldRef(
    override val declaringClassType: String,
    override val name: String,
    override val type: String,
) : FieldRef

/** Method reference test double. */
data class FakeMethodRef(
    override val declaringClassType: String,
    override val name: String,
    override val returnType: String,
    override val parameterTypes: List<String>,
) : MethodRef

/** A single normalized instruction spec. Fields mirror the [Instruction] accessors used by decode. */
class Insn(
    val opcode: Opcode,
    val offset: Int,
    val registers: IntArray = IntArray(0),
    val literal: Long = 0L,
    val target: Int = 0,
    val indexType: IndexType = IndexType.NONE,
    val stringValue: String? = null,
    val typeValue: String? = null,
    val fieldRef: FieldRef? = null,
    val methodRef: MethodRef? = null,
    val payload: InstructionPayload? = null,
)

class FakeCatchHandler(
    override val types: List<String>,
    override val handlers: List<Int>,
    override val catchAllHandler: Int,
) : CatchHandler

class FakeTryBlock(
    override val startOffset: Int,
    override val endOffset: Int,
    override val catchHandler: CatchHandler,
) : TryBlock

class FakeCodeReader(
    override val registerCount: Int,
    private val insns: List<Insn>,
    override val tries: List<TryBlock> = emptyList(),
) : CodeReader {
    override val unitsCount: Int get() = insns.lastOrNull()?.let { it.offset + 1 } ?: 0
    override val codeOffset: Int get() = 0
    override val debugInfo: DebugInfo? get() = null

    override fun visitInstructions(visitor: (Instruction) -> Unit) {
        val cursor = Cursor()
        for (spec in insns) {
            cursor.spec = spec
            visitor(cursor)
        }
    }

    /** The reused cursor — a fresh object is not made per instruction, matching the SPI contract. */
    private class Cursor : Instruction {
        lateinit var spec: Insn
        override fun decode() {}
        override val offset: Int get() = spec.offset
        override val fileOffset: Int get() = spec.offset
        override val opcode: Opcode get() = spec.opcode
        override val mnemonic: String get() = spec.opcode.name
        override val rawOpcodeUnit: Int get() = 0
        override val indexType: IndexType get() = spec.indexType
        override val registerCount: Int get() = spec.registers.size
        override fun register(argNum: Int): Int = spec.registers[argNum]
        override val resultRegister: Int get() = -1
        override val literal: Long get() = spec.literal
        override val target: Int get() = spec.target
        override val index: Int get() = 0
        override fun indexAsString(): String = spec.stringValue!!
        override fun indexAsType(): String = spec.typeValue!!
        override fun indexAsField(): FieldRef = spec.fieldRef!!
        override fun indexAsMethod(): MethodRef = spec.methodRef!!
        override fun indexAsProto(protoIndex: Int): MethodProto = error("unused")
        override fun indexAsCallSite(): CallSite = error("unused")
        override fun indexAsMethodHandle(): MethodHandle = error("unused")
        override val payload: InstructionPayload? get() = spec.payload
    }
}
