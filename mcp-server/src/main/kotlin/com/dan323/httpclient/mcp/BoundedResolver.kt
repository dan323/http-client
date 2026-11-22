package com.dan323.httpclient.mcp

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A name lookup that gives up after [millis], or sooner when the call has less time left ([remaining], in milliseconds):
 * `InetAddress.getAllByName` has no timeout of its own, so a slow resolver could stretch the host checks past the call's
 * deadline. A lookup that times out, or that finds no time left, counts as "cannot resolve".
 *
 * A caller that stops waiting does not stop the lookup (the JDK may ignore the interrupt), so at most [workers] lookups run
 * at once and there is no queue: when all are busy the next lookup fails at once instead of piling up threads.
 */
class BoundedResolver(
    private val millis: Long = 10_000,
    private val remaining: () -> Long = { Long.MAX_VALUE },
    workers: Int = 4,
    private val lookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) : (String) -> List<InetAddress>, AutoCloseable {
    private val pool = ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS, SynchronousQueue(),
        { r -> Thread(r, "httpc-mcp-dns").also { it.isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

    override fun invoke(name: String): List<InetAddress> {
        val wait = minOf(millis, remaining())
        if (wait <= 0) throw UnknownHostException(name)
        val future = try { pool.submit<List<InetAddress>> { lookup(name) } } catch (_: RejectedExecutionException) { throw UnknownHostException(name) }
        try {
            return future.get(wait, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw UnknownHostException(name)
        } catch (e: ExecutionException) {
            throw (e.cause as? UnknownHostException) ?: UnknownHostException(name)
        }
    }

    override fun close() { pool.shutdownNow() }
}
