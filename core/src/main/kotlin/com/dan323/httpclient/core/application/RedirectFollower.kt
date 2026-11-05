package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.HttpExecutor
import java.net.URI

/**
 * Sends a request through [executor] using the session's [cookies].
 *
 * With the cookie jar off the executor handles everything (including redirects).
 * With the jar on, redirects are followed here, hop by hop, with the executor told not to follow any:
 * the `Cookie` header is recomputed from the jar for every hop's URL, `Set-Cookie` of every hop (the
 * `POST /login` -> `302` + `Set-Cookie` flow) is stored, and cookie jar state never leaks to another host
 * through the executor's own redirect handling. Rules: at most [MAX_REDIRECTS] hops (then an error, like
 * the JDK), `@no-redirect` returns the first response, 301/302 on a non-GET/HEAD method and 303 switch to
 * GET without body (and drop Content-* headers), 307/308 keep method and body, and an explicit
 * `Cookie`, `Authorization` or `Proxy-Authorization` header written in the request is dropped when the
 * redirect leaves the origin (scheme, host, port). Non-http(s) or missing `Location` ends the chain with
 * the redirect response itself. The reported duration is the sum over all hops. `@timeout` is one deadline
 * for the whole chain: each hop gets only the time that remains.
 *
 * Not thread-safe by itself beyond what [executor] and [cookies] guarantee; it keeps no state between calls.
 */
internal class RedirectFollower(
    private val executor: HttpExecutor,
    private val cookies: CookieJar,
) {
    /** Adds the jar's `Cookie` header unless the request sets its own `Cookie` header. */
    private fun withCookies(r: HttpRequest): HttpRequest {
        if (r.headers.any { it.name.equals("Cookie", ignoreCase = true) }) return r
        val cookie = cookies.headerFor(r.url) ?: return r
        return r.copy(headers = r.headers + Header("Cookie", cookie))
    }

    fun send(request: HttpRequest): HttpResponse {
        if (!request.options.useCookieJar) return executor.execute(request)
        val follow = request.options.followRedirects
        var current = request.copy(options = request.options.copy(followRedirects = false))
        var total = 0L
        var hops = 0
        val timeout = request.options.timeoutMillis
        val deadline = timeout?.let { System.nanoTime() + it * 1_000_000 }
        while (true) {
            if (deadline != null) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) throw java.net.http.HttpTimeoutException("request timed out after $timeout ms")
                current = current.copy(options = current.options.copy(timeoutMillis = left))
            }
            val sent = withCookies(current)
            val response = executor.execute(sent)
            total += response.durationMillis
            cookies.store(sent.url, response.headers)
            val next = if (follow) redirectTarget(current, response) else null
            if (next == null) return response.copy(durationMillis = total)
            if (++hops > MAX_REDIRECTS) throw IllegalStateException("Too many redirects (more than $MAX_REDIRECTS)")
            current = next
        }
    }

    private fun redirectTarget(request: HttpRequest, response: HttpResponse): HttpRequest? {
        if (response.statusCode !in setOf(301, 302, 303, 307, 308)) return null
        val location = response.header("Location")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val from = try { URI(request.url) } catch (e: Exception) { return null }
        val to = try { from.resolve(location) } catch (e: Exception) { return null }
        if (to.scheme?.lowercase() !in setOf("http", "https") || to.rawAuthority == null) return null
        val toGet = response.statusCode == 303 && request.method != "HEAD" ||
            (response.statusCode == 301 || response.statusCode == 302) && request.method == "POST"
        val sameOrigin = from.scheme.equals(to.scheme, true) && from.host.equals(to.host, true) && port(from) == port(to) &&
            (from.host != null || from.rawAuthority.equals(to.rawAuthority, true))
        val dropped = buildSet {
            if (!sameOrigin) addAll(listOf("cookie", "authorization", "proxy-authorization"))
            if (toGet) addAll(listOf("content-type", "content-length", "content-encoding", "transfer-encoding"))
        }
        return request.copy(
            method = if (toGet) "GET" else request.method,
            url = to.toString(),
            headers = request.headers.filter { it.name.lowercase() !in dropped },
            body = if (toGet) null else request.body,
        )
    }

    private fun port(u: URI) = if (u.port != -1) u.port else if (u.scheme.equals("https", true)) 443 else 80

    companion object {
        const val MAX_REDIRECTS = 10
    }
}
