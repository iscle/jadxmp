package com.jadxmp.oracle

/** Controlled failures and side effects for exception-state round trips. */
object ExceptionStateHooks {
    val trace = ArrayList<Int>()

    @JvmStatic
    fun checkpoint(mode: Int, stage: Int) {
        trace.add(stage)
        if (mode == stage || mode == -1) throw IllegalStateException("stage $stage")
    }

    @JvmStatic
    fun value(mode: Int): Int {
        checkpoint(mode, 1)
        return 42
    }
}
