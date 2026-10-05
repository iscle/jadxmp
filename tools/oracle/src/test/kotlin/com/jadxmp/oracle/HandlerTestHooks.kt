package com.jadxmp.oracle

import java.io.IOException

/** Externally controlled effects make both exceptional and normal generated paths observable. */
object HandlerTestHooks {
    val trace = ArrayList<Int>()
    val failures = ArrayList<Throwable>()

    fun reset() { trace.clear(); failures.clear() }

    @JvmStatic
    fun observe(stage: Int) { trace.add(stage) }

    @JvmStatic
    @Throws(IOException::class)
    fun step(mode: Int, stage: Int) {
        observe(stage)
        val failure = when {
            mode == stage || (mode == 20 && stage in listOf(2, 4)) || (mode == 21 && stage == 4) -> IOException("stage $stage")
            mode == stage + 10 || (mode == 21 && stage == 2) -> IllegalStateException("stage $stage")
            else -> null
        }
        if (failure != null) { failures.add(failure); throw failure }
    }

    @JvmStatic
    fun unchecked(mode: Int, stage: Int) {
        observe(stage)
        if (mode == stage || mode == 30) throw IllegalStateException("stage $stage").also { failures.add(it) }
    }
}
