package com.jadxmp.ir.insn

/**
 * The compact, normalized IR instruction set.  **jadx: InsnType**
 *
 * The many DEX and JVM opcodes collapse into these: size/width variants (`const/4`, `const/16`,
 * `const-wide`, `iconst_*`, `ldc`), the register-pair encodings, and the JVM stack ops all map to a
 * single semantic opcode here. Operand types live on the [Operand]s, not in the opcode, so one
 * opcode covers every width. Kept deliberately small — it is the contract the IR-build stage targets
 * and every later stage reads.
 */
enum class IrOpcode {
    // --- constants ---
    CONST, // numeric/null constant (value on the LiteralOperand)
    CONST_STRING,
    CONST_CLASS,

    // --- arithmetic / logic (operator on ArithInstruction) ---
    ARITH,
    NEG,
    NOT,

    // --- moves & conversions ---
    MOVE,
    CAST, // primitive numeric conversion (i2l, d2f, …)
    CHECK_CAST, // reference cast with runtime check
    INSTANCE_OF,

    // --- comparisons ---
    // TODO(cmp): add CompareInstruction carrying the fcmpl/fcmpg (and dcmpl/dcmpg) NaN bias when CMP is implemented.
    CMP, // long/float/double compare producing -1/0/1
    IF, // two-way conditional branch (operator on IfInstruction)

    // --- control flow ---
    GOTO,
    SWITCH,
    RETURN,
    THROW,

    // --- monitors ---
    MONITOR_ENTER,
    MONITOR_EXIT,

    // --- arrays ---
    NEW_ARRAY,
    FILLED_NEW_ARRAY, // array created and populated in one op
    FILL_ARRAY, // bulk-fill an existing array from packed data
    ARRAY_LENGTH,
    ARRAY_GET,
    ARRAY_PUT,

    // --- objects & fields ---
    NEW_INSTANCE,
    INSTANCE_GET,
    INSTANCE_PUT,
    STATIC_GET,
    STATIC_PUT,

    // --- calls ---
    INVOKE,
    MOVE_RESULT, // pull the result of the preceding INVOKE/FILLED_NEW_ARRAY
    MOVE_EXCEPTION, // bind the caught exception at a handler entry

    // --- synthetic ops added by later passes (no bytecode counterpart) ---
    NOP,
    PHI, // SSA φ-function
    TERNARY, // a ? b : c
    CONSTRUCTOR, // normalized <init> invocation
    STRING_CONCAT, // reconstructed string concatenation
    BREAK,
    CONTINUE,
    ONE_ARG, // pass-through: emit the single argument as-is
}
