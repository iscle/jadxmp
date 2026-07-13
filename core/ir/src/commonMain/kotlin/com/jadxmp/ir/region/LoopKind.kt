package com.jadxmp.ir.region

/**
 * The source form a [LoopRegion] should be emitted as.  **jadx: LoopType (family)**
 */
enum class LoopKind {
    /** `while (cond) { … }` — condition tested before the body. */
    WHILE,

    /** `do { … } while (cond)` — condition tested after the body. */
    DO_WHILE,

    /** `for (init; cond; update) { … }`. */
    FOR,

    /** `for (item : iterable) { … }`. */
    FOR_EACH,

    /** `while (true) { … }` — no exit condition (exits are `break`s inside the body). */
    INFINITE,
}
