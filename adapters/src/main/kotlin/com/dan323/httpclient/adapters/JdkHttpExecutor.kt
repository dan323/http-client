package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.CookieJar
import com.dan323.httpclient.core.application.HttpLineSyntax
import com.dan323.httpclient.core.application.RedirectFollower
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.ProxyAddress
import com.dan323.httpclient.core.domain.RequestOptions
import com.dan323.httpclient.core.port.out.HttpExecutor
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Executes requests with the JDK client. Redirects are followed by [RedirectFollower], never by the JDK, so
 * every request is sent by a client with `Redirect.NEVER`: the default client already is one, and an injected
 * client with another policy (or any request naming an `@proxy`) uses a derived client, built once per proxy
 * and reused. A derived client copies the injected client's connect timeout, SSL context/parameters, authenticator,
 * cookie handler, executor and HTTP version; only the request priority cannot be read back and is not copied.
 * Call [close] to release derived clients (the injected client is left to its owner).
 *
 * With [maxBodyBytes] a longer response body is cut there while it streams in (the rest is never read), so an untrusted
 * server cannot fill the heap; null keeps the whole body.
 */
class JdkHttpExecutor(
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    private val maxBodyBytes: Int? = null,
) : HttpExecutor, AutoCloseable {
    init { require(maxBodyBytes == null || maxBodyBytes > 0) { "maxBodyBytes must be positive" } }

    private val derived = ConcurrentHashMap<Optional<ProxyAddress>, HttpClient>()

    private fun clientFor(o: RequestOptions): HttpClient {
        // Redirects are never left to the JDK (its forwarding of credential headers varies by version).
        if (client.followRedirects() == HttpClient.Redirect.NEVER && o.proxy == null) return client
        return derived.computeIfAbsent(Optional.ofNullable(o.proxy)) { key ->
            val p = key.orElse(null)
            val b = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).version(client.version())
            client.connectTimeout().ifPresent(b::connectTimeout)
            client.cookieHandler().ifPresent(b::cookieHandler)
            client.authenticator().ifPresent(b::authenticator)
            client.executor().ifPresent(b::executor)
            b.sslContext(client.sslContext()).sslParameters(client.sslParameters())
            // Keep the injected client's own proxy selector unless the request names a proxy.
            if (p != null) b.proxy(ProxySelector.of(resolve(p))) else client.proxy().ifPresent(b::proxy)
            b.build()
        }
    }

    private fun resolve(p: ProxyAddress): InetSocketAddress {
        val a = InetSocketAddress(p.host, p.port)
        require(!a.isUnresolved) { "Cannot resolve @proxy host: '${p.host}'" }
        return a
    }

    override fun close() {
        derived.values.forEach { it.close() }
        derived.clear()
    }

    /**
     * Redirects are followed here by [RedirectFollower] (no cookie jar), not by the JDK client: an explicit
     * `Cookie`, `Authorization` or `Proxy-Authorization` header is dropped when a redirect leaves the origin,
     * on every JDK version. `@no-redirect` returns the first response.
     */
    override fun execute(request: HttpRequest): HttpResponse =
        RedirectFollower(::executeOnce, CookieJar()).send(request.copy(options = request.options.copy(useCookieJar = false)))

    private fun executeOnce(request: HttpRequest): HttpResponse {
        val builder = java.net.http.HttpRequest.newBuilder(URI.create(request.url))
            .method(request.method, request.body?.let(BodyPublishers::ofByteArray) ?: BodyPublishers.noBody())
        request.options.timeoutMillis?.let { builder.timeout(Duration.ofMillis(it)) }
        // Restricted by java.net.http (the JDK sets them itself): dropped here; the parser and `--check` warn about them.
        request.headers.filter { !HttpLineSyntax.isClientControlledHeader(it.name) }.forEach { builder.header(it.name, it.value) }

        val start = System.nanoTime()
        // Client set-up (it may resolve a slow @proxy host) runs inside the future too, so the deadline and
        // interruption cover it.
        val inner = AtomicReference<CompletableFuture<*>?>()
        val future = CompletableFuture.supplyAsync { clientFor(request.options) }.thenCompose { c ->
            c.sendAsync(builder.build(), maxBodyBytes?.let { LimitedBodyHandler(it) } ?: BodyHandlers.ofByteArray()).also { inner.set(it) }
        }
        fun cancelAll() { future.cancel(true); inner.get()?.cancel(true) }
        val resp = try {
            // The timeout bounds the whole exchange (headers and body), not just the wait for headers.
            val t = request.options.timeoutMillis
            if (t != null) future.get(t, TimeUnit.MILLISECONDS) else future.get()
        } catch (e: TimeoutException) {
            cancelAll()
            throw java.net.http.HttpTimeoutException("request timed out after ${request.options.timeoutMillis} ms")
        } catch (e: InterruptedException) {
            cancelAll()
            Thread.currentThread().interrupt()
            throw CancellationException("Request cancelled").also { it.initCause(e) }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        val headers = responseHeaders(resp.headers().map())
        return HttpResponse(resp.statusCode(), headers, resp.body(), ms)
    }
}


/**
 * The response headers as a list. HTTP/2 pseudo-headers (`:status`, names starting with `:`) are not headers of the
 * message and the status is already reported separately, so they are left out.
 */
internal fun responseHeaders(map: Map<String, List<String>>): List<Header> =
    map.filterKeys { !it.startsWith(":") }.flatMap { (k, vs) -> vs.map { Header(k, it) } }
