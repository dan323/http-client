package com.dan323.httpclient.core.application

import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs work that cannot be interrupted (JDK XPath) with a time limit and a hard cap on background threads.
 *
 * At most [maxThreads] daemon threads exist. Slots are counted by a semaphore, not by the pool: a call takes a
 * permit at once or, when every slot is busy (typically with evaluations that timed out and are still running),
 * fails fast with `null`. The permit is released only when its evaluation really ends (inside the task), so
 * abandoned evaluations can never pile up beyond [maxThreads], and a free slot is never refused because a
 * worker has not yet returned to the pool. Idle threads exit after [KEEP_ALIVE_SECONDS].
 */
internal class BoundedEvaluator(
    private val maxThreads: Int = DEFAULT_MAX_THREADS,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val threadName: String = "xpath-eval",
) {
    private val counter = AtomicInteger()
    private val slots = Semaphore(maxThreads)
    private val pool = ThreadPoolExecutor(
        maxThreads, maxThreads, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue(),
        { r -> Thread(r, "$threadName-${counter.incrementAndGet()}").apply { isDaemon = true } },
    ).apply { allowCoreThreadTimeOut(true) }

    /** The result of [block], or `null` when it failed, timed out, or no slot was free. Never throws. */
    fun <T> evaluate(block: () -> T?): T? {
        if (!slots.tryAcquire()) return null // all slots busy with abandoned evaluations
        val claimed = AtomicBoolean(false) // set by whoever owns the permit's release: the task, or a cancel before it started
        val future = try {
            pool.submit<T?> {
                if (!claimed.compareAndSet(false, true)) return@submit null
                try {
                    block()
                } finally {
                    slots.release()
                }
            }
        } catch (e: RejectedExecutionException) {
            slots.release()
            return null
        }
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            abandon(future, claimed)
            null
        } catch (e: ExecutionException) {
            null
        } catch (e: InterruptedException) {
            abandon(future, claimed)
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun abandon(future: java.util.concurrent.Future<*>, claimed: AtomicBoolean) {
        future.cancel(true)
        if (claimed.compareAndSet(false, true)) slots.release() // never started: nothing else will release
    }

    companion object {
        /** Concurrent XPath evaluations allowed in the whole process, abandoned (timed-out) ones included. */
        const val DEFAULT_MAX_THREADS = 4

        /** Idle evaluation threads exit after this long. */
        const val KEEP_ALIVE_SECONDS = 30L

        /** Time a caller waits for one evaluation. */
        const val DEFAULT_TIMEOUT_MILLIS = 2_000L
    }
}
