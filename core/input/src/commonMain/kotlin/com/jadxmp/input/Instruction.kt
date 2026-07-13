package com.jadxmp.input

/**
 * Classifies what an instruction's [Instruction.index] refers to, so a consumer knows which
 * `indexAs…` resolver to call.
 *
 * jadx: InsnIndexType
 */
public enum class IndexType {
    NONE,
    TYPE_REF,
    STRING_REF,
    FIELD_REF,
    METHOD_REF,
    PROTO_REF,
    METHOD_HANDLE_REF,
    CALL_SITE,
}

/**
 * Extra data attached to instructions whose operands live in a separate payload table
 * (`packed-switch`, `sparse-switch`, `fill-array-data`).
 *
 * jadx: ICustomPayload and subtypes
 */
public sealed interface InstructionPayload

/**
 * The case table of a `packed-switch`/`sparse-switch`. [keys] and [targets] are parallel arrays;
 * `targets[i]` is the branch offset (relative to the switch instruction) taken for `keys[i]`.
 *
 * jadx: ISwitchPayload
 */
public class SwitchPayload(
    public val keys: IntArray,
    public val targets: IntArray,
) : InstructionPayload {
    public val size: Int get() = keys.size
}

/**
 * The initializer blob of a `fill-array-data`. [data] is one of `ByteArray`/`ShortArray`/`IntArray`/
 * `LongArray` depending on [elementSize] (1/2/4/8), or an empty `ByteArray` when there are no elements.
 *
 * jadx: IArrayPayload
 */
public class FillArrayDataPayload(
    public val size: Int,
    public val elementSize: Int,
    public val data: Any,
) : InstructionPayload

/**
 * A single decoded instruction, surfaced by [CodeReader.visitInstructions].
 *
 * ### Streaming contract
 * The same [Instruction] object is reused for every instruction in a method (to avoid per-insn
 * allocation), so a consumer must extract everything it needs before the visit callback returns —
 * do not retain the [Instruction] reference. Register/operand accessors are only valid **after**
 * [decode] has been called for the current instruction; before that, only [offset], [opcode],
 * [rawOpcodeUnit], [fileOffset], and [indexType] are meaningful.
 *
 * jadx: InsnData
 */
public interface Instruction {
    /** Populate operand fields (registers, literal, target, index, payload) for the current insn. */
    public fun decode()

    /** Offset of this instruction within the method, in 16-bit code units. */
    public val offset: Int

    /** Absolute byte offset of this instruction within the input file (useful for disassembly). */
    public val fileOffset: Int

    public val opcode: Opcode

    /** Human-readable mnemonic of the raw (pre-normalization) opcode, for smali/diagnostics. */
    public val mnemonic: String

    /** The raw first code unit as read from the stream (format-specific). */
    public val rawOpcodeUnit: Int

    public val indexType: IndexType

    public val registerCount: Int

    public fun register(argNum: Int): Int

    /**
     * Some formats fuse the result register into the instruction instead of a following move-result.
     * Returns the result register, or -1 when a separate move-result carries it.
     */
    public val resultRegister: Int

    public val literal: Long

    /** Branch/switch target, as a code-unit offset within the method. */
    public val target: Int

    /** Raw constant-pool style index; interpret via [indexType] and the `indexAs…` resolvers. */
    public val index: Int

    public fun indexAsString(): String

    public fun indexAsType(): String

    public fun indexAsField(): FieldRef

    public fun indexAsMethod(): MethodRef

    public fun indexAsProto(protoIndex: Int): MethodProto

    public fun indexAsCallSite(): CallSite

    public fun indexAsMethodHandle(): MethodHandle

    public val payload: InstructionPayload?
}
