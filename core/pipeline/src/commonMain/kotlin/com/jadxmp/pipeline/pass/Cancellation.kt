package com.jadxmp.pipeline.pass

/**
 * Cooperative cancellation hook for the analysis hot loops.
 *
 * `core:pipeline` is a pure-`commonMain` module and deliberately takes **no** coroutines dependency:
 * the orchestrator (`core:api`, which owns the `CoroutineScope`) bridges structured-concurrency
 * cancellation into the engine by supplying a [CancellationCheck] whose [ensureActive] throws when the
 * job is cancelled. Analysis loops call it periodically so a cancelled decompile stops promptly and,
 * on single-threaded targets (browser wasm/js), the caller can yield to keep the UI responsive.
 *
 * The default [None] never cancels — used by tests and by callers that don't need cancellation.
 *
 * jadx cross-reference: replaces jadx's `Thread.currentThread().isInterrupted()` checks.
 */
fun interface CancellationCheck {
    /** Throw (typically a cancellation exception) if the surrounding job has been cancelled. */
    fun ensureActive()

    companion object {
        /** A no-op check that never cancels. */
        val None: CancellationCheck = CancellationCheck { }
    }
}
