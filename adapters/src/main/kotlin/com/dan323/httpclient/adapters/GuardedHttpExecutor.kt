package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.CookieJar
import com.dan323.httpclient.core.application.RedirectFollower
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.HttpExecutor

/**
 * Sends only what the start flags of an untrusted caller allow (docs/mcp-safety-model.md): hosts in [hosts], methods that
 * do not change data unless [allowWrite], no `# @proxy`, and a total timeout of at most [maxTimeoutMillis] for each hop (a request
 * without `# @timeout` gets it too). It is a backstop: to bound a whole redirect chain, also pass the limit to
 * `SessionFactory.create(maxTimeoutMillis = ...)`, because the redirect follower builds its deadline before this guard runs. A refused request throws [RequestRefusedException] and nothing is sent.
 *
 * Wrap the executor that the redirect follower of the session sends each hop through, so every hop is checked.
 */
class GuardedHttpExecutor(
    private val delegate: HttpExecutor,
    private val hosts: HostAllowList = HostAllowList(),
    private val allowWrite: Boolean = false,
    private val maxTimeoutMillis: Long = 60_000,
) : HttpExecutor {
    init { require(maxTimeoutMillis > 0) { "maxTimeoutMillis must be positive" } }

    /**
     * Follows redirects itself (like [JdkHttpExecutor]) and checks every hop, so a delegate that would follow them on
     * its own (a direct call with redirects on) never sees an unchecked target. The session's follower turns following
     * off for the hops it sends here, so the redirects are then left to it, as before.
     */
    override fun execute(request: HttpRequest): HttpResponse {
        val timeout = request.options.timeoutMillis?.coerceAtMost(maxTimeoutMillis) ?: maxTimeoutMillis
        val options = request.options.copy(timeoutMillis = timeout, useCookieJar = false)
        return RedirectFollower(::executeChecked, CookieJar()).send(request.copy(options = options))
    }

    private fun executeChecked(request: HttpRequest): HttpResponse {
        if (!allowWrite && request.method.uppercase() !in READ_ONLY) {
            throw RequestRefusedException("Request refused: ${request.method} changes data (allow it with --allow-write)")
        }
        if (request.options.proxy != null) throw RequestRefusedException("Request refused: # @proxy is not allowed here")
        hosts.check(request.url)
        return delegate.execute(request.copy(options = request.options.copy(followRedirects = false)))
    }

    private companion object { val READ_ONLY = setOf("GET", "HEAD", "OPTIONS") }
}
