package com.jadxmp.ir.attr

/**
 * Boolean marker flags attachable to any [AttrNode].
 *
 * Flags are valueless facts (presence is the whole meaning); anything carrying data uses an
 * [AttrKey] instead. Later passes both set and read these.
 *
 * jadx: AFlag (a curated subset — the IR only defines the platform-neutral, pass-agnostic flags;
 * pass-specific flags are declared by the passes that own them).
 */
enum class AttrFlag {
    /** Node was introduced by the decompiler, not present in the original bytecode. */
    SYNTHETIC,

    /** Node is processed normally but must not be emitted to the generated source. */
    DONT_GENERATE,

    /** Node is emitted but wrapped in a comment (kept for the reader, not for the compiler). */
    COMMENT_OUT,

    /** Node may be removed entirely without changing semantics. */
    REMOVABLE,

    /** This value/expression is used inside another and must not be listed as a standalone arg. */
    WRAPPED,

    /** Represents `this`. */
    THIS,

    /** Represents `super`. */
    SUPER,

    /** Type of this element is fixed and must not be changed by type inference. */
    IMMUTABLE_TYPE,

    /** Method argument (a definition that has no defining instruction). */
    METHOD_ARGUMENT,

    /** Decompilation of this node is known to be incorrect; a warning should be surfaced. */
    INCONSISTENT_CODE,

    /** An error attribute is attached (see [IrAttrs.ERROR]); decompilation of this node failed. */
    HAS_ERROR,

    /**
     * This instruction defines a **block-local temporary**: a value defined AND read only within the one
     * block that contains its definition (never escaping to another block), recursively through inlined
     * sub-expressions. Such a temp is safe to re-declare per emission, so when its block is DUPLICATED
     * into several region positions (a shared straight-line tail placed in each arm) codegen declares it
     * locally in each copy rather than deduping it to one out-of-scope declaration. Set by the
     * structuring stage; read by the codegen backends. A cross-block / coalesced value is never marked.
     */
    BLOCK_LOCAL_TEMP,
}
