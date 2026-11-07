package com.dan323.httpclient.core.application

import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs work that cannot be interrupted (JDK XPath) with a time limit and a hard cap on background threads.
 *
 * At most [maxThreads] daemon threads exist. There is no queue: a call starts at once or, when every slot is
 * busy (typically with evaluations that timed out and are still running), fails fast with `null`. A slot is
 * freed only when its evaluation really ends, so abandoned evaluations can never pile up beyond [maxThreads].
 */
internal class BoundedEvaluator(
    private val maxThreads: Int = DEFAULT_MAX_THREADS,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val threadName: String = "xpath-eval",
) {
    private val counter = AtomicInteger()
    private val pool = ThreadPoolExecutor(
        maxThreads, maxThreads, 0L, TimeUnit.MILLISECONDS, SynchronousQueue(),
        { r -> Thread(r, "$threadName-${counter.incrementAndGet()}").apply { isDaemon = true } },
    )

    /** The result of [block], or `null` when it failed, timed out, or no slot was free. Never throws. */
    fun <T> evaluate(block: () -> T?): T? {
        val future = try {
            pool.submit<T?> { block() }
        } catch (e: RejectedExecutionException) {
            return null // all slots busy with abandoned evaluations
        }
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            null
        } catch (e: ExecutionException) {
            null
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            null
        }
    }

    companion object {
        /** Concurrent XPath evaluations allowed in the whole process, abandoned (timed-out) ones included. */
        const val DEFAULT_MAX_THREADS = 4

        /** Time a caller waits for one evaluation. */
        const val DEFAULT_TIMEOUT_MILLIS = 2_000L
    }
}
