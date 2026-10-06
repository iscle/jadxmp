package com.jadxmp.oracle

import com.jadxmp.api.Decompiler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InputFailureAccountingTest {
    @Test fun parallelResultsRetainInputFailureAndSuccessfulReloadClearsIt() {
        JavaCompilation.compile(listOf(DecompiledClass("loaderrors.Empty", "package loaderrors; public interface Empty {}"))).use { original ->
            assertTrue(original.result.success, original.result.diagnostics.toString())
            val bytes = original.output.resolve("loaderrors/Empty.class").readBytes()
            val engine = Decompiler()
            assertEquals(1, engine.load("healthy.class", bytes))
            assertEquals(0, parallel(engine).errorCount)
            assertEquals(0, engine.load("broken.class", bytes.copyOf(7)))
            assertEquals(1, engine.diagnostics.size)
            repeat(2) {
                val result = parallel(engine)
                assertTrue(result.classes.isEmpty())
                assertEquals(1, result.errorCount, "a recorded parse failure is not successful empty output")
            }
            assertEquals(1, engine.load("healthy.class", bytes))
            assertTrue(engine.diagnostics.isEmpty())
            val result = parallel(engine)
            assertEquals(listOf("loaderrors.Empty"), result.classNames)
            assertEquals(0, result.errorCount)
        }
    }

    private fun parallel(engine: Decompiler): com.jadxmp.api.DecompilationResult {
        val finished = CountDownLatch(1)
        var completed: Result<com.jadxmp.api.DecompilationResult>? = null
        suspend { engine.decompileAllParallel() }.startCoroutine(object : Continuation<com.jadxmp.api.DecompilationResult> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<com.jadxmp.api.DecompilationResult>) {
                completed = result
                finished.countDown()
            }
        })
        assertTrue(finished.await(30, TimeUnit.SECONDS), "parallel decompilation did not finish")
        return requireNotNull(completed).getOrThrow()
    }
}
