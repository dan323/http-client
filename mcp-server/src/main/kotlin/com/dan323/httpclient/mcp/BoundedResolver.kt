package com.dan323.httpclient.mcp

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A name lookup that gives up after [millis]: `InetAddress.getAllByName` has no timeout of its own, so a slow resolver
 * could stretch the host checks past the call's deadline. A lookup that times out counts as "cannot resolve".
 */
class BoundedResolver(
    private val millis: Long = 10_000,
    private val lookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) : (String) -> List<InetAddress> {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "httpc-mcp-dns").also { it.isDaemon = true } }

    override fun invoke(name: String): List<InetAddress> {
        val future = pool.submit<List<InetAddress>> { lookup(name) }
        try {
            return future.get(millis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw UnknownHostException(name)
        } catch (e: ExecutionException) {
            throw (e.cause as? UnknownHostException) ?: UnknownHostException(name)
        }
    }
}
