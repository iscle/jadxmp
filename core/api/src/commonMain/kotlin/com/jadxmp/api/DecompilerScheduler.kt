package com.jadxmp.api

import com.jadxmp.pipeline.pass.CancellationCheck
import com.jadxmp.pipeline.pass.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Coroutine orchestration for whole-input decompilation (ARCHITECTURE §4). Runs a per-class transform
 * over a bounded pool with **structured concurrency** and cooperative cancellation:
 *
 *  - **Bounded parallelism** — at most [parallelism] classes in flight (a [Semaphore]); the platform
 *    default is [defaultParallelism] (JVM: CPU-based; wasmJs/js: 1).
 *  - **Cancellation bridge** — the pipeline polls a [CancellationCheck] in its hot loops but takes no
 *    coroutines dependency; this scheduler supplies a check backed by the running coroutine's [Job], so
 *    cancelling the scope stops the analysis promptly. The pipeline signals via [CancellationSignal]
 *    (which its `PassRunner` re-throws rather than swallowing); we convert that back into ordinary
 *    structured-concurrency cancellation at the coroutine boundary.
 *
 * No threads, no `ExecutorService`, no `synchronized` — coroutines only (CONVENTIONS "Concurrency").
 */
class DecompilerScheduler(
    val parallelism: Int = defaultParallelism(),
) {
    /**
     * Apply [transform] to every item with bounded parallelism, returning results in input order.
     * [transform] is a blocking (non-suspending) unit of CPU work — the pipeline — and is handed a
     * [CancellationCheck] to poll. Cancelling the calling scope cancels all in-flight work.
     */
    suspend fun <T, R> map(items: List<T>, transform: (T, CancellationCheck) -> R): List<R> =
        coroutineScope {
            val gate = Semaphore(parallelism.coerceAtLeast(1))
            items.map { item ->
                async(Dispatchers.Default) {
                    gate.withPermit {
                        val job = currentCoroutineContext()[Job]
                        // Poll-based bridge: throw the pipeline's cancellation marker once the job is no
                        // longer active. Cheap enough to call in tight analysis loops.
                        val check = CancellationCheck { if (job == null || !job.isActive) throw CancellationSignal() }
                        try {
                            transform(item, check)
                        } catch (signal: CancellationSignal) {
                            // Re-raise as real coroutine cancellation so the scope tears down cleanly.
                            currentCoroutineContext().ensureActive()
                            throw signal
                        }
                    }
                }
            }.awaitAll()
        }
}
