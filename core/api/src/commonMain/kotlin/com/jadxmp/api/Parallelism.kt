package com.jadxmp.api

/**
 * Platform decompile parallelism: how many classes the [DecompilerScheduler] may process at once.
 *
 * - JVM/Android: CPU-based (bounded by available processors).
 * - wasmJs/js: **1** — those targets are single-threaded, so parallelism is cooperative only; the
 *   scheduler still runs, just sequentially, yielding on cancellation checks.
 *
 * Lives behind `expect/actual` (ARCHITECTURE §4, CONVENTIONS "Concurrency") so `commonMain` never
 * touches a JVM thread/CPU API directly.
 */
expect fun defaultParallelism(): Int
