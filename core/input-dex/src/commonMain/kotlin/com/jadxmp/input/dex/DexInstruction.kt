package com.jadxmp.input.dex

import com.jadxmp.input.CallSite
import com.jadxmp.input.FieldRef
import com.jadxmp.input.FillArrayDataPayload
import com.jadxmp.input.IndexType
import com.jadxmp.input.Instruction
import com.jadxmp.input.InstructionPayload
import com.jadxmp.input.MethodHandle
import com.jadxmp.input.MethodProto
import com.jadxmp.input.MethodRef
import com.jadxmp.input.Opcode
import com.jadxmp.input.SwitchPayload
import com.jadxmp.io.ByteReader
import com.jadxmp.io.ByteReaderException

/**
 * A reusable [Instruction] cursor over a DEX code stream. One instance is mutated in place across a
 * method's instruction stream (see the streaming contract on [Instruction]); [reset] re-points it at
 * the next raw instruction and [decode]/[skip] read or step over its operands.
 *
 * jadx: DexInsnData + DexInsnFormat (the per-format decode logic is inlined here as a `when`).
 */
internal class DexInstruction(
    private val dex: Dex,
    private val code: ByteReader,
) : Instruction {

    private var info: DexInsnInfo? = null
    private var argsReg = IntArray(5)

    private var offsetValue = 0
    private var fileOffsetValue = 0
    private var rawUnit = 0
    private var lengthValue = 1
    private var regCount = 0
    private var literalValue = 0L
    private var targetValue = 0
    private var indexValue = 0
    private var payloadValue: InstructionPayload? = null
    var decoded = false
        private set

    val length: Int get() = lengthValue

    /** Re-point this cursor at a freshly-read raw instruction. */
    fun reset(fileOffset: Int, offset: Int, opcodeUnit: Int, insnInfo: DexInsnInfo?) {
        this.info = insnInfo
        this.fileOffsetValue = fileOffset
        this.offsetValue = offset
        this.rawUnit = opcodeUnit
        this.lengthValue = insnInfo?.format?.length ?: 1
        this.regCount = insnInfo?.format?.registerCount?.coerceAtLeast(0) ?: 0
        this.literalValue = 0L
        this.targetValue = 0
        this.indexValue = 0
        this.payloadValue = null
        this.decoded = false
        // Clear operand registers so a caller that reads register() before decode() cannot silently
        // observe the previous instruction's registers (the cursor object is reused per method).
        this.argsReg.fill(0)
    }

    override val offset: Int get() = offsetValue
    override val fileOffset: Int get() = fileOffsetValue
    override val opcode: Opcode get() = info?.apiOpcode ?: Opcode.UNKNOWN
    override val mnemonic: String get() = info?.mnemonic ?: "unknown-0x${rawUnit.toString(16)}"
    override val rawOpcodeUnit: Int get() = rawUnit
    override val indexType: IndexType get() = info?.indexType ?: IndexType.NONE
    override val registerCount: Int get() = regCount
    override fun register(argNum: Int): Int {
        // Loud failure beats silent garbage: operands are only valid after decode().
        check(decoded) { "register($argNum) read before decode()" }
        return argsReg[argNum]
    }
    override val resultRegister: Int get() = -1
    override val literal: Long get() = literalValue
    override val target: Int get() = targetValue
    override val index: Int get() = indexValue
    override val payload: InstructionPayload? get() = payloadValue

    override fun indexAsString(): String = dex.string(indexValue) ?: throw ByteReaderException("no string #$indexValue")
    override fun indexAsType(): String = dex.type(indexValue) ?: throw ByteReaderException("no type #$indexValue")
    override fun indexAsField(): FieldRef = dex.fieldRef(indexValue)
    override fun indexAsMethod(): MethodRef = dex.methodRef(indexValue)
    override fun indexAsProto(protoIndex: Int): MethodProto = dex.proto(protoIndex)
    override fun indexAsCallSite(): CallSite = dex.callSite(indexValue)
    override fun indexAsMethodHandle(): MethodHandle = dex.methodHandle(indexValue)

    override fun decode() {
        if (info == null || decoded) return
        decodeFormat(info!!.format)
        decoded = true
    }

    /** Advance [code] past this instruction's operands without materializing them. */
    fun skip() {
        val fmt = info?.format
        if (fmt == null) {
            return // unknown opcode: 1 unit, nothing to skip
        }
        when (fmt) {
            DexFormat.PACKED_SWITCH_PAYLOAD -> {
                val size = code.readU16()
                code.skip(4 + size * 4)
                lengthValue = size * 2 + 4
            }
            DexFormat.SPARSE_SWITCH_PAYLOAD -> {
                val size = code.readU16()
                code.skip(size * 8)
                lengthValue = size * 4 + 2
            }
            DexFormat.FILL_ARRAY_DATA_PAYLOAD -> {
                val elemSize = code.readU16()
                val size = code.readS32()
                // Mirror decodeFillArray()'s guard: `size` is attacker-controlled. Validate it against
                // the bytes remaining before it drives the skip amount, so a crafted huge/negative size
                // fails as a catchable ByteReaderException instead of overflowing `size * elemSize` or
                // tripping ByteReader.skip's negative-count IllegalArgumentException. After the check,
                // size * elemSize <= remaining, so the Int arithmetic here cannot overflow.
                Bounds.checkCount(size, stride = elemSize.coerceAtLeast(1), reader = code)
                if (elemSize == 1) code.skip(size + size % 2) else code.skip(size * elemSize)
                lengthValue = (size * elemSize + 1) / 2 + 4
            }
            else -> if (fmt.length > 1) code.skip((fmt.length - 1) * 2)
        }
    }

    private fun decodeFormat(fmt: DexFormat) {
        val u = rawUnit
        when (fmt) {
            DexFormat.F10X -> {}
            DexFormat.F12X -> {
                argsReg[0] = nibble2(u); argsReg[1] = nibble3(u)
            }
            DexFormat.F11N -> {
                argsReg[0] = nibble2(u); literalValue = signedNibble3(u).toLong()
            }
            DexFormat.F11X -> argsReg[0] = byte1(u)
            DexFormat.F10T -> targetValue = offsetValue + signedByte1(u)
            DexFormat.F20T -> targetValue = offsetValue + code.readS16()
            DexFormat.F22X -> {
                argsReg[0] = byte1(u); argsReg[1] = code.readU16()
            }
            DexFormat.F21T -> {
                argsReg[0] = byte1(u); targetValue = offsetValue + code.readS16()
            }
            DexFormat.F21S -> {
                argsReg[0] = byte1(u); literalValue = code.readS16().toLong()
            }
            DexFormat.F21H -> {
                argsReg[0] = byte1(u)
                var lit = code.readS16().toLong()
                lit = lit shl (if ((u and 0xFF) == 0x15) 16 else 48)
                literalValue = lit
            }
            DexFormat.F21C -> {
                argsReg[0] = byte1(u); indexValue = code.readU16()
            }
            DexFormat.F23X -> {
                argsReg[0] = byte1(u)
                val next = code.readU16()
                argsReg[1] = next and 0xFF
                argsReg[2] = (next ushr 8) and 0xFF
            }
            DexFormat.F22B -> {
                argsReg[0] = byte1(u)
                val next = code.readU16()
                argsReg[1] = next and 0xFF
                literalValue = signedByte1(next).toLong()
            }
            DexFormat.F22T -> {
                argsReg[0] = nibble2(u); argsReg[1] = nibble3(u)
                targetValue = offsetValue + code.readS16()
            }
            DexFormat.F22S -> {
                argsReg[0] = nibble2(u); argsReg[1] = nibble3(u)
                literalValue = code.readS16().toLong()
            }
            DexFormat.F22C -> {
                argsReg[0] = nibble2(u); argsReg[1] = nibble3(u)
                indexValue = code.readU16()
            }
            DexFormat.F30T -> targetValue = offsetValue + code.readS32()
            DexFormat.F32X -> {
                argsReg[0] = code.readU16(); argsReg[1] = code.readU16()
            }
            DexFormat.F31I -> {
                argsReg[0] = byte1(u); literalValue = code.readS32().toLong()
            }
            DexFormat.F31T -> {
                argsReg[0] = byte1(u); targetValue = offsetValue + code.readS32()
            }
            DexFormat.F31C -> {
                argsReg[0] = byte1(u); indexValue = code.readS32()
            }
            DexFormat.F35C -> readRegList(u)
            DexFormat.F3RC -> readRegRange(u)
            DexFormat.F45CC -> {
                readRegList(u); targetValue = code.readU16()
            }
            DexFormat.F4RCC -> {
                readRegRange(u); targetValue = code.readU16()
            }
            DexFormat.F51I -> {
                argsReg[0] = byte1(u); literalValue = code.readS64()
            }
            DexFormat.PACKED_SWITCH_PAYLOAD -> {
                val size = code.readU16()
                val firstKey = code.readS32()
                Bounds.checkCount(size, stride = 4, reader = code) // size targets follow
                val keys = IntArray(size)
                val targets = IntArray(size)
                for (i in 0 until size) {
                    targets[i] = code.readS32()
                    keys[i] = firstKey + i
                }
                payloadValue = SwitchPayload(keys, targets)
                lengthValue = size * 2 + 4
            }
            DexFormat.SPARSE_SWITCH_PAYLOAD -> {
                val size = code.readU16()
                Bounds.checkCount(size, stride = 8, reader = code) // size keys + size targets follow
                val keys = IntArray(size) { code.readS32() }
                val targets = IntArray(size) { code.readS32() }
                payloadValue = SwitchPayload(keys, targets)
                lengthValue = size * 4 + 2
            }
            DexFormat.FILL_ARRAY_DATA_PAYLOAD -> decodeFillArray()
        }
    }

    private fun decodeFillArray() {
        val elemSize = code.readU16()
        val size = code.readS32()
        // Validate the element count against the bytes remaining before allocating the array, so a
        // crafted huge or negative size fails gracefully instead of OOM-ing / throwing NegativeArraySize.
        Bounds.checkCount(size, stride = elemSize.coerceAtLeast(1), reader = code)
        val data: Any = when (elemSize) {
            0 -> ByteArray(0)
            1 -> code.readBytes(size).also { if (size % 2 != 0) code.readU8() }
            2 -> ShortArray(size) { code.readS16().toShort() }
            4 -> IntArray(size) { code.readS32() }
            8 -> LongArray(size) { code.readS64() }
            else -> throw ByteReaderException("bad fill-array element size $elemSize")
        }
        lengthValue = (size * elemSize + 1) / 2 + 4
        payloadValue = FillArrayDataPayload(size, elemSize, data)
    }

    private fun readRegList(u: Int) {
        val count = nibble3(u)
        indexValue = code.readU16()
        val rs = code.readU16()
        argsReg[0] = nibble0(rs)
        argsReg[1] = nibble1(rs)
        argsReg[2] = nibble2(rs)
        argsReg[3] = nibble3(rs)
        argsReg[4] = nibble2(u)
        regCount = count
    }

    private fun readRegRange(u: Int) {
        val count = byte1(u)
        indexValue = code.readU16()
        val startReg = code.readU16()
        if (argsReg.size < count) argsReg = IntArray(count)
        for (i in 0 until count) argsReg[i] = startReg + i
        regCount = count
    }

    private companion object {
        fun byte1(v: Int): Int = (v ushr 8) and 0xFF
        fun signedByte1(v: Int): Int = ((v ushr 8) and 0xFF).toByte().toInt()
        fun nibble0(v: Int): Int = v and 0xF
        fun nibble1(v: Int): Int = (v ushr 4) and 0xF
        fun nibble2(v: Int): Int = (v ushr 8) and 0xF
        fun nibble3(v: Int): Int = (v ushr 12) and 0xF
        fun signedNibble3(v: Int): Int = (((v ushr 12) and 0xF) shl 28) shr 28
    }
}
