package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.HttpExecutor
import com.dan323.httpclient.core.domain.RequestOptions
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.time.Duration
import java.net.http.HttpClient
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers

class JdkHttpExecutor(
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : HttpExecutor {
    // Redirect policy and proxy are client-level in the JDK, so derive a client when the request needs them.
    private fun clientFor(o: RequestOptions): HttpClient {
        if (o.followRedirects && o.proxy == null) return client
        val b = HttpClient.newBuilder()
        if (!o.followRedirects) b.followRedirects(HttpClient.Redirect.NEVER) else b.followRedirects(HttpClient.Redirect.NORMAL)
        o.proxy?.let { b.proxy(ProxySelector.of(parseProxy(it))) }
        return b.build()
    }

    private fun parseProxy(p: String): InetSocketAddress {
        val u = URI.create(if ("://" in p) p else "http://$p")
        require(u.host != null) { "Invalid @proxy value: '$p'" }
        return InetSocketAddress(u.host, if (u.port > 0) u.port else 80)
    }

    // Restricted by java.net.http; the JDK sets these itself.
    private val restricted = setOf("host", "content-length", "connection", "expect", "upgrade")

    override fun execute(request: HttpRequest): HttpResponse {
        val builder = java.net.http.HttpRequest.newBuilder(URI.create(request.url))
            .method(request.method, request.body?.let(BodyPublishers::ofByteArray) ?: BodyPublishers.noBody())
        request.options.timeoutMillis?.let { builder.timeout(Duration.ofMillis(it)) }
        request.headers.filter { it.name.lowercase() !in restricted }.forEach { builder.header(it.name, it.value) }

        val start = System.nanoTime()
        val resp = clientFor(request.options).send(builder.build(), BodyHandlers.ofByteArray())
        val ms = (System.nanoTime() - start) / 1_000_000
        val headers = resp.headers().map().flatMap { (k, vs) -> vs.map { Header(k, it) } }
        return HttpResponse(resp.statusCode(), headers, resp.body(), ms)
    }
}
