package com.jadxmp.input

/**
 * The normalized instruction set every input parser maps onto.
 *
 * DEX and JVM bytecode have very different, redundant opcode spaces (DEX alone has ~230 opcodes with
 * `move`/`move-from16`/`move-16` variants that mean the same thing). We collapse those into a single
 * compact, format-agnostic set so the IR builder has exactly one instruction vocabulary to decode,
 * regardless of source format. Register widths (`_WIDE`), object-ness, and literal encodings that the
 * IR/type engine cares about are preserved; addressing-mode differences that it does not are dropped.
 *
 * jadx: jadx.api.plugins.input.insns.Opcode
 */
public enum class Opcode {
    UNKNOWN,
    NOP,

    // --- arithmetic ---
    ADD_INT,
    ADD_LONG,
    ADD_FLOAT,
    ADD_DOUBLE,
    ADD_INT_LIT,
    SUB_INT,
    SUB_LONG,
    SUB_FLOAT,
    SUB_DOUBLE,
    RSUB_INT,
    MUL_INT,
    MUL_LONG,
    MUL_FLOAT,
    MUL_DOUBLE,
    MUL_INT_LIT,
    DIV_INT,
    DIV_LONG,
    DIV_FLOAT,
    DIV_DOUBLE,
    DIV_INT_LIT,
    REM_INT,
    REM_LONG,
    REM_FLOAT,
    REM_DOUBLE,
    REM_INT_LIT,
    AND_INT,
    AND_LONG,
    AND_INT_LIT,
    OR_INT,
    OR_LONG,
    OR_INT_LIT,
    XOR_INT,
    XOR_LONG,
    XOR_INT_LIT,
    SHL_INT,
    SHL_LONG,
    SHL_INT_LIT,
    SHR_INT,
    SHR_LONG,
    SHR_INT_LIT,
    USHR_INT,
    USHR_LONG,
    USHR_INT_LIT,
    NEG_INT,
    NEG_LONG,
    NEG_FLOAT,
    NEG_DOUBLE,
    NOT_INT,
    NOT_LONG,

    // --- comparison ---
    CMPL_FLOAT,
    CMPG_FLOAT,
    CMPL_DOUBLE,
    CMPG_DOUBLE,
    CMP_LONG,

    // --- primitive conversions ---
    INT_TO_LONG,
    INT_TO_FLOAT,
    INT_TO_DOUBLE,
    INT_TO_BYTE,
    INT_TO_CHAR,
    INT_TO_SHORT,
    LONG_TO_INT,
    LONG_TO_FLOAT,
    LONG_TO_DOUBLE,
    FLOAT_TO_INT,
    FLOAT_TO_LONG,
    FLOAT_TO_DOUBLE,
    DOUBLE_TO_INT,
    DOUBLE_TO_LONG,
    DOUBLE_TO_FLOAT,

    // --- moves / constants ---
    MOVE,
    MOVE_WIDE,
    MOVE_OBJECT,
    MOVE_RESULT,
    MOVE_EXCEPTION,
    CONST,
    CONST_WIDE,
    CONST_STRING,
    CONST_CLASS,
    CONST_METHOD_HANDLE,
    CONST_METHOD_TYPE,

    // --- object / array ---
    CHECK_CAST,
    INSTANCE_OF,
    ARRAY_LENGTH,
    NEW_INSTANCE,
    NEW_ARRAY,
    FILLED_NEW_ARRAY,
    FILLED_NEW_ARRAY_RANGE,
    FILL_ARRAY_DATA,
    FILL_ARRAY_DATA_PAYLOAD,

    // array get
    AGET,
    AGET_WIDE,
    AGET_OBJECT,
    AGET_BOOLEAN,
    AGET_BYTE,
    AGET_CHAR,
    AGET_SHORT,
    // array put
    APUT,
    APUT_WIDE,
    APUT_OBJECT,
    APUT_BOOLEAN,
    APUT_BYTE,
    APUT_CHAR,
    APUT_SHORT,

    // instance field
    IGET,
    IPUT,
    // static field
    SGET,
    SPUT,

    // --- control flow ---
    GOTO,
    IF_EQ,
    IF_NE,
    IF_LT,
    IF_GE,
    IF_GT,
    IF_LE,
    IF_EQZ,
    IF_NEZ,
    IF_LTZ,
    IF_GEZ,
    IF_GTZ,
    IF_LEZ,
    PACKED_SWITCH,
    PACKED_SWITCH_PAYLOAD,
    SPARSE_SWITCH,
    SPARSE_SWITCH_PAYLOAD,
    RETURN,
    RETURN_VOID,
    THROW,

    // --- monitors ---
    MONITOR_ENTER,
    MONITOR_EXIT,

    // --- invokes ---
    INVOKE_VIRTUAL,
    INVOKE_VIRTUAL_RANGE,
    INVOKE_SUPER,
    INVOKE_SUPER_RANGE,
    INVOKE_DIRECT,
    INVOKE_DIRECT_RANGE,
    INVOKE_STATIC,
    INVOKE_STATIC_RANGE,
    INVOKE_INTERFACE,
    INVOKE_INTERFACE_RANGE,
    INVOKE_POLYMORPHIC,
    INVOKE_POLYMORPHIC_RANGE,
    INVOKE_CUSTOM,
    INVOKE_CUSTOM_RANGE,
}
