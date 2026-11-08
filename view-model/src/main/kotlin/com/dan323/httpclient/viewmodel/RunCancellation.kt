package com.dan323.httpclient.viewmodel

import java.util.concurrent.CancellationException

/** How a [RunCancellation.track]ed run ended. */
sealed interface Tracked<out T> {
    data class Done<T>(val value: T) : Tracked<T>

    /** The run was cancelled with [RunCancellation.cancel] (before or while it was sending). */
    data object Cancelled : Tracked<Nothing>
}

/**
 * Cancels the one run a front end has in progress. [track] runs the blocking work on the calling thread;
 * [cancel] interrupts that thread, which makes the executor cancel the in-flight request (see `RequestSession`).
 *
 * The interrupt can never leak: [cancel] only interrupts while a tracked block is executing (checked under the same
 * lock that ends it), and a pooled thread comes back from [track] with its interrupt flag cleared when the run was
 * cancelled. A [cancel] that arrives before the block starts makes [track] return [Tracked.Cancelled] without
 * running it, so the "running" window before the request is sent can be cancelled too. Call [reset] when a new run
 * begins, so a stale cancel from an earlier run does not apply.
 */
class RunCancellation {
    private val lock = Any()
    private var thread: Thread? = null
    private var requested = false

    /** True after [cancel] until the next [reset]. */
    val isCancelRequested: Boolean get() = synchronized(lock) { requested }

    /** Starts a new run: forgets an earlier [cancel]. */
    fun reset() = synchronized(lock) { requested = false }

    /** Asks the tracked run to stop. Does nothing to threads that are not inside [track]. */
    fun cancel() = synchronized(lock) {
        requested = true
        thread?.interrupt()
    }

    fun <T> track(block: () -> T): Tracked<T> {
        synchronized(lock) {
            if (requested) return Tracked.Cancelled
            thread = Thread.currentThread()
        }
        val result = try {
            Result.success(block())
        } catch (e: Throwable) {
            Result.failure(e)
        }
        val cancelled = synchronized(lock) {
            thread = null
            // Still under the lock: a cancel() that came in time has interrupted us, a later one finds no thread.
            requested.also { if (it) Thread.interrupted() }
        }
        val failure = result.exceptionOrNull()
        return when {
            failure == null -> Tracked.Done(result.getOrThrow())
            cancelled && failure.isCancellation() -> Tracked.Cancelled
            else -> throw failure
        }
    }

    private fun Throwable.isCancellation() =
        this is CancellationException || this is InterruptedException || cause is InterruptedException
}
