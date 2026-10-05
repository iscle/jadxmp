package com.jadxmp.input.jvm

/** Bounded permutation plan retaining source identity even when several values have the same type. */
internal data class JvmStackChange(
    val startWord: Int,
    val inputs: List<JvmFrameValue>,
    /** Indices into [inputs], bottom to top. */
    val output: List<Int>,
)
