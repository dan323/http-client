package com.dan323.httpclient.mcp

import com.dan323.httpclient.adapters.RequestRefusedException
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.HttpExecutor

/**
 * Counts every request that really goes out (chain dependencies and redirect hops included: put it beneath
 * `GuardedHttpExecutor`) and enforces one deadline for the whole tool call. Call [begin] before each tool call.
 * Each request's timeout is also cut to the time left, so the deadline holds even for a slow last request.
 */
class CallLimits(
    private val delegate: HttpExecutor,
    private val maxRequests: Int,
    private val totalMillis: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) : HttpExecutor {
    private var sent = 0
    private var deadline = 0L

    @Synchronized fun begin() { sent = 0; deadline = nanoTime() + totalMillis * 1_000_000 }

    override fun execute(request: HttpRequest): HttpResponse {
        val left = synchronized(this) {
            if (++sent > maxRequests) throw RequestRefusedException("Request refused: more than $maxRequests requests in one call (--max-requests)")
            val remaining = (deadline - nanoTime()) / 1_000_000
            if (remaining <= 0) throw RequestRefusedException("Request refused: the call used its ${totalMillis / 1000} s (--total-timeout)")
            remaining
        }
        val timeout = request.options.timeoutMillis?.coerceAtMost(left) ?: left
        return delegate.execute(request.copy(options = request.options.copy(timeoutMillis = timeout)))
    }
}
