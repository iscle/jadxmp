package com.jadxmp.input.dex

import com.jadxmp.input.IndexType
import com.jadxmp.input.Opcode

/**
 * The wire-level instruction formats of the DEX spec (§"Instruction Formats"). Each carries its size
 * in 16-bit code units ([length]) and how many registers it addresses ([registerCount], -1 when
 * variable, as for the `35c`/`3rc` invoke shapes). Payload pseudo-formats have a length computed at
 * decode time.
 *
 * jadx: DexInsnFormat
 */
internal enum class DexFormat(val length: Int, val registerCount: Int) {
    F10X(1, 0),
    F12X(1, 2),
    F11N(1, 1),
    F11X(1, 1),
    F10T(1, 0),
    F20T(2, 0),
    F22X(2, 2),
    F21T(2, 1),
    F21S(2, 1),
    F21H(2, 1),
    F21C(2, 1),
    F23X(2, 3),
    F22B(2, 2),
    F22T(2, 2),
    F22S(2, 2),
    F22C(2, 2),
    F30T(3, 0),
    F32X(3, 2),
    F31I(3, 1),
    F31T(3, 1),
    F31C(3, 1),
    F35C(3, -1),
    F3RC(3, -1),
    F45CC(4, -1),
    F4RCC(4, -1),
    F51I(5, 1),
    PACKED_SWITCH_PAYLOAD(-1, -1),
    SPARSE_SWITCH_PAYLOAD(-1, -1),
    FILL_ARRAY_DATA_PAYLOAD(-1, -1),
}

/** Everything the decoder needs to know about one raw opcode. jadx: DexInsnInfo */
internal class DexInsnInfo(
    val apiOpcode: Opcode,
    val format: DexFormat,
    val indexType: IndexType,
    val mnemonic: String,
)

/**
 * Maps each raw DEX opcode (0x00..0xFF) to its normalized [Opcode], wire [DexFormat], and operand
 * [IndexType]. This is the single point where DEX's ~230 redundant opcodes fold into the compact
 * normalized set the IR consumes. The three payload pseudo-ops (encoded as a zero low byte with a
 * non-zero high byte) live in a separate map.
 *
 * jadx: DexInsnInfo (static table)
 */
internal object DexOpcodeTable {
    private val table = arrayOfNulls<DexInsnInfo>(0x100)
    private val payloads = HashMap<Int, DexInsnInfo>(3)

    fun lookup(opcodeUnit: Int): DexInsnInfo? {
        val op = opcodeUnit and 0xFF
        if (op == 0 && opcodeUnit != 0) {
            return payloads[opcodeUnit]
        }
        return table[op]
    }

    private fun reg(raw: Int, api: Opcode, fmt: DexFormat, mnem: String, idx: IndexType = IndexType.NONE) {
        table[raw] = DexInsnInfo(api, fmt, idx, mnem)
    }

    private fun payload(raw: Int, api: Opcode, fmt: DexFormat, mnem: String) {
        payloads[raw] = DexInsnInfo(api, fmt, IndexType.NONE, mnem)
    }

    init {
        val N = IndexType.NONE
        val T = IndexType.TYPE_REF
        val S = IndexType.STRING_REF
        val F = IndexType.FIELD_REF
        val M = IndexType.METHOD_REF
        val C = IndexType.CALL_SITE

        reg(0x00, Opcode.NOP, DexFormat.F10X, "nop")

        reg(0x01, Opcode.MOVE, DexFormat.F12X, "move")
        reg(0x02, Opcode.MOVE, DexFormat.F22X, "move/from16")
        reg(0x03, Opcode.MOVE, DexFormat.F32X, "move/16")
        reg(0x04, Opcode.MOVE_WIDE, DexFormat.F12X, "move-wide")
        reg(0x05, Opcode.MOVE_WIDE, DexFormat.F22X, "move-wide/from16")
        reg(0x06, Opcode.MOVE_WIDE, DexFormat.F32X, "move-wide/16")
        reg(0x07, Opcode.MOVE_OBJECT, DexFormat.F12X, "move-object")
        reg(0x08, Opcode.MOVE_OBJECT, DexFormat.F22X, "move-object/from16")
        reg(0x09, Opcode.MOVE_OBJECT, DexFormat.F32X, "move-object/16")
        reg(0x0a, Opcode.MOVE_RESULT, DexFormat.F11X, "move-result")
        reg(0x0b, Opcode.MOVE_RESULT, DexFormat.F11X, "move-result-wide")
        reg(0x0c, Opcode.MOVE_RESULT, DexFormat.F11X, "move-result-object")
        reg(0x0d, Opcode.MOVE_EXCEPTION, DexFormat.F11X, "move-exception")

        reg(0x0e, Opcode.RETURN_VOID, DexFormat.F10X, "return-void")
        reg(0x0f, Opcode.RETURN, DexFormat.F11X, "return")
        reg(0x10, Opcode.RETURN, DexFormat.F11X, "return-wide")
        reg(0x11, Opcode.RETURN, DexFormat.F11X, "return-object")

        reg(0x12, Opcode.CONST, DexFormat.F11N, "const/4")
        reg(0x13, Opcode.CONST, DexFormat.F21S, "const/16")
        reg(0x14, Opcode.CONST, DexFormat.F31I, "const")
        reg(0x15, Opcode.CONST, DexFormat.F21H, "const/high16")
        reg(0x16, Opcode.CONST_WIDE, DexFormat.F21S, "const-wide/16")
        reg(0x17, Opcode.CONST_WIDE, DexFormat.F31I, "const-wide/32")
        reg(0x18, Opcode.CONST_WIDE, DexFormat.F51I, "const-wide")
        reg(0x19, Opcode.CONST_WIDE, DexFormat.F21H, "const-wide/high16")
        reg(0x1a, Opcode.CONST_STRING, DexFormat.F21C, "const-string", S)
        reg(0x1b, Opcode.CONST_STRING, DexFormat.F31C, "const-string/jumbo", S)
        reg(0x1c, Opcode.CONST_CLASS, DexFormat.F21C, "const-class", T)

        reg(0x1d, Opcode.MONITOR_ENTER, DexFormat.F11X, "monitor-enter")
        reg(0x1e, Opcode.MONITOR_EXIT, DexFormat.F11X, "monitor-exit")
        reg(0x1f, Opcode.CHECK_CAST, DexFormat.F21C, "check-cast", T)
        reg(0x20, Opcode.INSTANCE_OF, DexFormat.F22C, "instance-of", T)
        reg(0x21, Opcode.ARRAY_LENGTH, DexFormat.F12X, "array-length")
        reg(0x22, Opcode.NEW_INSTANCE, DexFormat.F21C, "new-instance", T)
        reg(0x23, Opcode.NEW_ARRAY, DexFormat.F22C, "new-array", T)
        reg(0x24, Opcode.FILLED_NEW_ARRAY, DexFormat.F35C, "filled-new-array", T)
        reg(0x25, Opcode.FILLED_NEW_ARRAY_RANGE, DexFormat.F3RC, "filled-new-array/range", T)
        reg(0x26, Opcode.FILL_ARRAY_DATA, DexFormat.F31T, "fill-array-data")

        reg(0x27, Opcode.THROW, DexFormat.F11X, "throw")
        reg(0x28, Opcode.GOTO, DexFormat.F10T, "goto")
        reg(0x29, Opcode.GOTO, DexFormat.F20T, "goto/16")
        reg(0x2a, Opcode.GOTO, DexFormat.F30T, "goto/32")
        reg(0x2b, Opcode.PACKED_SWITCH, DexFormat.F31T, "packed-switch")
        reg(0x2c, Opcode.SPARSE_SWITCH, DexFormat.F31T, "sparse-switch")

        reg(0x2d, Opcode.CMPL_FLOAT, DexFormat.F23X, "cmpl-float")
        reg(0x2e, Opcode.CMPG_FLOAT, DexFormat.F23X, "cmpg-float")
        reg(0x2f, Opcode.CMPL_DOUBLE, DexFormat.F23X, "cmpl-double")
        reg(0x30, Opcode.CMPG_DOUBLE, DexFormat.F23X, "cmpg-double")
        reg(0x31, Opcode.CMP_LONG, DexFormat.F23X, "cmp-long")

        reg(0x32, Opcode.IF_EQ, DexFormat.F22T, "if-eq")
        reg(0x33, Opcode.IF_NE, DexFormat.F22T, "if-ne")
        reg(0x34, Opcode.IF_LT, DexFormat.F22T, "if-lt")
        reg(0x35, Opcode.IF_GE, DexFormat.F22T, "if-ge")
        reg(0x36, Opcode.IF_GT, DexFormat.F22T, "if-gt")
        reg(0x37, Opcode.IF_LE, DexFormat.F22T, "if-le")
        reg(0x38, Opcode.IF_EQZ, DexFormat.F21T, "if-eqz")
        reg(0x39, Opcode.IF_NEZ, DexFormat.F21T, "if-nez")
        reg(0x3a, Opcode.IF_LTZ, DexFormat.F21T, "if-ltz")
        reg(0x3b, Opcode.IF_GEZ, DexFormat.F21T, "if-gez")
        reg(0x3c, Opcode.IF_GTZ, DexFormat.F21T, "if-gtz")
        reg(0x3d, Opcode.IF_LEZ, DexFormat.F21T, "if-lez")

        reg(0x44, Opcode.AGET, DexFormat.F23X, "aget")
        reg(0x45, Opcode.AGET_WIDE, DexFormat.F23X, "aget-wide")
        reg(0x46, Opcode.AGET_OBJECT, DexFormat.F23X, "aget-object")
        reg(0x47, Opcode.AGET_BOOLEAN, DexFormat.F23X, "aget-boolean")
        reg(0x48, Opcode.AGET_BYTE, DexFormat.F23X, "aget-byte")
        reg(0x49, Opcode.AGET_CHAR, DexFormat.F23X, "aget-char")
        reg(0x4a, Opcode.AGET_SHORT, DexFormat.F23X, "aget-short")
        reg(0x4b, Opcode.APUT, DexFormat.F23X, "aput")
        reg(0x4c, Opcode.APUT_WIDE, DexFormat.F23X, "aput-wide")
        reg(0x4d, Opcode.APUT_OBJECT, DexFormat.F23X, "aput-object")
        reg(0x4e, Opcode.APUT_BOOLEAN, DexFormat.F23X, "aput-boolean")
        reg(0x4f, Opcode.APUT_BYTE, DexFormat.F23X, "aput-byte")
        reg(0x50, Opcode.APUT_CHAR, DexFormat.F23X, "aput-char")
        reg(0x51, Opcode.APUT_SHORT, DexFormat.F23X, "aput-short")

        reg(0x52, Opcode.IGET, DexFormat.F22C, "iget", F)
        reg(0x53, Opcode.IGET, DexFormat.F22C, "iget-wide", F)
        reg(0x54, Opcode.IGET, DexFormat.F22C, "iget-object", F)
        reg(0x55, Opcode.IGET, DexFormat.F22C, "iget-boolean", F)
        reg(0x56, Opcode.IGET, DexFormat.F22C, "iget-byte", F)
        reg(0x57, Opcode.IGET, DexFormat.F22C, "iget-char", F)
        reg(0x58, Opcode.IGET, DexFormat.F22C, "iget-short", F)
        reg(0x59, Opcode.IPUT, DexFormat.F22C, "iput", F)
        reg(0x5a, Opcode.IPUT, DexFormat.F22C, "iput-wide", F)
        reg(0x5b, Opcode.IPUT, DexFormat.F22C, "iput-object", F)
        reg(0x5c, Opcode.IPUT, DexFormat.F22C, "iput-boolean", F)
        reg(0x5d, Opcode.IPUT, DexFormat.F22C, "iput-byte", F)
        reg(0x5e, Opcode.IPUT, DexFormat.F22C, "iput-char", F)
        reg(0x5f, Opcode.IPUT, DexFormat.F22C, "iput-short", F)

        reg(0x60, Opcode.SGET, DexFormat.F21C, "sget", F)
        reg(0x61, Opcode.SGET, DexFormat.F21C, "sget-wide", F)
        reg(0x62, Opcode.SGET, DexFormat.F21C, "sget-object", F)
        reg(0x63, Opcode.SGET, DexFormat.F21C, "sget-boolean", F)
        reg(0x64, Opcode.SGET, DexFormat.F21C, "sget-byte", F)
        reg(0x65, Opcode.SGET, DexFormat.F21C, "sget-char", F)
        reg(0x66, Opcode.SGET, DexFormat.F21C, "sget-short", F)
        reg(0x67, Opcode.SPUT, DexFormat.F21C, "sput", F)
        reg(0x68, Opcode.SPUT, DexFormat.F21C, "sput-wide", F)
        reg(0x69, Opcode.SPUT, DexFormat.F21C, "sput-object", F)
        reg(0x6a, Opcode.SPUT, DexFormat.F21C, "sput-boolean", F)
        reg(0x6b, Opcode.SPUT, DexFormat.F21C, "sput-byte", F)
        reg(0x6c, Opcode.SPUT, DexFormat.F21C, "sput-char", F)
        reg(0x6d, Opcode.SPUT, DexFormat.F21C, "sput-short", F)

        reg(0x6e, Opcode.INVOKE_VIRTUAL, DexFormat.F35C, "invoke-virtual", M)
        reg(0x6f, Opcode.INVOKE_SUPER, DexFormat.F35C, "invoke-super", M)
        reg(0x70, Opcode.INVOKE_DIRECT, DexFormat.F35C, "invoke-direct", M)
        reg(0x71, Opcode.INVOKE_STATIC, DexFormat.F35C, "invoke-static", M)
        reg(0x72, Opcode.INVOKE_INTERFACE, DexFormat.F35C, "invoke-interface", M)
        reg(0x74, Opcode.INVOKE_VIRTUAL_RANGE, DexFormat.F3RC, "invoke-virtual/range", M)
        reg(0x75, Opcode.INVOKE_SUPER_RANGE, DexFormat.F3RC, "invoke-super/range", M)
        reg(0x76, Opcode.INVOKE_DIRECT_RANGE, DexFormat.F3RC, "invoke-direct/range", M)
        reg(0x77, Opcode.INVOKE_STATIC_RANGE, DexFormat.F3RC, "invoke-static/range", M)
        reg(0x78, Opcode.INVOKE_INTERFACE_RANGE, DexFormat.F3RC, "invoke-interface/range", M)

        reg(0x7b, Opcode.NEG_INT, DexFormat.F12X, "neg-int")
        reg(0x7c, Opcode.NOT_INT, DexFormat.F12X, "not-int")
        reg(0x7d, Opcode.NEG_LONG, DexFormat.F12X, "neg-long")
        reg(0x7e, Opcode.NOT_LONG, DexFormat.F12X, "not-long")
        reg(0x7f, Opcode.NEG_FLOAT, DexFormat.F12X, "neg-float")
        reg(0x80, Opcode.NEG_DOUBLE, DexFormat.F12X, "neg-double")
        reg(0x81, Opcode.INT_TO_LONG, DexFormat.F12X, "int-to-long")
        reg(0x82, Opcode.INT_TO_FLOAT, DexFormat.F12X, "int-to-float")
        reg(0x83, Opcode.INT_TO_DOUBLE, DexFormat.F12X, "int-to-double")
        reg(0x84, Opcode.LONG_TO_INT, DexFormat.F12X, "long-to-int")
        reg(0x85, Opcode.LONG_TO_FLOAT, DexFormat.F12X, "long-to-float")
        reg(0x86, Opcode.LONG_TO_DOUBLE, DexFormat.F12X, "long-to-double")
        reg(0x87, Opcode.FLOAT_TO_INT, DexFormat.F12X, "float-to-int")
        reg(0x88, Opcode.FLOAT_TO_LONG, DexFormat.F12X, "float-to-long")
        reg(0x89, Opcode.FLOAT_TO_DOUBLE, DexFormat.F12X, "float-to-double")
        reg(0x8a, Opcode.DOUBLE_TO_INT, DexFormat.F12X, "double-to-int")
        reg(0x8b, Opcode.DOUBLE_TO_LONG, DexFormat.F12X, "double-to-long")
        reg(0x8c, Opcode.DOUBLE_TO_FLOAT, DexFormat.F12X, "double-to-float")
        reg(0x8d, Opcode.INT_TO_BYTE, DexFormat.F12X, "int-to-byte")
        reg(0x8e, Opcode.INT_TO_CHAR, DexFormat.F12X, "int-to-char")
        reg(0x8f, Opcode.INT_TO_SHORT, DexFormat.F12X, "int-to-short")

        reg(0x90, Opcode.ADD_INT, DexFormat.F23X, "add-int")
        reg(0x91, Opcode.SUB_INT, DexFormat.F23X, "sub-int")
        reg(0x92, Opcode.MUL_INT, DexFormat.F23X, "mul-int")
        reg(0x93, Opcode.DIV_INT, DexFormat.F23X, "div-int")
        reg(0x94, Opcode.REM_INT, DexFormat.F23X, "rem-int")
        reg(0x95, Opcode.AND_INT, DexFormat.F23X, "and-int")
        reg(0x96, Opcode.OR_INT, DexFormat.F23X, "or-int")
        reg(0x97, Opcode.XOR_INT, DexFormat.F23X, "xor-int")
        reg(0x98, Opcode.SHL_INT, DexFormat.F23X, "shl-int")
        reg(0x99, Opcode.SHR_INT, DexFormat.F23X, "shr-int")
        reg(0x9a, Opcode.USHR_INT, DexFormat.F23X, "ushr-int")
        reg(0x9b, Opcode.ADD_LONG, DexFormat.F23X, "add-long")
        reg(0x9c, Opcode.SUB_LONG, DexFormat.F23X, "sub-long")
        reg(0x9d, Opcode.MUL_LONG, DexFormat.F23X, "mul-long")
        reg(0x9e, Opcode.DIV_LONG, DexFormat.F23X, "div-long")
        reg(0x9f, Opcode.REM_LONG, DexFormat.F23X, "rem-long")
        reg(0xa0, Opcode.AND_LONG, DexFormat.F23X, "and-long")
        reg(0xa1, Opcode.OR_LONG, DexFormat.F23X, "or-long")
        reg(0xa2, Opcode.XOR_LONG, DexFormat.F23X, "xor-long")
        reg(0xa3, Opcode.SHL_LONG, DexFormat.F23X, "shl-long")
        reg(0xa4, Opcode.SHR_LONG, DexFormat.F23X, "shr-long")
        reg(0xa5, Opcode.USHR_LONG, DexFormat.F23X, "ushr-long")
        reg(0xa6, Opcode.ADD_FLOAT, DexFormat.F23X, "add-float")
        reg(0xa7, Opcode.SUB_FLOAT, DexFormat.F23X, "sub-float")
        reg(0xa8, Opcode.MUL_FLOAT, DexFormat.F23X, "mul-float")
        reg(0xa9, Opcode.DIV_FLOAT, DexFormat.F23X, "div-float")
        reg(0xaa, Opcode.REM_FLOAT, DexFormat.F23X, "rem-float")
        reg(0xab, Opcode.ADD_DOUBLE, DexFormat.F23X, "add-double")
        reg(0xac, Opcode.SUB_DOUBLE, DexFormat.F23X, "sub-double")
        reg(0xad, Opcode.MUL_DOUBLE, DexFormat.F23X, "mul-double")
        reg(0xae, Opcode.DIV_DOUBLE, DexFormat.F23X, "div-double")
        reg(0xaf, Opcode.REM_DOUBLE, DexFormat.F23X, "rem-double")

        reg(0xb0, Opcode.ADD_INT, DexFormat.F12X, "add-int/2addr")
        reg(0xb1, Opcode.SUB_INT, DexFormat.F12X, "sub-int/2addr")
        reg(0xb2, Opcode.MUL_INT, DexFormat.F12X, "mul-int/2addr")
        reg(0xb3, Opcode.DIV_INT, DexFormat.F12X, "div-int/2addr")
        reg(0xb4, Opcode.REM_INT, DexFormat.F12X, "rem-int/2addr")
        reg(0xb5, Opcode.AND_INT, DexFormat.F12X, "and-int/2addr")
        reg(0xb6, Opcode.OR_INT, DexFormat.F12X, "or-int/2addr")
        reg(0xb7, Opcode.XOR_INT, DexFormat.F12X, "xor-int/2addr")
        reg(0xb8, Opcode.SHL_INT, DexFormat.F12X, "shl-int/2addr")
        reg(0xb9, Opcode.SHR_INT, DexFormat.F12X, "shr-int/2addr")
        reg(0xba, Opcode.USHR_INT, DexFormat.F12X, "ushr-int/2addr")
        reg(0xbb, Opcode.ADD_LONG, DexFormat.F12X, "add-long/2addr")
        reg(0xbc, Opcode.SUB_LONG, DexFormat.F12X, "sub-long/2addr")
        reg(0xbd, Opcode.MUL_LONG, DexFormat.F12X, "mul-long/2addr")
        reg(0xbe, Opcode.DIV_LONG, DexFormat.F12X, "div-long/2addr")
        reg(0xbf, Opcode.REM_LONG, DexFormat.F12X, "rem-long/2addr")
        reg(0xc0, Opcode.AND_LONG, DexFormat.F12X, "and-long/2addr")
        reg(0xc1, Opcode.OR_LONG, DexFormat.F12X, "or-long/2addr")
        reg(0xc2, Opcode.XOR_LONG, DexFormat.F12X, "xor-long/2addr")
        reg(0xc3, Opcode.SHL_LONG, DexFormat.F12X, "shl-long/2addr")
        reg(0xc4, Opcode.SHR_LONG, DexFormat.F12X, "shr-long/2addr")
        reg(0xc5, Opcode.USHR_LONG, DexFormat.F12X, "ushr-long/2addr")
        reg(0xc6, Opcode.ADD_FLOAT, DexFormat.F12X, "add-float/2addr")
        reg(0xc7, Opcode.SUB_FLOAT, DexFormat.F12X, "sub-float/2addr")
        reg(0xc8, Opcode.MUL_FLOAT, DexFormat.F12X, "mul-float/2addr")
        reg(0xc9, Opcode.DIV_FLOAT, DexFormat.F12X, "div-float/2addr")
        reg(0xca, Opcode.REM_FLOAT, DexFormat.F12X, "rem-float/2addr")
        reg(0xcb, Opcode.ADD_DOUBLE, DexFormat.F12X, "add-double/2addr")
        reg(0xcc, Opcode.SUB_DOUBLE, DexFormat.F12X, "sub-double/2addr")
        reg(0xcd, Opcode.MUL_DOUBLE, DexFormat.F12X, "mul-double/2addr")
        reg(0xce, Opcode.DIV_DOUBLE, DexFormat.F12X, "div-double/2addr")
        reg(0xcf, Opcode.REM_DOUBLE, DexFormat.F12X, "rem-double/2addr")

        reg(0xd0, Opcode.ADD_INT_LIT, DexFormat.F22S, "add-int/lit16")
        reg(0xd1, Opcode.RSUB_INT, DexFormat.F22S, "rsub-int")
        reg(0xd2, Opcode.MUL_INT_LIT, DexFormat.F22S, "mul-int/lit16")
        reg(0xd3, Opcode.DIV_INT_LIT, DexFormat.F22S, "div-int/lit16")
        reg(0xd4, Opcode.REM_INT_LIT, DexFormat.F22S, "rem-int/lit16")
        reg(0xd5, Opcode.AND_INT_LIT, DexFormat.F22S, "and-int/lit16")
        reg(0xd6, Opcode.OR_INT_LIT, DexFormat.F22S, "or-int/lit16")
        reg(0xd7, Opcode.XOR_INT_LIT, DexFormat.F22S, "xor-int/lit16")
        reg(0xd8, Opcode.ADD_INT_LIT, DexFormat.F22B, "add-int/lit8")
        reg(0xd9, Opcode.RSUB_INT, DexFormat.F22B, "rsub-int/lit8")
        reg(0xda, Opcode.MUL_INT_LIT, DexFormat.F22B, "mul-int/lit8")
        reg(0xdb, Opcode.DIV_INT_LIT, DexFormat.F22B, "div-int/lit8")
        reg(0xdc, Opcode.REM_INT_LIT, DexFormat.F22B, "rem-int/lit8")
        reg(0xdd, Opcode.AND_INT_LIT, DexFormat.F22B, "and-int/lit8")
        reg(0xde, Opcode.OR_INT_LIT, DexFormat.F22B, "or-int/lit8")
        reg(0xdf, Opcode.XOR_INT_LIT, DexFormat.F22B, "xor-int/lit8")
        reg(0xe0, Opcode.SHL_INT_LIT, DexFormat.F22B, "shl-int/lit8")
        reg(0xe1, Opcode.SHR_INT_LIT, DexFormat.F22B, "shr-int/lit8")
        reg(0xe2, Opcode.USHR_INT_LIT, DexFormat.F22B, "ushr-int/lit8")

        reg(0xfa, Opcode.INVOKE_POLYMORPHIC, DexFormat.F45CC, "invoke-polymorphic", M)
        reg(0xfb, Opcode.INVOKE_POLYMORPHIC_RANGE, DexFormat.F4RCC, "invoke-polymorphic/range", M)
        reg(0xfc, Opcode.INVOKE_CUSTOM, DexFormat.F35C, "invoke-custom", C)
        reg(0xfd, Opcode.INVOKE_CUSTOM_RANGE, DexFormat.F3RC, "invoke-custom/range", C)
        reg(0xfe, Opcode.CONST_METHOD_HANDLE, DexFormat.F21C, "const-method-handle", IndexType.METHOD_HANDLE_REF)
        reg(0xff, Opcode.CONST_METHOD_TYPE, DexFormat.F21C, "const-method-type", IndexType.PROTO_REF)

        payload(0x0100, Opcode.PACKED_SWITCH_PAYLOAD, DexFormat.PACKED_SWITCH_PAYLOAD, "packed-switch-payload")
        payload(0x0200, Opcode.SPARSE_SWITCH_PAYLOAD, DexFormat.SPARSE_SWITCH_PAYLOAD, "sparse-switch-payload")
        payload(0x0300, Opcode.FILL_ARRAY_DATA_PAYLOAD, DexFormat.FILL_ARRAY_DATA_PAYLOAD, "array-payload")

        // Silence "unused" for the alias vals kept for readability at the registration site.
        check(N == IndexType.NONE)
    }
}
